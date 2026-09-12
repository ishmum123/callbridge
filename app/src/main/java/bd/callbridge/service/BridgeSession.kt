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
import bd.callbridge.gemini.GeminiLiveSession
import bd.callbridge.gemini.LiveSession
import bd.callbridge.gemini.LiveSessionEvent
import bd.callbridge.gemini.SessionTerminalState
import bd.callbridge.gemini.TranscriptRecorder
import bd.callbridge.gemini.VadMode
import bd.callbridge.store.dao.CallDao
import bd.callbridge.store.dao.TurnDao
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

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
 * Takes already-built [audioChunks]/[vadEvents] flows (normally [AudioPipeline.chunks] /
 * [AudioPipeline.vadEvents]) rather than an [AudioPipeline] instance directly, so unit tests can
 * drive this class with plain fake flows instead of standing up a real [VoiceCallCapture].
 * Collecting [audioChunks] is what starts/stops the underlying capture (see [AudioPipeline]'s own
 * doc comment) — cancelling the collector job in [stop] is enough to tear capture down too.
 *
 * VAD mode decision (stated per M3 brief): [VadMode.LOCAL_VAD]. The audio pipeline's own
 * [VadGate] already exists for barge-in and drives [LiveSession.sendActivityStart]/
 * [LiveSession.sendActivityEnd] here; [VadMode.GEMINI_VAD] would make [LiveSession.interrupt] a
 * no-op and give us no manual turn boundary to hang a greeting kick off (see [start]).
 *
 * Greeting decision: [LiveSession] has no "speak first" / text-turn API (the Live session here is
 * audio-modality only — see `docs/gemini-live.md`), so the greeting is produced by combining (a)
 * the existing system-prompt instruction ("শুরুতে সংক্ষেপে নিজের পরিচয় দাও...", i.e. "introduce
 * yourself briefly at the start") with (b) an immediate empty `activityStart`+`activityEnd` pair
 * sent right after `setupComplete`, which under manual VAD is exactly the turn-boundary signal
 * that prompts the model to generate its first response with nothing said yet.
 *
 * Hangup mechanism: [TranscriptRecorder.hangupRequested] (fuzzy match against
 * [bd.callbridge.gemini.SystemPromptBuilder.HANGUP_PHRASE] in the ASR'd output transcript — see
 * `docs/gemini-live.md` "HANGUP token" section, current mechanism as of M2) drains briefly to let
 * any already-buffered reply audio finish reaching the injector, then calls [onHangupRequested].
 * [LiveSession.terminalState] going [SessionTerminalState.Failed] (watchdog/socket death) also
 * calls [onHangupRequested], per spec's watchdog behavior of ending the call gracefully.
 */
class BridgeSession(
    private val liveSession: LiveSession,
    private val injector: Injector,
    private val transcriptRecorder: TranscriptRecorder,
    private val audioChunks: Flow<ShortArray>,
    private val vadEvents: Flow<VadGate.Event>,
    private val systemPrompt: String,
    private val callerProfile: CallerProfile,
    private val vadMode: VadMode,
    private val scope: CoroutineScope,
    /** Invoked (at most once) when the call should be hung up — normally
     *  [bd.callbridge.call.CallController.hangUp]. Suspend so the caller can await teardown
     *  ordering if it wants to; BridgeSession itself never blocks on it. */
    private val onHangupRequested: suspend () -> Unit,
    private val onStatus: (BridgeStatus) -> Unit = {},
    private val outputResampler: Resampler = Resampler(Config.GEMINI_OUTPUT_SAMPLE_RATE_HZ, Config.CAPTURE_SAMPLE_RATE_HZ),
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** How long [stop] waits for already-in-flight reply audio to reach the injector before
     *  tearing down when ending because of a hangup request (not a hard failure). */
    private val drainTimeoutMs: Long = 1_500L,
) {
    private val teardownMutex = Mutex()
    private var stopped = false
    private var hungUpTriggered = false

    private var isModelSpeaking = false
    private var lastSpeechEndedAtMs: Long? = null
    private var firstAudioLogged = false

    private var jobs = mutableListOf<Job>()

    /** Every launched child coroutine goes through this so a bug in one collector (a fake/real
     *  dependency throwing, a malformed event) logs and dies quietly instead of taking down the
     *  InCallService process — brief requirement "must never crash the InCallService". */
    private val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e(TAG, "Unhandled exception in BridgeSession coroutine ($context)", throwable)
    }

    /** Opens the Gemini session, starts capture/injection, and wires barge-in + hangup. Must be
     *  called at most once. */
    suspend fun start() {
        Log.i(TAG, "phase=OPENING caller=${callerProfile.number} vadMode=$vadMode")
        onStatus(BridgeStatus(phase = BridgePhase.OPENING, callerNumber = callerProfile.number))

        injector.open()
        logRouteProbe()

        try {
            liveSession.open(systemPrompt, callerProfile)
        } catch (e: Exception) {
            Log.e(TAG, "LiveSession.open failed; hanging up", e)
            scope.launch(exceptionHandler) { onHangupRequested() }
            return
        }
        Log.i(TAG, "phase=ACTIVE caller=${callerProfile.number} socket=open")
        onStatus(
            BridgeStatus(
                phase = BridgePhase.ACTIVE,
                callerNumber = callerProfile.number,
                socketOpen = true,
                injectorRoute = injector.route.name,
            )
        )

        jobs += liveSession.events.onEach { handleLiveEvent(it) }.launchIn(scope + exceptionHandler)
        jobs += liveSession.events.onEach { transcriptRecorder.handle(it) }.launchIn(scope + exceptionHandler)
        jobs += vadEvents.onEach { handleVadEvent(it) }.launchIn(scope + exceptionHandler)
        jobs += audioChunks.onEach { chunk ->
            liveSession.sendAudio(chunk)
            transcriptRecorder.onAudioSent(chunk)
        }.launchIn(scope + exceptionHandler)
        jobs += transcriptRecorder.hangupRequested.onEach { requestHangup("model closing phrase") }.launchIn(scope + exceptionHandler)
        jobs += liveSession.terminalState.onEach { terminal ->
            if (terminal is SessionTerminalState.Failed) {
                Log.w(TAG, "LiveSession terminal Failed: ${terminal.message}", terminal.cause)
                onStatus(currentStatusSnapshot(socketOpen = false))
                requestHangup("session failed: ${terminal.message}")
            }
        }.launchIn(scope + exceptionHandler)

        // Greeting kick (see class doc): an empty activityStart/activityEnd pair immediately
        // after setup, so the model's first turn is generated with nothing said yet — the system
        // prompt already instructs it to introduce itself in that first turn.
        if (vadMode == VadMode.LOCAL_VAD) {
            Log.i(TAG, "sending greeting kick (empty activityStart/activityEnd)")
            liveSession.sendActivityStart()
            liveSession.sendActivityEnd()
        }
    }

    private fun handleLiveEvent(event: LiveSessionEvent) {
        when (event) {
            is LiveSessionEvent.AudioOut -> {
                if (!isModelSpeaking) {
                    isModelSpeaking = true
                    val speechEndedAt = lastSpeechEndedAtMs
                    if (speechEndedAt != null && !firstAudioLogged) {
                        firstAudioLogged = true
                        Log.i(TAG, "round-trip caller-SpeechEnded -> first model audio: ${nowMs() - speechEndedAt}ms")
                    }
                }
                val resampler = if (event.sampleRate == Config.GEMINI_OUTPUT_SAMPLE_RATE_HZ) {
                    outputResampler
                } else {
                    // A model/rate change from the documented 24 kHz would silently mis-resample
                    // with a fixed-ratio resampler built for 24->16; log loudly rather than guess.
                    Log.w(TAG, "AudioOut sampleRate=${event.sampleRate}, expected ${Config.GEMINI_OUTPUT_SAMPLE_RATE_HZ}; resampling anyway with a fresh one-shot resampler")
                    Resampler(event.sampleRate, Config.CAPTURE_SAMPLE_RATE_HZ)
                }
                val resampled = resampler.process(event.pcm)
                if (resampled.isNotEmpty()) injector.write(resampled)
            }

            LiveSessionEvent.TurnComplete, LiveSessionEvent.Interrupted -> {
                isModelSpeaking = false
                firstAudioLogged = false
            }

            is LiveSessionEvent.Error -> Log.w(TAG, "LiveSessionEvent.Error: ${event.message}", event.cause)

            is LiveSessionEvent.OutputTranscript -> onStatus(currentStatusSnapshot(lastTranscriptLine = event.text))

            else -> Unit
        }
    }

    private fun handleVadEvent(event: VadGate.Event) {
        when (event) {
            VadGate.Event.SpeechStarted -> {
                lastSpeechEndedAtMs = null
                firstAudioLogged = false
                if (isModelSpeaking) {
                    Log.i(TAG, "barge-in: caller speech while model speaking -> interrupt()+flush()")
                    liveSession.interrupt()
                    injector.flush()
                    isModelSpeaking = false
                }
                if (vadMode == VadMode.LOCAL_VAD) liveSession.sendActivityStart()
            }

            VadGate.Event.SpeechEnded -> {
                lastSpeechEndedAtMs = nowMs()
                if (vadMode == VadMode.LOCAL_VAD) liveSession.sendActivityEnd()
            }
        }
    }

    private fun requestHangup(reason: String) {
        scope.launch(exceptionHandler) {
            teardownMutex.withLock {
                if (hungUpTriggered) return@withLock
                hungUpTriggered = true
                Log.i(TAG, "phase=ENDING reason=$reason")
                onStatus(currentStatusSnapshot(phase = BridgePhase.ENDING, endReason = reason))
            }
            onHangupRequested()
        }
    }

    /** Drains briefly (lets already-in-flight reply audio reach the injector), then tears
     *  everything down. Safe to call more than once (e.g. once from a hangup request and once
     *  from the InCallService's call-ended callback racing it) — only the first call does
     *  anything. */
    suspend fun stop(reason: String) {
        teardownMutex.withLock {
            if (stopped) return
            stopped = true
        }
        Log.i(TAG, "phase=ENDED reason=$reason — tearing down")
        if (!hungUpTriggered) {
            // A normal (non-hangup-triggered) teardown, e.g. the caller just hung up: still give
            // any final buffered audio a moment before cutting the injector off.
            delay(drainTimeoutMs)
        }
        jobs.forEach { it.cancel() }
        jobs.clear()
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
        Log.i(TAG, "RouteProbe route=${probe.route} deviceFound=${probe.deviceFound} preferredDeviceSet=${probe.preferredDeviceSet} routedDeviceId=${probe.routedDeviceId} routedDeviceType=$routedType detail=${probe.detail}")
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
    ): BridgeSession {
        val vadMode = VadMode.LOCAL_VAD
        val liveSession = GeminiLiveSession(
            authProvider = ApiKeyAuth(Config.geminiApiKey),
            vadMode = vadMode,
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
            audioChunks = pipeline.chunks,
            vadEvents = pipeline.vadEvents,
            systemPrompt = systemPrompt,
            callerProfile = callerProfile,
            vadMode = vadMode,
            scope = scope,
            onHangupRequested = onHangupRequested,
            onStatus = onStatus,
        )
    }
}
