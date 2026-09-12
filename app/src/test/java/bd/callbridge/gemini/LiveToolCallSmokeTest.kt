package bd.callbridge.gemini

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Live empirical verification of the Gemini Live API tool-calling wire protocol (see
 * `docs/gemini-tools.md` for the full write-up of what this confirmed). Opens a *raw* OkHttp
 * WebSocket directly (not via [GeminiLiveSession], which does not implement tool-calling yet —
 * that's the next worker's job per `docs/gemini-tools.md`'s wiring notes), so this test is a
 * standalone empirical probe, independent of any future GeminiLiveSession changes.
 *
 * Skipped unless BOTH `-PliveSmoke=true` and `GEMINI_API_KEY` is present in `local.properties`
 * (same gating convention as [GeminiLiveSmokeTest]).
 *
 * Flow: setup with one `lookup_health_info(question: string)` function declared -> setupComplete
 * -> send a text turn asking a health question -> assert a `toolCall` naming
 * `lookup_health_info` arrives -> reply with a `toolResponse` -> assert audio output frames and a
 * final `turnComplete` follow.
 */
class LiveToolCallSmokeTest {

    private fun readApiKey(): String? {
        val f = File("../local.properties").takeIf { it.exists() } ?: File("local.properties")
        if (!f.exists()) return null
        val props = Properties().apply { f.inputStream().use { load(it) } }
        return props.getProperty("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
    }

    @Test
    fun `tool call round trip - toolCall then toolResponse then audio and turnComplete`() = runBlocking {
        assumeTrue(
            "liveSmoke system property not set to true; skipping (see docs/gemini-live.md for the -PliveSmoke=true command)",
            System.getProperty("liveSmoke") == "true",
        )
        val apiKey = readApiKey()
        assumeTrue("GEMINI_API_KEY not present in local.properties; skipping live tool-call smoke test", apiKey != null)

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        val incoming = Channel<JsonObject>(capacity = Channel.UNLIMITED)
        val json = Json { ignoreUnknownKeys = true }
        var socket: WebSocket? = null

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { incoming.trySend(json.parseToJsonElement(text).jsonObject) }
            }

            // Confirmed live 2026-09-12: the server sends every message (setupComplete,
            // toolCall, serverContent) as a BINARY frame, never text — same gotcha documented in
            // docs/gemini-live.md for the plain audio path. Required, not defensive.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { incoming.trySend(json.parseToJsonElement(bytes.utf8()).jsonObject) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.close(t)
            }
        }

        socket = client.newWebSocket(request, listener)

        val setupMessage = buildJsonObject {
            putJsonObject("setup") {
                put("model", "models/${bd.callbridge.Config.GEMINI_MODEL_ID}")
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add("AUDIO") }
                }
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put(
                                "text",
                                "You MUST call the lookup_health_info function for every medical " +
                                    "question before answering, even if you already know the answer. " +
                                    "Never answer a medical question without first calling it.",
                            )
                        })
                    }
                }
                putJsonArray("tools") {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            add(buildJsonObject {
                                put("name", "lookup_health_info")
                                put("description", "Looks up evidence-based health information to answer the caller's medical question.")
                                putJsonObject("parameters") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("question") {
                                            put("type", "string")
                                            put("description", "The caller's health question, restated in English.")
                                        }
                                    }
                                    putJsonArray("required") { add("question") }
                                }
                            })
                        }
                    })
                }
            }
        }
        println("TOOL SMOKE TEST setup message: $setupMessage")
        socket.send(setupMessage.toString())

        // 1) setupComplete
        val setupAck = withTimeoutOrNull(10_000L) {
            var msg: JsonObject
            do { msg = incoming.receive() } while (msg["setupComplete"] == null && msg["toolCall"] == null)
            msg
        }
        assertTrue("Did not receive setupComplete within 10s", setupAck != null)
        println("TOOL SMOKE TEST setupComplete: $setupAck")

        // 2) send a text turn that should trigger the tool call
        val turnMessage = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", "What is the recommended paracetamol dose for a 2 year old?") })
                        }
                    })
                }
                put("turnComplete", true)
            }
        }
        socket.send(turnMessage.toString())

        // 3) collect until we see a toolCall (may be interleaved with serverContent chatter)
        var toolCallMsg: JsonObject? = null
        val deadlineMs = System.currentTimeMillis() + 20_000L
        while (toolCallMsg == null && System.currentTimeMillis() < deadlineMs) {
            val msg = withTimeoutOrNull(20_000L) { incoming.receive() } ?: break
            if (msg.containsKey("toolCall")) {
                toolCallMsg = msg
            }
        }
        assertTrue("Did not receive a toolCall message within 20s", toolCallMsg != null)
        println("TOOL SMOKE TEST toolCall: $toolCallMsg")

        val functionCalls = toolCallMsg!!["toolCall"]!!.jsonObject["functionCalls"]!!.jsonArray
        assertTrue("toolCall.functionCalls was empty", functionCalls.isNotEmpty())
        val call = functionCalls[0].jsonObject
        val callId = call["id"]!!.jsonPrimitive.contentOrNull
        val callName = call["name"]!!.jsonPrimitive.contentOrNull
        assertTrue("expected functionCalls[0].name == lookup_health_info, got $callName", callName == "lookup_health_info")
        assertTrue("functionCalls[0].id was null/blank", !callId.isNullOrBlank())

        // 4) reply with a toolResponse
        val toolResponseMessage = buildJsonObject {
            putJsonObject("toolResponse") {
                putJsonArray("functionResponses") {
                    add(buildJsonObject {
                        put("id", callId)
                        put("name", callName)
                        putJsonObject("response") {
                            put(
                                "result",
                                "Paracetamol for a 2 year old: 10-15 mg/kg per dose, every 4-6 hours, max 4 doses/24h. " +
                                    "See a doctor urgently if breathing difficulty, persistent high fever, or lethargy.",
                            )
                        }
                    })
                }
            }
        }
        println("TOOL SMOKE TEST toolResponse: $toolResponseMessage")
        socket.send(toolResponseMessage.toString())

        // 5) expect audio output + turnComplete to follow
        var sawAudio = false
        var sawTurnComplete = false
        val deadline2Ms = System.currentTimeMillis() + 20_000L
        while (!sawTurnComplete && System.currentTimeMillis() < deadline2Ms) {
            val msg = withTimeoutOrNull(20_000L) { incoming.receive() } ?: break
            val sc = msg["serverContent"]?.jsonObject ?: continue
            val parts = sc["modelTurn"]?.jsonObject?.get("parts")?.jsonArray
            if (parts != null && parts.any { it.jsonObject["inlineData"] != null }) sawAudio = true
            if (sc["turnComplete"]?.jsonPrimitive?.contentOrNull == "true" || sc["turnComplete"] != null) sawTurnComplete = true
        }
        println("TOOL SMOKE TEST after toolResponse: sawAudio=$sawAudio sawTurnComplete=$sawTurnComplete")
        assertTrue("Expected audio output frames after toolResponse", sawAudio)
        assertTrue("Expected turnComplete after toolResponse", sawTurnComplete)

        socket.close(1000, "test done")
        Unit
    }
}
