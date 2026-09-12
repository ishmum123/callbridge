package bd.callbridge.service

import android.content.Context
import android.util.Log
import bd.callbridge.Config
import bd.callbridge.audio.AudioPipeline
import bd.callbridge.audio.Injector
import bd.callbridge.audio.InjectorFactory
import bd.callbridge.audio.RouteProbe
import bd.callbridge.audio.Resampler
import bd.callbridge.audio.TelephonyTxInjector
import bd.callbridge.audio.VadGate
import bd.callbridge.audio.VoiceCallCapture
import bd.callbridge.gemini.ApiKeyAuth
import bd.callbridge.gemini.CallerProfile
import bd.callbridge.gemini.FunctionDeclaration
import bd.callbridge.gemini.GeminiLiveSession
import bd.callbridge.gemini.LiveSession
import bd.callbridge.gemini.LiveSessionEvent
import bd.callbridge.gemini.SessionTerminalState
import bd.callbridge.gemini.SystemPromptBuilder
import bd.callbridge.gemini.TranscriptRecorder
import bd.callbridge.gemini.VadMode
import bd.callbridge.knowledge.HealthKnowledge
import bd.callbridge.knowledge.noInformationAvailable
import bd.callbridge.store.dao.CallDao
import bd.callbridge.store.dao.TurnDao
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val TAG = "BridgeSession"

/** Coarse phase for the status screen (spec §4.6) — kept intentionally small (M3 brief: "keep
 *  minimal; a StateFlow on the app singleton is fine"). */
enum class BridgePhase { OPENING, ACTIVE, ENDING, ENDED }

data class BridgeStatus(
    val phase: BridgePhase = BridgePhase.ENDED,
    val callerNumber: String? = null,
    val socketOpen: Boolean = false,
    val injectorRoute: String? = null,
    val lastTranscriptLine: String? = null,
    val endReason: String? = null,
)

/**
 * Per-call composition of capture -> Gemini Live -> injector (M3, spec §4.4 "wiring").
 *
 * Takes an already-built, single ordered [pipelineEvents] flow (normally
 * [AudioPipeline.events]) rather than an [AudioPipeline] instance directly, so unit tests can
 * drive this class with a plain fake flow instead of standing up a real [VoiceCallCapture].
 * Collecting [pipelineEvents] is what starts/stops the underlying capture (see [AudioPipeline]'s
 * own doc comment) — cancelling the collector job in [stop] is enough to tear capture down too.
 *
 * **Ordering (code review fix):** audio chunks and VAD transitions are consumed from the single
 * [pipelineEvents] flow, in one coroutine, in strict production order — not as two independently
 * scheduled flows (the original design), which could let `sendActivityStart` race ahead of/behind
 * the audio it's supposed to bracket.
 *
 * VAD mode decision (stated per M3 brief): [VadMode.LOCAL_VAD]. The audio pipeline's own
 * [VadGate] already exists for barge-in and drives [LiveSession.sendActivityStart]/
 * [LiveSession.sendActivityEnd] here; [VadMode.GEMINI_VAD] would make [LiveSession.interrupt] a
 * no-op.
 *
 * Greeting decision (code review fix): [LiveSession.sendTextTurn] sends a short Bangla text
 * instruction right after `setupComplete`, instead of the original empty
 * `activityStart`/`activityEnd` pair — that empty pair armed [GeminiLiveSession]'s 8s response
 * watchdog, which would fail the call 8s after every pickup if the model didn't answer an empty
 * turn. `sendTextTurn` deliberately never arms that watchdog (see its doc comment); verified live
 * against the real API in `GeminiLiveSmokeTest` (see `docs/gemini-live.md`).
 *
 * Hangup mechanism: [TranscriptRecorder.hangupRequested] (fuzzy match against
 * [bd.callbridge.gemini.SystemPromptBuilder.HANGUP_PHRASE] in the ASR'd output transcript — see
 * `docs/gemini-live.md` "HANGUP token" section, current mechanism as of M2) **drains until the
 * model's turn actually completes** (or [drainTimeoutMs] elapses) before calling
 * [onHangupRequested] — code review fix: the original fixed-sleep drain could still cut the
 * goodbye off mid-sentence, or fire on the very first streaming transcript delta that happened to
 * contain the phrase, well before the matching audio had even been generated.
 * [LiveSession.terminalState] going [SessionTerminalState.Failed] (watchdog/socket death) also
 * calls [onHangupRequested] (no drain — there's nothing left to finish).
 */
class BridgeSession(
    private val liveSession: LiveSession,
    private val injector: Injector,
    private val transcriptRecorder: TranscriptRecorder,
    private val pipelineEvents: Flow<AudioPipeline.PipelineEvent>,
    private val systemPrompt: String,
    private val callerProfile: CallerProfile,
    private val vadMode: VadMode,
    private val scope: CoroutineScope,
    /** Invoked (at most once) when the call should be hung up — normally
     *  [bd.callbridge.call.CallController.hangUp]. Suspend so the caller can await teardown
     *  ordering if it wants to; BridgeSession itself never blocks on it. */
    private val onHangupRequested: suspend () -> Unit,
    private val onStatus: (BridgeStatus) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** How long to wait for the model's current turn to actually finish (goodbye audio) before
     *  hanging up anyway, and (separately) how long [open] is allowed to take before [start]
     *  gives up. */
    private val drainTimeoutMs: Long = 4_000L,
    /** [LiveSessionEvent.AudioOut] handling — including the potentially-blocking
     *  [Injector.write] — runs on this dispatcher rather than [scope]'s own (code review fix:
     *  a blocking AudioTrack/injector write must not tie up a shared Dispatchers.Default thread). */
    private val injectorDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Health-demo tool backend (`docs/gemini-tools.md`); null when this call's [systemPrompt]
     *  declares no tools (e.g. the general shop-assistant persona), in which case no `ToolCall`
     *  event should ever arrive, but see [handleToolCall]'s fallback if one somehow does. */
    private val healthKnowledge: HealthKnowledge? = null,
    /** Caller-profile context passed to [healthKnowledge]'s lookup, same summary already baked
     *  into [systemPrompt] via `HealthPromptBn.healthSystemPrompt`. */
    private val callerContextSummary: String? = null,
    /** Dispatcher [handleToolCall]'s lookup job runs on — [Dispatchers.IO] in production (the
     *  lookup itself does network I/O); overridable so tests can drive it deterministically. */
    private val toolLookupDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val teardownMutex = Mutex()
    @Volatile private var stopped = false
    @Volatile private var hungUpTriggered = false
    @Volatile private var sessionActive = false

    @Volatile private var isModelSpeaking = false
    @Volatile private var suppressStaleAudio = false
    @Volatile private var lastSpeechEndedAtMs: Long? = null
    @Volatile private var firstAudioLogged = false

    private val jobs = CopyOnWriteArrayList<Job>()
    private var startJob: Job? = null

    /** Pending `lookup_health_info` lookups keyed by the `toolCall`'s id, so a
     *  [LiveSessionEvent.ToolCallCancelled] can cancel + drop exactly the right one(s). See
     *  [handleToolCall]/[handleToolCallCancelled]. */
    private val pendingToolCalls = ConcurrentHashMap<String, Job>()

    private val resamplerCache = mutableMapOf<Pair<Int, Int>, Resampler>()

    /** Every launched child coroutine goes through this so a bug in one collector (a fake/real
     *  dependency throwing, a malformed event) logs and dies quietly instead of taking down the
     *  InCallService process — brief requirement "must never crash the InCallService". */
    private val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e(TAG, "Unhandled exception in BridgeSession coroutine ($context)", throwable)
    }

    /**
     * Opens the Gemini session, starts capture/injection, and wires barge-in + hangup. Must be
     * called at most once. Returns promptly — the actual [LiveSession.open] network call (and
     * everything that depends on it) runs in a background job (code review fix: this lets a
     * caller assign this session as "the active one" and call [stop] concurrently without ever
     * blocking on or racing a slow/hanging `open()`; [stop] cancels that job directly rather than
     * waiting out a 10s setup timeout).
     */
    fun start() {
        if (stopped) {
            Log.i(TAG, "start() called after stop(); ignoring")
            return
        }
        Log.i(TAG, "phase=OPENING caller=${callerProfile.number} vadMode=$vadMode")
        onStatus(BridgeStatus(phase = BridgePhase.OPENING, callerNumber = callerProfile.number))

        try {
            injector.open()
            logRouteProbe()
        } catch (e: Exception) {
            Log.e(TAG, "injector.open() failed; hanging up", e)
            requestHangup("injector open failed: ${e.message}")
            return
        }

        // Subscribe to liveSession.events/terminalState BEFORE open() (LiveSession's documented
        // contract: events has no replay, so a setup-failure Error emitted during open() itself
        // would be missed by a subscriber that attaches afterwards — code review fix, this was
        // previously done after open() returned).
        jobs += liveSession.events.onEach { transcriptRecorder.handle(it) }.launchIn(scope + exceptionHandler)
        jobs += liveSession.events.onEach { handleLiveEvent(it) }.launchIn(scope + exceptionHandler)
        jobs += liveSession.terminalState.onEach { terminal ->
            if (terminal is SessionTerminalState.Failed) {
                Log.w(TAG, "LiveSession terminal Failed: ${terminal.message}", terminal.cause)
                onStatus(currentStatusSnapshot(socketOpen = false))
                requestHangup("session failed: ${terminal.message}")
            }
        }.launchIn(scope + exceptionHandler)
        jobs += transcriptRecorder.hangupRequested.onEach { onHangupPhraseDetected() }.launchIn(scope + exceptionHandler)

        startJob = scope.launch(exceptionHandler) {
            try {
                liveSession.open(systemPrompt, callerProfile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "LiveSession.open failed; hanging up", e)
                requestHangup("open failed: ${e.message}")
                return@launch
            }
            if (stopped) {
                // stop() raced us while open() was in flight and won; nothing left to do here —
                // stop()'s own teardown already (or will) close what open() just stood up.
                return@launch
            }
            sessionActive = true
            Log.i(TAG, "phase=ACTIVE caller=${callerProfile.number} socket=open")
            onStatus(currentStatusSnapshot(phase = BridgePhase.ACTIVE))

            jobs += pipelineEvents.onEach { handlePipelineEvent(it) }.launchIn(scope + exceptionHandler)

            // Greeting kick (see class doc): a text turn instructing the model to introduce
            // itself, since the audio-output session has no other "speak first" mechanism.
            Log.i(TAG, "sending greeting kick (text turn)")
            liveSession.sendTextTurn(SystemPromptBuilder.GREETING_TRIGGER)
        }
    }

    /** Single ordered consumer for both gated audio chunks and VAD transitions (see class doc's
     *  "Ordering" note) — this is what guarantees `sendActivityStart`/`sendAudio`/
     *  `sendActivityEnd` happen in encounter order. */
    private fun handlePipelineEvent(event: AudioPipeline.PipelineEvent) {
        when (event) {
            is AudioPipeline.PipelineEvent.Chunk -> {
                liveSession.sendAudio(event.pcm)
                transcriptRecorder.onAudioSent(event.pcm)
            }
            is AudioPipeline.PipelineEvent.Vad -> handleVadEvent(event.event)
        }
    }

    private suspend fun handleLiveEvent(event: LiveSessionEvent) {
        when (event) {
            is LiveSessionEvent.AudioOut -> {
                if (!sessionActive) return
                if (suppressStaleAudio) {
                    // Stale audio from a turn we already barged into; wait for
                    // Interrupted/TurnComplete before writing anything else to the injector
                    // (code review fix — previously flush() cleared what was already queued but
                    // kept writing every subsequent frame of the interrupted turn).
                    return
                }
                if (!isModelSpeaking) {
                    isModelSpeaking = true
                    val speechEndedAt = lastSpeechEndedAtMs
                    if (speechEndedAt != null && !firstAudioLogged) {
                        firstAudioLogged = true
                        Log.i(TAG, "round-trip caller-SpeechEnded -> first model audio: ${nowMs() - speechEndedAt}ms")
                    }
                }
                writeToInjector(event)
            }

            LiveSessionEvent.TurnComplete, LiveSessionEvent.Interrupted -> {
                isModelSpeaking = false
                suppressStaleAudio = false
                firstAudioLogged = false
            }

            is LiveSessionEvent.Error -> Log.w(TAG, "LiveSessionEvent.Error: ${event.message}", event.cause)

            is LiveSessionEvent.OutputTranscript -> onStatus(currentStatusSnapshot(lastTranscriptLine = event.text))

            is LiveSessionEvent.ToolCall -> handleToolCall(event)

            is LiveSessionEvent.ToolCallCancelled -> handleToolCallCancelled(event)

            else -> Unit
        }
    }

    /**
     * Handles a `lookup_health_info` [LiveSessionEvent.ToolCall]: runs [healthKnowledge]'s lookup
     * in a tracked background [Job] (never inline in this collector — [handleLiveEvent] must keep
     * draining audio/transcript events while a lookup, up to its own 6s budget, is in flight), then
     * replies via [LiveSession.sendToolResponse]. If [healthKnowledge] is null (tool declared
     * without a backend — shouldn't happen in production wiring, but must not leave the model
     * hanging) or the function name is unrecognized, replies immediately with
     * [noInformationAvailable] instead of silently dropping the call.
     */
    private fun handleToolCall(event: LiveSessionEvent.ToolCall) {
        if (healthKnowledge == null || event.name != "lookup_health_info") {
            Log.w(TAG, "ToolCall id=${event.id} name=${event.name}: no matching backend; replying with no-info")
            val job = scope.launch(exceptionHandler) {
                sendNoInfoResponse(event.id, event.name)
            }
            pendingToolCalls[event.id] = job
            jobs += job
            return
        }

        val question = event.args["question"]?.jsonPrimitive?.contentOrNull.orEmpty()
        Log.i(TAG, "ToolCall received id=${event.id} name=${event.name} question=$question")
        val job = scope.launch(exceptionHandler + toolLookupDispatcher) {
            val startedAtMs = nowMs()
            val answer = try {
                healthKnowledge.lookup(question, callerContextSummary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // HealthKnowledge implementations document that they never throw, but the model
                // must get *some* response even if that contract is violated — never leave a
                // ToolCall unanswered.
                Log.w(TAG, "ToolCall id=${event.id}: lookup threw unexpectedly", e)
                noInformationAvailable()
            }
            val lookupMs = nowMs() - startedAtMs
            // Atomically claim this id: if ToolCallCancelled already removed it, this returns
            // null and we drop the result instead of sending a stale toolResponse.
            if (pendingToolCalls.remove(event.id) == null) {
                Log.i(TAG, "ToolCall id=${event.id}: cancelled before lookup finished (provider=${answer.provider} lookupMs=$lookupMs); dropping")
                return@launch
            }
            Log.i(TAG, "ToolCall id=${event.id}: provider=${answer.provider} lookupMs=$lookupMs")
            val response = buildJsonObject {
                put("result", answer.answerEn)
                putJsonArray("sources") { answer.sources.forEach { add(it) } }
            }
            runCatching { liveSession.sendToolResponse(event.id, event.name, response) }
                .onSuccess { Log.i(TAG, "ToolCall id=${event.id}: response sent") }
                .onFailure { Log.w(TAG, "ToolCall id=${event.id}: sendToolResponse failed", it) }
        }
        pendingToolCalls[event.id] = job
        jobs += job
    }

    private suspend fun sendNoInfoResponse(id: String, name: String) {
        if (pendingToolCalls.remove(id) == null) return
        val fallback = noInformationAvailable()
        val response = buildJsonObject {
            put("result", fallback.answerEn)
            putJsonArray("sources") { }
        }
        runCatching { liveSession.sendToolResponse(id, name, response) }
            .onFailure { Log.w(TAG, "ToolCall id=$id: fallback sendToolResponse failed", it) }
    }

    /** Drops any pending lookup(s) for the cancelled ids without sending a (now stale)
     *  [LiveSession.sendToolResponse] — see [pendingToolCalls]'s doc. */
    private fun handleToolCallCancelled(event: LiveSessionEvent.ToolCallCancelled) {
        Log.i(TAG, "ToolCallCancelled ids=${event.ids}")
        event.ids.forEach { id -> pendingToolCalls.remove(id)?.cancel() }
    }

    /** Resamples on the calling (event-collector) dispatcher, but the actual [Injector.write] —
     *  potentially a blocking `AudioTrack.write` call — runs on [injectorDispatcher] (code review
     *  fix: must not tie up a shared `Dispatchers.Default` thread). A narrow [withContext] around
     *  just the write, rather than moving the whole event-collector job to a different
     *  dispatcher, keeps this single-flow collector's ordering guarantees simple to reason about. */
    private suspend fun writeToInjector(event: LiveSessionEvent.AudioOut) {
        // Resample to the injector's actually-negotiated rate, not a fixed assumption (code
        // review fix): TelephonyTxInjector can fall back to 8 kHz mono, and writing 16kHz-rate
        // samples to an 8kHz track plays back at half speed / garbled.
        val targetRate = injector.openSampleRateHz ?: Config.CAPTURE_SAMPLE_RATE_HZ
        if (event.sampleRate != Config.GEMINI_OUTPUT_SAMPLE_RATE_HZ) {
            Log.w(TAG, "AudioOut sampleRate=${event.sampleRate}, expected ${Config.GEMINI_OUTPUT_SAMPLE_RATE_HZ}; resampling anyway")
        }
        val resampler = resamplerCache.getOrPut(event.sampleRate to targetRate) {
            Log.i(TAG, "building resampler ${event.sampleRate}Hz -> ${targetRate}Hz (injector.openSampleRateHz=${injector.openSampleRateHz})")
            Resampler(event.sampleRate, targetRate)
        }
        val resampled = resampler.process(event.pcm)
        if (resampled.isNotEmpty()) withContext(injectorDispatcher) { injector.write(resampled) }
    }

    private fun handleVadEvent(event: VadGate.Event) {
        when (event) {
            VadGate.Event.SpeechStarted -> {
                lastSpeechEndedAtMs = null
                firstAudioLogged = false
                if (isModelSpeaking) {
                    Log.i(TAG, "barge-in: caller speech while model speaking -> interrupt()+flush()")
                    // interrupt() already sends the manual activityStart signal under
                    // LOCAL_VAD (see LiveSession.interrupt's doc) — do not also call
                    // sendActivityStart() below, or the server sees it twice (code review fix).
                    liveSession.interrupt()
                    injector.flush()
                    suppressStaleAudio = true
                    isModelSpeaking = false
                } else if (vadMode == VadMode.LOCAL_VAD) {
                    liveSession.sendActivityStart()
                }
            }

            VadGate.Event.SpeechEnded -> {
                lastSpeechEndedAtMs = nowMs()
                if (vadMode == VadMode.LOCAL_VAD) liveSession.sendActivityEnd()
            }
        }
    }

    /** The ASR'd output transcript matched the closing phrase. Drains until the model's turn
     *  actually completes (its goodbye audio has fully streamed) or [drainTimeoutMs] elapses,
     *  *then* hangs up — never on the first transcript delta that happens to contain the phrase
     *  (code review fix: that could cut the goodbye off mid-sentence, since the delta arrives
     *  well before the matching audio is done generating). */
    private fun onHangupPhraseDetected() {
        scope.launch(exceptionHandler) {
            Log.i(TAG, "hangup phrase detected in transcript; draining until TurnComplete or ${drainTimeoutMs}ms")
            // A fresh subscription to liveSession.events (not a side-channel signal) waiting for
            // the next TurnComplete/Interrupted — correct by construction: SharedFlow.first{}
            // only sees events emitted from this point on, so it can't observe a stale
            // already-finished turn from before the phrase was even detected.
            withTimeoutOrNull(drainTimeoutMs) {
                liveSession.events.first { it is LiveSessionEvent.TurnComplete || it is LiveSessionEvent.Interrupted }
            }
            requestHangup("model closing phrase")
        }
    }

    /** Marks this session as ending and invokes [onHangupRequested] — at most once, and never
     *  after [stop] has already torn things down (code review fix: the original version could
     *  invoke [onHangupRequested] once per trigger — e.g. once for the closing phrase and again
     *  for a terminal `Failed` racing it — which double-called `CallController.hangUp()` and,
     *  since that job wasn't tracked, could outlive [stop] and disconnect a *subsequent* call). */
    private fun requestHangup(reason: String) {
        val job = scope.launch(exceptionHandler) {
            val shouldHangUp = teardownMutex.withLock {
                if (hungUpTriggered || stopped) {
                    false
                } else {
                    hungUpTriggered = true
                    true
                }
            }
            if (shouldHangUp) {
                Log.i(TAG, "phase=ENDING reason=$reason")
                onStatus(currentStatusSnapshot(phase = BridgePhase.ENDING, endReason = reason))
                onHangupRequested()
            }
        }
        jobs += job
    }

    /**
     * Tears everything down. Safe to call more than once, and safe to call while [start] is
     * still opening (cancels [startJob] first rather than waiting out a 10s setup timeout) —
     * only the first call does anything. Cancels every collector job and **joins** them before
     * touching [injector]/[liveSession] (code review fix: cancelling a job and immediately
     * closing a shared resource it might still be mid-write on is a use-after-close race).
     */
    suspend fun stop(reason: String) {
        teardownMutex.withLock {
            if (stopped) return
            stopped = true
        }
        Log.i(TAG, "phase=ENDED reason=$reason — tearing down")
        startJob?.cancelAndJoin()
        val toJoin = jobs.toList()
        jobs.clear()
        toJoin.forEach { it.cancel() }
        toJoin.joinAll()
        runCatching { injector.flush() }.onFailure { Log.w(TAG, "injector.flush() during teardown threw", it) }
        runCatching { injector.close() }.onFailure { Log.w(TAG, "injector.close() during teardown threw", it) }
        runCatching { liveSession.close() }.onFailure { Log.w(TAG, "liveSession.close() during teardown threw", it) }
        runCatching { transcriptRecorder.finish(reason) }.onFailure { Log.w(TAG, "TranscriptRecorder.finish() during teardown threw", it) }
        onStatus(currentStatusSnapshot(phase = BridgePhase.ENDED, socketOpen = false, endReason = reason))
    }

    private fun currentStatusSnapshot(
        phase: BridgePhase = BridgePhase.ACTIVE,
        socketOpen: Boolean = true,
        endReason: String? = null,
        lastTranscriptLine: String? = null,
    ) = BridgeStatus(
        phase = phase,
        callerNumber = callerProfile.number,
        socketOpen = socketOpen,
        injectorRoute = injector.route.name,
        endReason = endReason,
        lastTranscriptLine = lastTranscriptLine,
    )

    private fun logRouteProbe() {
        val probe: RouteProbe = injector.probe()
        val routedType = (injector as? TelephonyTxInjector)?.getRoutedDevice()?.type
        Log.i(
            TAG,
            "RouteProbe route=${probe.route} deviceFound=${probe.deviceFound} preferredDeviceSet=${probe.preferredDeviceSet} " +
                "routedDeviceId=${probe.routedDeviceId} routedDeviceType=$routedType openSampleRateHz=${injector.openSampleRateHz} detail=${probe.detail}",
        )
    }
}

/**
 * Builds a [BridgeSession] wired to real Android/network dependencies for one call (M3 wiring
 * entry point). Kept separate from [BridgeSession] itself so the class doing the actual
 * composition logic stays constructible from plain fakes in tests.
 */
object BridgeSessionFactory {
    fun create(
        context: Context,
        callerProfile: CallerProfile,
        systemPrompt: String,
        callId: Long,
        turnDao: TurnDao,
        callDao: CallDao,
        startedAtMs: Long,
        scope: CoroutineScope,
        onHangupRequested: suspend () -> Unit,
        onStatus: (BridgeStatus) -> Unit = {},
        /** Tools declared for this call's Live session (`docs/gemini-tools.md`); empty for the
         *  general shop-assistant persona. See [bd.callbridge.service.BridgeSessionManager]. */
        tools: List<FunctionDeclaration> = emptyList(),
        /** Health-demo tool backend; only meaningful (and only ever called) when [tools] declares
         *  `lookup_health_info`. */
        healthKnowledge: bd.callbridge.knowledge.HealthKnowledge? = null,
        callerContextSummary: String? = null,
    ): BridgeSession {
        val vadMode = VadMode.LOCAL_VAD
        val liveSession = GeminiLiveSession(
            authProvider = ApiKeyAuth(Config.geminiApiKey),
            vadMode = vadMode,
            tools = tools,
        )
        val injector = InjectorFactory.create(Config.injectorRoute, context)
        val capture = VoiceCallCapture(context)
        val pipeline = AudioPipeline(capture)
        val transcriptRecorder = TranscriptRecorder(
            callId = callId,
            turnDao = turnDao,
            callDao = callDao,
            startedAtMs = startedAtMs,
        )
        return BridgeSession(
            liveSession = liveSession,
            injector = injector,
            transcriptRecorder = transcriptRecorder,
            pipelineEvents = pipeline.events,
            systemPrompt = systemPrompt,
            callerProfile = callerProfile,
            vadMode = vadMode,
            scope = scope,
            onHangupRequested = onHangupRequested,
            onStatus = onStatus,
            healthKnowledge = healthKnowledge,
            callerContextSummary = callerContextSummary,
        )
    }
}
