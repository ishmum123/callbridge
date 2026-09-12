package bd.callbridge.gemini

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Real OkHttp WebSocket client for the Gemini Live API `BidiGenerateContent` endpoint
 * (spec §4.4). See `docs/gemini-live.md` for verified message shapes and sources.
 *
 * Threading: OkHttp WebSocket callbacks fire on OkHttp's internal dispatcher threads. All state
 * mutation here is confined to atomics / a thread-safe [MutableSharedFlow]; the watchdog runs as
 * a coroutine on [scope].
 *
 * @param nowMs injection point for tests: pass a virtual clock (e.g. a `TestScope`'s scheduler
 *   `currentTime`) synced with [scope]'s dispatcher to drive the watchdog deterministically.
 */
class GeminiLiveSession(
    private val authProvider: AuthProvider,
    private val vadMode: VadMode = VadMode.LOCAL_VAD,
    private val modelId: String = bd.callbridge.Config.GEMINI_MODEL_ID,
    private val voiceName: String = "Kore",
    private val languageCode: String = "bn-IN",
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val watchdogTimeoutMs: Long = 8_000L,
    private val setupTimeoutMs: Long = 10_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val wsUrlBase: String =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
) : LiveSession {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _events = MutableSharedFlow<LiveSessionEvent>(
        replay = 0,
        extraBufferCapacity = 1024,
    )
    override val events: SharedFlow<LiveSessionEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var watchdogJob: Job? = null
    private val setupComplete = CompletableDeferred<Unit>()
    private val lastMessageAtMs = AtomicLong(0)
    @Volatile private var closed = false

    override suspend fun open(systemPrompt: String, profile: CallerProfile) {
        val key = authProvider.token()
        val url = "$wsUrlBase?key=$key"
        val request = Request.Builder().url(url).build()

        lastMessageAtMs.set(nowMs())
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
                    activityHandling = "START_OF_ACTIVITY_INTERRUPTS",
                ),
            ),
        )
        send(json.encodeToString(setup))

        try {
            withTimeout(setupTimeoutMs) { setupComplete.await() }
        } catch (e: Exception) {
            _events.tryEmit(LiveSessionEvent.Error("Setup not acknowledged within ${setupTimeoutMs}ms", e))
            throw e
        }

        startWatchdog()
    }

    override fun sendAudio(pcm: ShortArray) {
        val bytes = shortsToLittleEndianBytes(pcm)
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val envelope = RealtimeInputEnvelope(
            RealtimeInputPayload(audio = AudioBlob(data = b64, mimeType = INPUT_AUDIO_MIME_TYPE)),
        )
        send(json.encodeToString(envelope))
    }

    override fun sendActivityStart() {
        if (vadMode != VadMode.LOCAL_VAD) return
        send(json.encodeToString(RealtimeInputEnvelope(RealtimeInputPayload(activityStart = JsonObject(emptyMap())))))
    }

    override fun sendActivityEnd() {
        if (vadMode != VadMode.LOCAL_VAD) return
        send(json.encodeToString(RealtimeInputEnvelope(RealtimeInputPayload(activityEnd = JsonObject(emptyMap())))))
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
        watchdogJob?.cancel()
        webSocket?.close(1000, "client close")
        webSocket = null
        _events.tryEmit(LiveSessionEvent.Closed)
    }

    private fun send(text: String) {
        val ws = webSocket ?: return
        if (!ws.send(text)) {
            _events.tryEmit(LiveSessionEvent.Error("WebSocket send buffer full or socket closed"))
        }
    }

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            while (isActive) {
                val elapsed = nowMs() - lastMessageAtMs.get()
                val remaining = watchdogTimeoutMs - elapsed
                if (remaining <= 0) {
                    _events.tryEmit(LiveSessionEvent.Error("Watchdog: no socket message for ${elapsed}ms"))
                    close()
                    break
                }
                delay(remaining)
            }
        }
    }

    private fun onServerText(text: String) {
        lastMessageAtMs.set(nowMs())
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            _events.tryEmit(LiveSessionEvent.Error("Malformed server message: ${e.message}", e))
            return
        }

        obj["setupComplete"]?.let {
            setupComplete.complete(Unit)
            return
        }
        obj["goAway"]?.let {
            _events.tryEmit(LiveSessionEvent.Error("Server sent goAway; connection closing soon"))
            return
        }
        obj["serverContent"]?.jsonObject?.let { handleServerContent(it) }
    }

    private fun handleServerContent(sc: JsonObject) {
        if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
            _events.tryEmit(LiveSessionEvent.Interrupted)
        }

        sc["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
            if (it.isNotEmpty()) _events.tryEmit(LiveSessionEvent.InputTranscript(it))
        }
        sc["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
            if (it.isNotEmpty()) _events.tryEmit(LiveSessionEvent.OutputTranscript(it))
        }

        (sc["modelTurn"]?.jsonObject?.get("parts") as? kotlinx.serialization.json.JsonArray)?.forEach { partEl ->
            val part = partEl.jsonObject
            val inline = part["inlineData"]?.jsonObject ?: return@forEach
            val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            if (!mime.startsWith("audio/")) return@forEach
            val data = inline["data"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val bytes = Base64.getDecoder().decode(data)
            _events.tryEmit(LiveSessionEvent.AudioOut(littleEndianBytesToShorts(bytes)))
        }

        if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
            _events.tryEmit(LiveSessionEvent.TurnComplete)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            onServerText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            // The Live API's WS protocol sends JSON text frames; binary frames aren't expected,
            // but handle defensively by decoding as UTF-8 JSON.
            onServerText(bytes.utf8())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            lastMessageAtMs.set(nowMs())
            _events.tryEmit(LiveSessionEvent.Error("WebSocket failure: ${t.message}", t))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed) {
                closed = true
                watchdogJob?.cancel()
                _events.tryEmit(LiveSessionEvent.Closed)
            }
        }
    }

    companion object {
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
