package bd.callbridge.gemini

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * MockWebServer-backed tests for [GeminiLiveSession]: setup message shape, audio chunk encoding,
 * and event parsing for audio/transcripts/interrupted/turnComplete. These use real threads and
 * real (short) timeouts rather than a virtual-time TestDispatcher, since the behavior under test
 * spans real OkHttp WebSocket I/O threads that a TestDispatcher's virtual clock doesn't control.
 */
class GeminiLiveSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun wsUrl(): String = server.url("/ws").toString().replaceFirst("http://", "ws://")

    /** A server-side harness: captures inbound text frames, hands back the server [WebSocket]
     *  once connected so the test can push server->client messages. */
    private class ServerHarness {
        val received = CopyOnWriteArrayList<String>()
        val socketRef = AtomicReference<WebSocket?>(null)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketRef.set(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Echo the close handshake back so OkHttp/MockWebServer can tear the connection
                // down cleanly instead of leaving it half-closed (which hangs server.shutdown()).
                webSocket.close(code, reason)
            }
        }

        suspend fun awaitSocket(): WebSocket = withTimeout(5_000) {
            var s = socketRef.get()
            while (s == null) {
                delay(10)
                s = socketRef.get()
            }
            s
        }

        suspend fun awaitMessageCount(n: Int) = withTimeout(5_000) {
            while (received.size < n) delay(10)
        }
    }

    private fun newSession(
        harness: ServerHarness,
        vadMode: VadMode = VadMode.LOCAL_VAD,
        watchdogTimeoutMs: Long = 8_000L,
        socketDeadTimeoutMs: Long = 60_000L,
    ): GeminiLiveSession {
        server.enqueue(MockResponse().withWebSocketUpgrade(harness.listener))
        return GeminiLiveSession(
            authProvider = ApiKeyAuth("test-key"),
            vadMode = vadMode,
            client = client,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            watchdogTimeoutMs = watchdogTimeoutMs,
            socketDeadTimeoutMs = socketDeadTimeoutMs,
            wsUrlBase = wsUrl(),
        )
    }

    private val profile = CallerProfile(number = "01700000000", name = "Karim", village = "Dumuria", occupation = "কৃষক")

    @Test
    fun `setup message has the verified BidiGenerateContent shape`() = runBlocking {
        val harness = ServerHarness()
        val session = newSession(harness)

        val openJob = launch { session.open("SYSTEM PROMPT TEXT", profile) }
        harness.awaitMessageCount(1)
        harness.awaitSocket().send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        val setup = Json.parseToJsonElement(harness.received[0]).jsonObject["setup"]!!.jsonObject
        assertEquals("models/gemini-3.1-flash-live-preview", setup["model"]!!.jsonPrimitive.content)
        val genConfig = setup["generationConfig"]!!.jsonObject
        assertTrue(genConfig["responseModalities"].toString().contains("AUDIO"))
        val speech = genConfig["speechConfig"]!!.jsonObject
        assertEquals("bn-IN", speech["languageCode"]!!.jsonPrimitive.content)
        assertEquals(
            "Sulafat",
            speech["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content,
        )
        assertTrue(setup["systemInstruction"]!!.jsonObject["parts"].toString().contains("SYSTEM PROMPT TEXT"))
        assertTrue(setup.containsKey("inputAudioTranscription"))
        assertTrue(setup.containsKey("outputAudioTranscription"))
        val rtc = setup["realtimeInputConfig"]!!.jsonObject
        assertEquals(true, rtc["automaticActivityDetection"]!!.jsonObject["disabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("START_OF_ACTIVITY_INTERRUPTS", rtc["activityHandling"]!!.jsonPrimitive.content)

        session.close()
    }

    @Test
    fun `GeminiVad mode leaves automatic activity detection enabled`() = runBlocking {
        val harness = ServerHarness()
        val session = newSession(harness, vadMode = VadMode.GEMINI_VAD)

        val openJob = launch { session.open("prompt", profile) }
        harness.awaitMessageCount(1)
        harness.awaitSocket().send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        val setup = Json.parseToJsonElement(harness.received[0]).jsonObject["setup"]!!.jsonObject
        val rtc = setup["realtimeInputConfig"]!!.jsonObject
        assertEquals(false, rtc["automaticActivityDetection"]!!.jsonObject["disabled"]!!.jsonPrimitive.content.toBoolean())

        session.close()
    }

    @Test
    fun `sendAudio base64-encodes little-endian PCM16 with the documented mimeType`() = runBlocking {
        val harness = ServerHarness()
        val session = newSession(harness)
        val openJob = launch { session.open("p", profile) }
        harness.awaitMessageCount(1)
        harness.awaitSocket().send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        val pcm = shortArrayOf(0, 1, -1, 32767, -32768, 12345)
        session.sendAudio(pcm)
        harness.awaitMessageCount(2)

        val audioMsg = Json.parseToJsonElement(harness.received[1]).jsonObject["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals(INPUT_AUDIO_MIME_TYPE, audioMsg["mimeType"]!!.jsonPrimitive.content)
        val decodedBytes = Base64.getDecoder().decode(audioMsg["data"]!!.jsonPrimitive.content)
        val decoded = GeminiLiveSession.littleEndianBytesToShorts(decodedBytes)
        assertTrue(pcm.contentEquals(decoded))

        session.close()
    }

    @Test
    fun `interrupt in LocalVad mode sends activityStart`() = runBlocking {
        val harness = ServerHarness()
        val session = newSession(harness, vadMode = VadMode.LOCAL_VAD)
        val openJob = launch { session.open("p", profile) }
        harness.awaitMessageCount(1)
        harness.awaitSocket().send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        session.interrupt()
        harness.awaitMessageCount(2)
        val payload = Json.parseToJsonElement(harness.received[1]).jsonObject["realtimeInput"]!!.jsonObject
        assertTrue(payload.containsKey("activityStart"))

        session.close()
    }

    @Test
    fun `interrupt in GeminiVad mode sends nothing`() = runBlocking {
        val harness = ServerHarness()
        val session = newSession(harness, vadMode = VadMode.GEMINI_VAD)
        val openJob = launch { session.open("p", profile) }
        harness.awaitMessageCount(1)
        harness.awaitSocket().send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        session.interrupt()
        delay(200)
        assertEquals(1, harness.received.size)

        session.close()
    }

    @Test
    fun `parses audio, transcript, interrupted and turnComplete server events`() = runBlocking {
        val harness = ServerHarness()
        val session = newSession(harness)
        val openJob = launch { session.open("p", profile) }
        harness.awaitMessageCount(1)
        val serverWs = harness.awaitSocket()
        serverWs.send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        val events = mutableListOf<LiveSessionEvent>()
        val collectJob = launch { session.events.toList(events) }
        delay(50)

        val audioB64 = Base64.getEncoder().encodeToString(
            GeminiLiveSession.shortsToLittleEndianBytes(shortArrayOf(100, 200, 300))
        )
        serverWs.send(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$audioB64"}}]}}}"""
        )
        serverWs.send("""{"serverContent":{"inputTranscription":{"text":"ধান কীভাবে"}}}""")
        serverWs.send("""{"serverContent":{"outputTranscription":{"text":"চাষ করুন"}}}""")
        serverWs.send("""{"serverContent":{"interrupted":true}}""")
        serverWs.send("""{"serverContent":{"turnComplete":true}}""")

        withTimeout(5_000) {
            while (events.count { it is LiveSessionEvent.TurnComplete } == 0) delay(10)
        }

        assertTrue(events.any { it is LiveSessionEvent.AudioOut && it.pcm.contentEquals(shortArrayOf(100, 200, 300)) })
        assertTrue(events.any { it is LiveSessionEvent.InputTranscript && it.text == "ধান কীভাবে" })
        assertTrue(events.any { it is LiveSessionEvent.OutputTranscript && it.text == "চাষ করুন" })
        assertTrue(events.any { it is LiveSessionEvent.Interrupted })
        assertTrue(events.any { it is LiveSessionEvent.TurnComplete })

        collectJob.cancel()
        session.close()
    }

    @Test
    fun `response watchdog does not fire on caller silence alone`() = runBlocking {
        val harness = ServerHarness()
        // Short response-watchdog timeout, but no sendActivityEnd/response is ever outstanding,
        // so plain silence (no caller speech, nothing sent) must never trip it.
        val session = newSession(harness, watchdogTimeoutMs = 300L, socketDeadTimeoutMs = 60_000L)
        val openJob = launch { session.open("p", profile) }
        harness.awaitMessageCount(1)
        harness.awaitSocket().send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        val events = mutableListOf<LiveSessionEvent>()
        val collectJob = launch { session.events.toList(events) }

        delay(900) // several multiples of the 300ms response-watchdog window
        assertTrue(events.none { it is LiveSessionEvent.Error })

        collectJob.cancel()
        session.close()
    }

    @Test
    fun `response watchdog fires when a response is outstanding with no frames`() = runBlocking {
        val harness = ServerHarness()
        val session = newSession(harness, watchdogTimeoutMs = 300L, socketDeadTimeoutMs = 60_000L)
        val openJob = launch { session.open("p", profile) }
        harness.awaitMessageCount(1)
        harness.awaitSocket().send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        val events = mutableListOf<LiveSessionEvent>()
        val collectJob = launch { session.events.toList(events) }

        // Caller finished speaking: a response is now outstanding, but the server never answers.
        session.sendActivityEnd()

        withTimeout(5_000) {
            while (events.none { it is LiveSessionEvent.Error }) delay(10)
        }
        assertTrue(events.any { it is LiveSessionEvent.Error && it.message.contains("outstanding") })
        assertTrue(events.any { it is LiveSessionEvent.Closed })

        collectJob.cancel()
    }

    @Test
    fun `turnComplete disarms the response watchdog`() = runBlocking {
        val harness = ServerHarness()
        val session = newSession(harness, watchdogTimeoutMs = 300L, socketDeadTimeoutMs = 60_000L)
        val openJob = launch { session.open("p", profile) }
        harness.awaitMessageCount(1)
        val serverWs = harness.awaitSocket()
        serverWs.send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        val events = mutableListOf<LiveSessionEvent>()
        val collectJob = launch { session.events.toList(events) }

        session.sendActivityEnd()
        delay(100)
        serverWs.send("""{"serverContent":{"turnComplete":true}}""")

        withTimeout(5_000) {
            while (events.none { it is LiveSessionEvent.TurnComplete }) delay(10)
        }
        delay(600) // outlast the 300ms watchdog window; it must not fire post-disarm
        assertTrue(events.none { it is LiveSessionEvent.Error })

        collectJob.cancel()
        session.close()
    }

    @Test
    fun `GeminiVad response watchdog arms on the first response frame after our audio`() = runBlocking {
        val harness = ServerHarness()
        val session = newSession(harness, vadMode = VadMode.GEMINI_VAD, watchdogTimeoutMs = 300L, socketDeadTimeoutMs = 60_000L)
        val openJob = launch { session.open("p", profile) }
        harness.awaitMessageCount(1)
        val serverWs = harness.awaitSocket()
        serverWs.send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        val events = mutableListOf<LiveSessionEvent>()
        val collectJob = launch { session.events.toList(events) }

        session.sendAudio(ShortArray(160))
        harness.awaitMessageCount(2)
        delay(900) // no response yet: must not trip while GEMINI_VAD awaits the server's own VAD
        assertTrue(events.none { it is LiveSessionEvent.Error })

        val audioB64 = Base64.getEncoder().encodeToString(
            GeminiLiveSession.shortsToLittleEndianBytes(shortArrayOf(1, 2, 3))
        )
        serverWs.send(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$audioB64"}}]}}}"""
        )

        withTimeout(5_000) {
            while (events.none { it is LiveSessionEvent.Error }) delay(10)
        }
        assertTrue(events.any { it is LiveSessionEvent.Error && it.message.contains("outstanding") })

        collectJob.cancel()
    }

    @Test
    fun `dead-socket guard fires when no frames arrive at all`() = runBlocking {
        val harness = ServerHarness()
        // Large response-watchdog window (never outstanding anyway) but a short dead-socket guard.
        val session = newSession(harness, watchdogTimeoutMs = 60_000L, socketDeadTimeoutMs = 300L)
        val openJob = launch { session.open("p", profile) }
        harness.awaitMessageCount(1)
        harness.awaitSocket().send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        val events = mutableListOf<LiveSessionEvent>()
        val collectJob = launch { session.events.toList(events) }

        withTimeout(5_000) {
            while (events.none { it is LiveSessionEvent.Error }) delay(10)
        }
        assertTrue(events.any { it is LiveSessionEvent.Error && it.message.contains("Watchdog") })
        assertTrue(events.any { it is LiveSessionEvent.Closed })

        collectJob.cancel()
    }

    @Test
    fun `setupComplete is emitted as an event, not just used internally`() = runBlocking {
        val harness = ServerHarness()
        server.enqueue(MockResponse().withWebSocketUpgrade(harness.listener))
        val session = GeminiLiveSession(
            authProvider = ApiKeyAuth("test-key"),
            client = client,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            wsUrlBase = wsUrl(),
        )
        val events = mutableListOf<LiveSessionEvent>()
        val collectJob = launch { session.events.toList(events) }

        val openJob = launch { session.open("p", profile) }
        harness.awaitMessageCount(1)
        harness.awaitSocket().send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        withTimeout(5_000) {
            while (events.none { it is LiveSessionEvent.SetupComplete }) delay(10)
        }

        collectJob.cancel()
        session.close()
    }

    @Test
    fun `open() a second time throws IllegalStateException`() = runBlocking {
        val harness = ServerHarness()
        val session = newSession(harness)
        val openJob = launch { session.open("p", profile) }
        harness.awaitMessageCount(1)
        harness.awaitSocket().send("""{"setupComplete":{}}""")
        withTimeout(5_000) { openJob.join() }

        var threw = false
        try {
            session.open("p", profile)
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)

        session.close()
    }
}
