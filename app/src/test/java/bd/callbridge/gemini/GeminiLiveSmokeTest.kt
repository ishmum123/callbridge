package bd.callbridge.gemini

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Live smoke test against the real Gemini Live API. Skipped unless BOTH:
 *  - the `liveSmoke` system property is `"true"` (set via `-PliveSmoke=true`, see
 *    `docs/gemini-live.md` for the exact command), so a plain `./gradlew test` never dials out, and
 *  - `GEMINI_API_KEY` is present in `local.properties`.
 *
 * Opens a real session with the pinned model, sends 1s of silence, asserts no [LiveSessionEvent.Error]
 * arrives within 10s of setup completing, then closes cleanly. If the pinned model is rejected at
 * the WebSocket layer, retries once with [bd.callbridge.Config.GEMINI_MODEL_FALLBACK].
 */
class GeminiLiveSmokeTest {

    private fun readApiKey(): String? {
        val f = File("../local.properties").takeIf { it.exists() } ?: File("local.properties")
        if (!f.exists()) return null
        val props = Properties().apply { f.inputStream().use { load(it) } }
        return props.getProperty("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
    }

    @Test
    fun `real session opens, acks setup, and accepts silence without error`() = runBlocking {
        assumeTrue(
            "liveSmoke system property not set to true; skipping (see docs/gemini-live.md for the -PliveSmoke=true command)",
            System.getProperty("liveSmoke") == "true",
        )
        val apiKey = readApiKey()
        assumeTrue("GEMINI_API_KEY not present in local.properties; skipping live smoke test", apiKey != null)

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val result = tryModel(client, apiKey!!, bd.callbridge.Config.GEMINI_MODEL_ID)
            ?: tryModel(client, apiKey, bd.callbridge.Config.GEMINI_MODEL_FALLBACK)
                ?.also { println("PRIMARY MODEL REJECTED; FALLBACK WORKED: ${bd.callbridge.Config.GEMINI_MODEL_FALLBACK}") }

        checkNotNull(result) { "Both primary and fallback models failed to open a Live API session" }
        println("LIVE SMOKE TEST: model=${result.first} events=${result.second}")
    }

    /**
     * B2 verification (M3 review): the greeting kick sends a text `clientContent` turn instead
     * of an empty activityStart/activityEnd pair (which armed the 8s response watchdog on every
     * call). Opens a real session, sends the same Bangla greeting-trigger text BridgeSession
     * sends, and asserts at least one [LiveSessionEvent.AudioOut] and a
     * [LiveSessionEvent.TurnComplete] arrive within ~8s — i.e. the model actually answers a text
     * turn in an audio-output session, and doing so doesn't trip the watchdog.
     */
    @Test
    fun `real session accepts a text turn (greeting kick) and replies with audio within 8s`() = runBlocking {
        assumeTrue(
            "liveSmoke system property not set to true; skipping (see docs/gemini-live.md for the -PliveSmoke=true command)",
            System.getProperty("liveSmoke") == "true",
        )
        val apiKey = readApiKey()
        assumeTrue("GEMINI_API_KEY not present in local.properties; skipping live smoke test", apiKey != null)

        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val session = GeminiLiveSession(
            authProvider = ApiKeyAuth(apiKey!!),
            modelId = bd.callbridge.Config.GEMINI_MODEL_ID,
            vadMode = VadMode.LOCAL_VAD,
            client = client,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            setupTimeoutMs = 10_000L,
            // Production default (8s): this is exactly the value B2 needs verified against —
            // sendTextTurn must not arm this and the model's reply must land well inside it.
        )
        val profile = CallerProfile(number = "01700000000", name = null, village = null, occupation = null)
        val events = mutableListOf<LiveSessionEvent>()
        val collectJob = launch { session.events.collect { events.add(it) } }

        try {
            session.open("তুমি একটি সহায়ক ফোন সেবা। কেউ কল করলে সংক্ষেপে বাংলায় নিজের পরিচয় দাও।", profile)
            session.sendTextTurn("কল শুরু হয়েছে, নিজের পরিচয় দাও।")

            val gotAudioAndTurnComplete = withTimeoutOrNull(8_000L) {
                while (events.none { it is LiveSessionEvent.AudioOut } || events.none { it is LiveSessionEvent.TurnComplete }) {
                    delay(50)
                }
                true
            }

            val summary = events.map { it.javaClass.simpleName }
            println("GREETING SMOKE TEST: withinBudget=$gotAudioAndTurnComplete events=$summary")

            check(gotAudioAndTurnComplete == true) {
                "Expected AudioOut + TurnComplete within 8s of sending the greeting text turn; got $summary"
            }
            check(events.none { it is LiveSessionEvent.Error }) {
                "Expected no Error event; got $summary"
            }
        } finally {
            collectJob.cancel()
            runCatching { session.close() }
        }
    }

    /** Returns (modelId, eventSummaries) on success, or null if setup/open failed for this model. */
    private suspend fun tryModel(client: OkHttpClient, apiKey: String, modelId: String): Pair<String, List<String>>? = coroutineScope {
        val session = GeminiLiveSession(
            authProvider = ApiKeyAuth(apiKey),
            modelId = modelId,
            vadMode = VadMode.LOCAL_VAD,
            client = client,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            setupTimeoutMs = 10_000L,
            // Production default (8s). Sending only silence with LOCAL_VAD and no
            // sendActivityStart/End calls never arms the response watchdog (it's only outstanding
            // between sendActivityEnd and turnComplete/interrupted) — the old 30s override here
            // was a workaround for a since-fixed bug where the watchdog ran unconditionally.
        )

        val profile = CallerProfile(number = "01700000000", name = "টেস্ট", village = "টেস্ট গ্রাম", occupation = "কৃষক")
        val summaries = mutableListOf<String>()
        // Subscribe before open(), per LiveSession.open's documented contract.
        val collectJob = launch {
            session.events.collect { ev ->
                summaries.add(ev.javaClass.simpleName + (if (ev is LiveSessionEvent.Error) ": ${ev.message}" else ""))
            }
        }

        try {
            session.open("তুমি একটি পরীক্ষামূলক সহায়ক। শুধু 'ঠিক আছে' বলো।", profile)

            // 1 second of 16 kHz silence, sent as ~100ms chunks.
            val chunk = ShortArray(1_600)
            repeat(10) { session.sendAudio(chunk) }

            withTimeoutOrNull(10_000L) { delay(10_000L) }

            session.close()
            // Give the Closed event a moment to land before we stop collecting, so the printed
            // summary reflects the full setupComplete -> ... -> Closed sequence.
            withTimeoutOrNull(2_000L) { while (summaries.none { it.startsWith("Closed") }) delay(20) }
            collectJob.cancel()

            val hadError = summaries.any { it.startsWith("Error") }
            if (hadError) {
                println("Model $modelId opened but reported an error: $summaries")
                null
            } else {
                modelId to summaries.toList()
            }
        } catch (e: Exception) {
            collectJob.cancel()
            runCatching { session.close() }
            println("Model $modelId failed to open: ${e.message}")
            null
        }
    }
}
