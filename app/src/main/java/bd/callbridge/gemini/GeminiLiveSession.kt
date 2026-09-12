package bd.callbridge.gemini

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Real OkHttp WebSocket client for the Gemini Live API `BidiGenerateContent` endpoint
 * (spec §4.4). See `docs/gemini-live.md` for verified message shapes and sources.
 *
 * Threading: OkHttp WebSocket callbacks fire on OkHttp's internal dispatcher threads. All state
 * mutation here is confined to atomics / a thread-safe [MutableSharedFlow]; the watchdog runs as
 * a coroutine on [scope].
 *
 * Single-use: [open] may be called at most once per instance (see [LiveSession.open]).
 *
 * Watchdog semantics (spec §4.4, see `docs/gemini-live.md` for the full rationale): the
 * [watchdogTimeoutMs] guard is armed only while a model *response is outstanding* — from
 * [sendActivityEnd] ([VadMode.LOCAL_VAD]) or the first response frame after our audio
 * ([VadMode.GEMINI_VAD]) until `turnComplete`/`interrupted` — so caller silence never trips it.
 * A separate, always-on [socketDeadTimeoutMs] guard catches a socket that stops producing any
 * frames at all (including outside an outstanding response).
 *
 * @param nowMs injection point for tests: pass a virtual clock (e.g. a `TestScope`'s scheduler
 *   `currentTime`) synced with [scope]'s dispatcher to drive the watchdog deterministically.
 */
class GeminiLiveSession(
    private val authProvider: AuthProvider,
    private val vadMode: VadMode = VadMode.LOCAL_VAD,
    private val modelId: String = bd.callbridge.Config.GEMINI_MODEL_ID,
    private val voiceName: String = "Sulafat",
    private val languageCode: String = "bn-IN",
    /** Function-calling tools to declare in the setup message (`docs/gemini-tools.md`). Empty by
     *  default so sessions that don't need tools send the same setup shape as before this param
     *  existed — see [SetupConfig.tools]'s `@EncodeDefault(NEVER)`. */
    private val tools: List<FunctionDeclaration> = emptyList(),
    private val client: OkHttpClient = defaultClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val watchdogTimeoutMs: Long = 8_000L,
    /** When false (demo default, see [bd.callbridge.Config.WATCHDOG_FATAL]) a watchdog expiry
     *  logs and clears the outstanding flag instead of failing the session. */
    private val watchdogFatal: Boolean = bd.callbridge.Config.WATCHDOG_FATAL,
    private val socketDeadTimeoutMs: Long = 60_000L,
    private val setupTimeoutMs: Long = 20_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val wsUrlBase: String =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
) : LiveSession {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _events = MutableSharedFlow<LiveSessionEvent>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<LiveSessionEvent> = _events.asSharedFlow()

    private val _terminalState = MutableStateFlow<SessionTerminalState>(SessionTerminalState.Open)
    override val terminalState: StateFlow<SessionTerminalState> = _terminalState.asStateFlow()

    private val droppedEventCount = AtomicLong(0)
    private val droppedAudioChunks = AtomicLong(0)
    override val droppedAudioChunkCount: Long get() = droppedAudioChunks.get()

    private var webSocket: WebSocket? = null

    // Dead-socket guard: a single long-lived, self-correcting loop watching lastAnyFrameAtMs.
    // Safe to let it sleep for a stale duration and recheck on wake (it never fires on stale
    // info, since it recomputes "remaining" before declaring failure) — unlike the response
    // watchdog below, it never needs to react *immediately* to a state change.
    private var deadSocketWatchdogJob: Job? = null
    private val lastAnyFrameAtMs = AtomicLong(0)

    // Response watchdog: only meaningful while responseOutstanding is true. Implemented as a
    // single-shot job that's cancelled and relaunched on every arm/reset, rather than folded into
    // the dead-socket loop above — a shared loop can be mid-sleep for the (much longer) dead-socket
    // window when a response becomes outstanding, and wouldn't wake up in time to start counting.
    @Volatile private var responseOutstanding = false
    private var responseWatchdogJob: Job? = null

    private val setupComplete = CompletableDeferred<Unit>()
    @Volatile private var opened = false
    @Volatile private var closed = false

    // GEMINI_VAD only: whether we've sent audio since the last turn ended, used to detect "the
    // first response frame after our audio" that arms the watchdog in that mode.
    private val audioSentSinceTurn = AtomicBoolean(false)

    override suspend fun open(systemPrompt: String, profile: CallerProfile) {
        check(!opened) { "GeminiLiveSession.open() called twice; sessions are single-use — open a new instance per call." }
        opened = true

        val key = authProvider.token()
        val url = "$wsUrlBase?key=$key"
        val request = Request.Builder().url(url).build()

        lastAnyFrameAtMs.set(nowMs())
        webSocket = client.newWebSocket(request, Listener())

        val setup = SetupEnvelope(
            setup = SetupConfig(
                model = "models/$modelId",
                generationConfig = GenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = SpeechConfig(
                        voiceConfig = VoiceConfig(PrebuiltVoiceConfig(voiceName)),
                        languageCode = languageCode,
                    ),
                ),
                systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
                realtimeInputConfig = RealtimeInputConfig(
                    automaticActivityDetection = AutomaticActivityDetection(
                        disabled = vadMode == VadMode.LOCAL_VAD,
                    ),
                    // HALF_DUPLEX demo mode: never let (possibly echoed/noisy) caller audio cut a reply.
                    activityHandling = if (bd.callbridge.Config.HALF_DUPLEX) "NO_INTERRUPTION" else "START_OF_ACTIVITY_INTERRUPTS",
                ),
                tools = if (tools.isNotEmpty()) listOf(Tool(functionDeclarations = tools)) else emptyList(),
            ),
        )
        send(json.encodeToString(setup))

        try {
            withTimeout(setupTimeoutMs) { setupComplete.await() }
        } catch (e: Exception) {
            val message = "Setup not acknowledged within ${setupTimeoutMs}ms"
            markTerminal(SessionTerminalState.Failed(message, e))
            emitChecked(LiveSessionEvent.Error(message, e))
            throw e
        }

        startWatchdog()
    }

    override fun sendAudio(pcm: ShortArray) {
        val ws = webSocket
        if (ws != null && ws.queueSize() > AUDIO_QUEUE_BACKPRESSURE_BYTES) {
            droppedAudioChunks.incrementAndGet()
            return
        }
        if (vadMode == VadMode.GEMINI_VAD) audioSentSinceTurn.set(true)
        val bytes = shortsToLittleEndianBytes(pcm)
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val envelope = RealtimeInputEnvelope(
            RealtimeInputPayload(audio = AudioBlob(data = b64, mimeType = INPUT_AUDIO_MIME_TYPE)),
        )
        send(json.encodeToString(envelope))
    }

    override fun sendTextTurn(text: String) {
        // Deliberately does not call armResponseWatchdog(): see LiveSession.sendTextTurn's doc —
        // a model that never answers the greeting's text turn must not fail the whole call 8s
        // after pickup. The always-on dead-socket watchdog (60s) still applies.
        send(
            json.encodeToString(
                ClientContentEnvelope(
                    ClientContentPayload(turns = listOf(ClientTurn(role = "user", parts = listOf(Part(text = text))))),
                ),
            ),
        )
    }

    override fun sendActivityStart() {
        if (vadMode != VadMode.LOCAL_VAD) return
        send(json.encodeToString(RealtimeInputEnvelope(RealtimeInputPayload(activityStart = JsonObject(emptyMap())))))
    }

    override fun sendActivityEnd() {
        if (vadMode != VadMode.LOCAL_VAD) return
        armResponseWatchdog()
        send(json.encodeToString(RealtimeInputEnvelope(RealtimeInputPayload(activityEnd = JsonObject(emptyMap())))))
    }

    override suspend fun sendToolResponse(id: String, name: String, response: JsonObject) {
        send(
            json.encodeToString(
                ToolResponseEnvelope(ToolResponsePayload(listOf(FunctionResponse(id = id, name = name, response = response)))),
            ),
        )
        // The tool response is itself a resumed "response outstanding" state: the model may now
        // go on to speak/answer, or (in principle) call another tool. Re-arm so a model that never
        // follows up after our answer is still caught by the watchdog (see class doc "Watchdog
        // semantics" and the toolCall pause in handleToolCall/onServerText).
        armResponseWatchdog()
    }

    override fun interrupt() {
        // GeminiVad: server's own activity detection already interrupts on caller speech; no-op.
        // LocalVad: activityStart under the default activityHandling=START_OF_ACTIVITY_INTERRUPTS
        // cancels the in-flight generation server-side (verified via https://ai.google.dev/api/live).
        if (vadMode == VadMode.LOCAL_VAD) {
            sendActivityStart()
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        deadSocketWatchdogJob?.cancel()
        responseWatchdogJob?.cancel()
        webSocket?.close(1000, "client close")
        webSocket = null
        markTerminal(SessionTerminalState.Closed)
        emitChecked(LiveSessionEvent.Closed)
        scope.cancel()
    }

    private fun send(text: String) {
        val ws = webSocket ?: return
        if (!ws.send(text)) {
            emitChecked(LiveSessionEvent.Error("WebSocket send buffer full or socket closed"))
        }
    }

    /** Records a dropped event (buffer overflow) and, for terminal events, updates [terminalState]. */
    private fun emitChecked(event: LiveSessionEvent) {
        if (!_events.tryEmit(event)) {
            droppedEventCount.incrementAndGet()
            Log.w(TAG, "Dropped event, buffer full (total dropped=${droppedEventCount.get()}): $event")
        }
    }

    /** Sets [terminalState] once; the first terminal state (usually a [SessionTerminalState.Failed]) wins. */
    private fun markTerminal(state: SessionTerminalState) {
        if (_terminalState.value is SessionTerminalState.Open) {
            _terminalState.value = state
        }
    }

    /** (Re)starts the single-shot response watchdog: fires if [watchdogTimeoutMs] elapses with no [noteResponseFrame]. */
    private fun armResponseWatchdog() {
        responseOutstanding = true
        responseWatchdogJob?.cancel()
        responseWatchdogJob = scope.launch {
            delay(watchdogTimeoutMs)
            val message = "Watchdog: model response outstanding with no frame for ${watchdogTimeoutMs}ms"
            if (!watchdogFatal) {
                // Demo mode: a stalled turn must not end the call. Log, clear the outstanding
                // flag so the next caller utterance can re-arm, and keep the socket open.
                Log.w(TAG, "$message — non-fatal (Config.WATCHDOG_FATAL=false), keeping session open")
                responseOutstanding = false
                return@launch
            }
            markTerminal(SessionTerminalState.Failed(message))
            emitChecked(LiveSessionEvent.Error(message))
            close()
        }
    }

    /**
     * Stops the response-watchdog timer without clearing [responseOutstanding] — used while a
     * [LiveSessionEvent.ToolCall] lookup is in flight: the model is legitimately silent waiting on
     * *our* [sendToolResponse], so the clock must not keep running against it, but the session is
     * still conceptually "awaiting a model response" for [handleServerContent]'s GEMINI_VAD arm
     * check. [sendToolResponse] (or [disarmResponseWatchdog] on cancellation) resumes/clears it.
     */
    private fun pauseResponseWatchdog() {
        responseWatchdogJob?.cancel()
        responseWatchdogJob = null
    }

    private fun disarmResponseWatchdog() {
        responseOutstanding = false
        audioSentSinceTurn.set(false)
        responseWatchdogJob?.cancel()
        responseWatchdogJob = null
    }

    /** Resets the single-shot response watchdog's deadline; equivalent to a fresh [armResponseWatchdog]. */
    private fun noteResponseFrame() {
        armResponseWatchdog()
    }

    private fun startWatchdog() {
        deadSocketWatchdogJob = scope.launch {
            while (isActive) {
                val elapsed = nowMs() - lastAnyFrameAtMs.get()
                val remaining = socketDeadTimeoutMs - elapsed
                if (remaining <= 0) {
                    val message = "Watchdog: no socket message at all for ${socketDeadTimeoutMs}ms"
                    markTerminal(SessionTerminalState.Failed(message))
                    emitChecked(LiveSessionEvent.Error(message))
                    close()
                    break
                }
                delay(remaining)
            }
        }
    }

    private fun onServerText(text: String) {
        lastAnyFrameAtMs.set(nowMs())
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            emitChecked(LiveSessionEvent.Error("Malformed server message: ${e.message}", e))
            return
        }

        obj["setupComplete"]?.let {
            setupComplete.complete(Unit)
            emitChecked(LiveSessionEvent.SetupComplete)
            return
        }
        obj["goAway"]?.let {
            val message = "Server sent goAway; connection closing soon"
            markTerminal(SessionTerminalState.Failed(message))
            emitChecked(LiveSessionEvent.Error(message))
            // Drive one terminal signal ourselves rather than waiting for the server's own close,
            // so callers see a deterministic Error -> Closed sequence.
            scope.launch { close() }
            return
        }
        obj["toolCall"]?.jsonObject?.let { handleToolCall(it); return }
        obj["toolCallCancellation"]?.jsonObject?.let { handleToolCallCancellation(it); return }
        obj["serverContent"]?.jsonObject?.let { handleServerContent(it) }
    }

    /** Parses a server `toolCall` (`docs/gemini-tools.md`) and emits one [LiveSessionEvent.ToolCall]
     *  per entry in `functionCalls[]`. Pauses the response watchdog for the duration of the
     *  lookup (see [pauseResponseWatchdog]) — the model going silent while we look the answer up
     *  is expected, not a hang. */
    private fun handleToolCall(tc: JsonObject) {
        pauseResponseWatchdog()
        val calls = tc["functionCalls"]?.jsonArray ?: return
        for (callEl in calls) {
            val call = callEl.jsonObject
            // Field order in the wild is name, args, id (not the reference doc's abstract order)
            // — parsed by key, not position (docs/gemini-tools.md).
            val name = call["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val id = call["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val args = call["args"]?.jsonObject ?: JsonObject(emptyMap())
            emitChecked(LiveSessionEvent.ToolCall(id, name, args))
        }
    }

    /** Parses a server `toolCallCancellation` and clears the response watchdog fully (same
     *  treatment as [LiveSessionEvent.Interrupted] — see `docs/gemini-tools.md`): whatever we were
     *  waiting to answer no longer matters. */
    private fun handleToolCallCancellation(tcc: JsonObject) {
        val ids = tcc["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        disarmResponseWatchdog()
        emitChecked(LiveSessionEvent.ToolCallCancelled(ids))
    }

    private fun handleServerContent(sc: JsonObject) {
        // GEMINI_VAD: the first response frame after our audio arms the watchdog (see class doc).
        if (vadMode == VadMode.GEMINI_VAD && !responseOutstanding && audioSentSinceTurn.get()) {
            armResponseWatchdog()
        } else if (responseOutstanding) {
            noteResponseFrame()
        }

        if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
            disarmResponseWatchdog()
            emitChecked(LiveSessionEvent.Interrupted)
        }

        sc["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
            if (it.isNotEmpty()) emitChecked(LiveSessionEvent.InputTranscript(it))
        }
        sc["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
            if (it.isNotEmpty()) emitChecked(LiveSessionEvent.OutputTranscript(it))
        }

        (sc["modelTurn"]?.jsonObject?.get("parts") as? kotlinx.serialization.json.JsonArray)?.forEach { partEl ->
            val part = partEl.jsonObject
            val inline = part["inlineData"]?.jsonObject ?: return@forEach
            val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            if (!mime.startsWith("audio/")) return@forEach
            val data = inline["data"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val bytes = Base64.getDecoder().decode(data)
            val rate = mime.substringAfter("rate=", "").toIntOrNull()
            if (rate != null && rate != EXPECTED_OUTPUT_SAMPLE_RATE_HZ) {
                emitChecked(
                    LiveSessionEvent.Error(
                        "Unexpected output audio sample rate: ${rate}Hz (expected ${EXPECTED_OUTPUT_SAMPLE_RATE_HZ}Hz, mimeType=$mime)"
                    )
                )
            }
            emitChecked(
                LiveSessionEvent.AudioOut(
                    littleEndianBytesToShorts(bytes),
                    sampleRate = rate ?: EXPECTED_OUTPUT_SAMPLE_RATE_HZ,
                )
            )
        }

        if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
            disarmResponseWatchdog()
            emitChecked(LiveSessionEvent.TurnComplete)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            onServerText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            // The real Gemini Live API sends every server message as a BINARY frame (opcode
            // 0x2), never text (opcode 0x1) — verified against the live endpoint (see
            // docs/gemini-live.md). This is the primary, required path, not a defensive fallback:
            // an OkHttp client that only implements onMessage(String) receives nothing at all.
            onServerText(bytes.utf8())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed) return
            closed = true
            deadSocketWatchdogJob?.cancel()
            responseWatchdogJob?.cancel()
            this@GeminiLiveSession.webSocket = null
            val message = "WebSocket failure: ${t.message}"
            markTerminal(SessionTerminalState.Failed(message, t))
            emitChecked(LiveSessionEvent.Error(message, t))
            emitChecked(LiveSessionEvent.Closed)
            scope.cancel()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Complete the close handshake so OkHttp finishes the socket and calls onClosed,
            // which is where we actually emit LiveSessionEvent.Closed.
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed) {
                closed = true
                deadSocketWatchdogJob?.cancel()
                responseWatchdogJob?.cancel()
                markTerminal(SessionTerminalState.Closed)
                emitChecked(LiveSessionEvent.Closed)
                scope.cancel()
            }
        }
    }

    companion object {
        private const val TAG = "GeminiLiveSession"

        /** Expected sample rate of Gemini's audio output; see [bd.callbridge.Config.GEMINI_OUTPUT_SAMPLE_RATE_HZ]. */
        const val EXPECTED_OUTPUT_SAMPLE_RATE_HZ = bd.callbridge.Config.GEMINI_OUTPUT_SAMPLE_RATE_HZ

        /**
         * Backpressure threshold for [sendAudio]: ~2s of 16 kHz mono PCM16 (16_000 * 2 bytes/sample
         * * 2s). Chunks are dropped rather than queued past this so a slow/degraded socket doesn't
         * accumulate unbounded latency between caller speech and Gemini hearing it.
         */
        const val AUDIO_QUEUE_BACKPRESSURE_BYTES: Long = 16_000L * 2 * 2

        /**
         * Shared client for all [GeminiLiveSession] instances that don't pass their own: one
         * connection pool/dispatcher instead of leaking a new one per call, plus a WebSocket
         * ping every 20s so OkHttp itself detects a silently-dead socket (triggering `onFailure`)
         * instead of relying solely on the application-level dead-socket watchdog.
         */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        }

        fun shortsToLittleEndianBytes(pcm: ShortArray): ByteArray {
            val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { buf.putShort(it) }
            return buf.array()
        }

        fun littleEndianBytesToShorts(bytes: ByteArray): ShortArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = ShortArray(bytes.size / 2)
            for (i in out.indices) out[i] = buf.short
            return out
        }
    }
}
