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
 * Live smoke test against the real Gemini Live API. Skipped automatically when no
 * `GEMINI_API_KEY` is present in `local.properties` (e.g. CI without the secret).
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

    /** Returns (modelId, eventSummaries) on success, or null if setup/open failed for this model. */
    private suspend fun tryModel(client: OkHttpClient, apiKey: String, modelId: String): Pair<String, List<String>>? = coroutineScope {
        val session = GeminiLiveSession(
            authProvider = ApiKeyAuth(apiKey),
            modelId = modelId,
            vadMode = VadMode.LOCAL_VAD,
            client = client,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            setupTimeoutMs = 10_000L,
            // The smoke test deliberately sends only silence, which a real session may not
            // respond to at all; use a longer watchdog than the 8s production default so the
            // watchdog itself (already covered by MockWebServer tests) doesn't fire mid-assertion.
            watchdogTimeoutMs = 30_000L,
        )

        val profile = CallerProfile(number = "01700000000", name = "টেস্ট", village = "টেস্ট গ্রাম", occupation = "কৃষক")
        val summaries = mutableListOf<String>()
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

            val hadError = summaries.any { it.startsWith("Error") }
            collectJob.cancel()
            session.close()

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
