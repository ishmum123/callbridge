package bd.callbridge.gemini

import bd.callbridge.Config
import bd.callbridge.store.dao.CallDao
import bd.callbridge.store.dao.TurnDao
import bd.callbridge.store.TurnEntity
import bd.callbridge.store.TurnRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong

/**
 * Consumes a [LiveSession]'s events for one call, writes `turns` rows, and accumulates
 * input/output seconds + estimated cost into the `calls` row on [finish] (spec §4.5, §9).
 *
 * Input seconds come from PCM chunk lengths the caller sends to Gemini: the orchestration layer
 * (owns wiring [LiveSession] to the audio pipeline) must call [onAudioSent] with the same
 * [ShortArray] it passes to `LiveSession.sendAudio`, since that isn't observable from the
 * session's event stream. Output seconds are derived from [LiveSessionEvent.AudioOut] chunk
 * lengths, which *are* observable via [consume].
 *
 * Transcript rows are flushed per turn (on [LiveSessionEvent.TurnComplete] or
 * [LiveSessionEvent.Interrupted]) rather than per transcription delta, since the Live API streams
 * transcription text incrementally within a turn.
 *
 * Threading: [onAudioSent] is called by the audio-pipeline caller (a different coroutine/thread
 * than whatever collects [consume]), so the sample counters are [AtomicLong]s; the pending
 * transcript [StringBuilder]s are protected by [builderMutex] since [handle] is a public suspend
 * function that isn't guaranteed to only ever be invoked from a single sequential collector.
 */
class TranscriptRecorder(
    private val callId: Long,
    private val turnDao: TurnDao,
    private val callDao: CallDao,
    private val startedAtMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val inputSamples = AtomicLong(0)
    private val outputSamples = AtomicLong(0)
    private val builderMutex = Mutex()
    private val pendingInput = StringBuilder()
    private val pendingOutput = StringBuilder()

    private val _hangupRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emits once the accumulated output transcript contains [SystemPromptBuilder.HANGUP_PHRASE]
     * (fuzzy match — see [HangupPhraseMatcher]). AUDIO-only sessions can't carry a distinct
     * out-of-band "hang up now" signal, so the prompt instructs the model to close every call with
     * this fixed, distinctive spoken sentence, which we detect in its ASR'd output transcript.
     */
    val hangupRequested: SharedFlow<Unit> = _hangupRequested.asSharedFlow()

    /** Call alongside every `LiveSession.sendAudio(pcm)` to track input seconds sent. */
    fun onAudioSent(pcm: ShortArray) {
        inputSamples.addAndGet(pcm.size.toLong())
    }

    /** Collects the session's events for the lifetime of the call; suspends until it completes. */
    suspend fun consume(events: Flow<LiveSessionEvent>) {
        events.collect { event -> handle(event) }
    }

    suspend fun handle(event: LiveSessionEvent) {
        when (event) {
            is LiveSessionEvent.AudioOut -> outputSamples.addAndGet(event.pcm.size.toLong())

            is LiveSessionEvent.InputTranscript -> builderMutex.withLock { pendingInput.append(event.text) }

            is LiveSessionEvent.OutputTranscript -> {
                val hungUp = builderMutex.withLock {
                    pendingOutput.append(event.text)
                    HangupPhraseMatcher.containsHangupPhrase(pendingOutput)
                }
                if (hungUp) _hangupRequested.tryEmit(Unit)
            }

            LiveSessionEvent.TurnComplete -> flushTurn()

            LiveSessionEvent.Interrupted -> flushTurn()

            is LiveSessionEvent.Error -> Unit
            LiveSessionEvent.SetupComplete -> Unit
            LiveSessionEvent.Closed -> flushTurn()
            // Tool-call plumbing is handled by BridgeSession, not the transcript — nothing to
            // record here (the caller's question/model's answer around the tool call still show
            // up as ordinary Input/OutputTranscript deltas).
            is LiveSessionEvent.ToolCall -> Unit
            is LiveSessionEvent.ToolCallCancelled -> Unit
        }
    }

    private suspend fun flushTurn() {
        val (input, output) = builderMutex.withLock {
            val i = pendingInput.toString()
            pendingInput.clear()
            val o = pendingOutput.toString()
            pendingOutput.clear()
            i to o
        }
        if (input.isNotEmpty()) {
            turnDao.insert(
                TurnEntity(
                    callId = callId,
                    role = TurnRole.CALLER,
                    text = input,
                    tMs = nowMs() - startedAtMs,
                )
            )
        }
        if (output.isNotEmpty()) {
            // The hangup phrase is the model's genuine spoken goodbye, not a hidden control
            // token, so it's kept verbatim in the stored transcript rather than stripped.
            turnDao.insert(
                TurnEntity(
                    callId = callId,
                    role = TurnRole.ASSISTANT,
                    text = output,
                    tMs = nowMs() - startedAtMs,
                )
            )
        }
    }

    /** Flushes any partial turn, then writes accumulated seconds/cost into the `calls` row. */
    suspend fun finish(endReason: String?) {
        flushTurn()
        val inputSeconds = inputSamples.get() / Config.CAPTURE_SAMPLE_RATE_HZ.toDouble()
        val outputSeconds = outputSamples.get() / Config.GEMINI_OUTPUT_SAMPLE_RATE_HZ.toDouble()
        val cost = CostModel.estimateUsd(inputSeconds, outputSeconds)
        val call = callDao.findById(callId) ?: return
        callDao.update(
            call.copy(
                endedAt = nowMs(),
                endReason = endReason,
                inputSeconds = inputSeconds,
                outputSeconds = outputSeconds,
                estCostUsd = cost,
            )
        )
    }
}

/**
 * Fuzzy matcher for [SystemPromptBuilder.HANGUP_PHRASE] against an ASR output transcript, which
 * may add/drop punctuation, whitespace, or use a different Unicode normalization form than the
 * literal prompt string. Normalizes both sides the same way (NFC, strip whitespace/punctuation)
 * and checks containment, so the phrase is still recognized when split across multiple
 * [LiveSessionEvent.OutputTranscript] fragments (the caller accumulates fragments before calling
 * this, so a phrase split mid-word across two deltas is still whole in the accumulated buffer).
 */
internal object HangupPhraseMatcher {
    private val PUNCTUATION = setOf(
        '।', ',', '.', '!', '?', ':', ';', '-', '—', '–', '"', '\'', '“', '”', '‘', '’', '،', '(', ')',
    )

    private val normalizedPhrase: String = normalize(SystemPromptBuilder.HANGUP_PHRASE)

    fun containsHangupPhrase(text: CharSequence): Boolean =
        normalize(text.toString()).contains(normalizedPhrase)

    private fun normalize(text: String): String {
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        return buildString(nfc.length) {
            for (c in nfc) {
                if (c.isWhitespace() || c in PUNCTUATION) continue
                append(c)
            }
        }
    }
}
