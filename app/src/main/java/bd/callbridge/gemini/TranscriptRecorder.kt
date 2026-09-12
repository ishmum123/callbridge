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
 */
class TranscriptRecorder(
    private val callId: Long,
    private val turnDao: TurnDao,
    private val callDao: CallDao,
    private val startedAtMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var inputSamples: Long = 0
    private var outputSamples: Long = 0
    private val pendingInput = StringBuilder()
    private val pendingOutput = StringBuilder()

    private val _hangupRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once when [SystemPromptBuilder.HANGUP_TOKEN] appears in the output transcript. */
    val hangupRequested: SharedFlow<Unit> = _hangupRequested.asSharedFlow()

    /** Call alongside every `LiveSession.sendAudio(pcm)` to track input seconds sent. */
    fun onAudioSent(pcm: ShortArray) {
        inputSamples += pcm.size
    }

    /** Collects the session's events for the lifetime of the call; suspends until it completes. */
    suspend fun consume(events: Flow<LiveSessionEvent>) {
        events.collect { event -> handle(event) }
    }

    suspend fun handle(event: LiveSessionEvent) {
        when (event) {
            is LiveSessionEvent.AudioOut -> outputSamples += event.pcm.size

            is LiveSessionEvent.InputTranscript -> pendingInput.append(event.text)

            is LiveSessionEvent.OutputTranscript -> {
                pendingOutput.append(event.text)
                if (pendingOutput.contains(SystemPromptBuilder.HANGUP_TOKEN)) {
                    _hangupRequested.tryEmit(Unit)
                }
            }

            LiveSessionEvent.TurnComplete -> flushTurn()

            LiveSessionEvent.Interrupted -> flushTurn()

            is LiveSessionEvent.Error -> Unit
            LiveSessionEvent.Closed -> flushTurn()
        }
    }

    private suspend fun flushTurn() {
        if (pendingInput.isNotEmpty()) {
            turnDao.insert(
                TurnEntity(
                    callId = callId,
                    role = TurnRole.CALLER,
                    text = pendingInput.toString(),
                    tMs = nowMs() - startedAtMs,
                )
            )
            pendingInput.clear()
        }
        if (pendingOutput.isNotEmpty()) {
            turnDao.insert(
                TurnEntity(
                    callId = callId,
                    role = TurnRole.ASSISTANT,
                    text = pendingOutput.toString().replace(SystemPromptBuilder.HANGUP_TOKEN, "").trim(),
                    tMs = nowMs() - startedAtMs,
                )
            )
            pendingOutput.clear()
        }
    }

    /** Flushes any partial turn, then writes accumulated seconds/cost into the `calls` row. */
    suspend fun finish(endReason: String?) {
        flushTurn()
        val inputSeconds = inputSamples / Config.CAPTURE_SAMPLE_RATE_HZ.toDouble()
        val outputSeconds = outputSamples / Config.GEMINI_OUTPUT_SAMPLE_RATE_HZ.toDouble()
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
