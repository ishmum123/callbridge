package bd.callbridge.knowledge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [HealthKnowledge] backed by Exa's neural search (`POST https://api.exa.ai/search`), restricted
 * to a reputable-health-domain allowlist. Verified live 2026-09-12 (see `docs/STATUS.md` /
 * worker report): request shape confirmed against a real call — `x-api-key` header, `contents:
 * {highlights: {...}}`, response has `results[].{url,title,highlights[]}`.
 *
 * Never throws: any failure (missing key, HTTP error, timeout, malformed JSON, zero results)
 * returns [noInformationAvailable].
 */
class ExaKnowledge(
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient,
    private val timeoutMs: Long = 6_000L,
    /** Overridable for tests (point at a local MockWebServer); defaults to the real Exa endpoint. */
    private val searchUrl: String = "https://api.exa.ai/search",
) : HealthKnowledge {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lookup(question: String, callerContext: String?): KnowledgeAnswer {
        if (apiKey.isBlank()) return noInformationAvailable()

        return try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) { doLookup(question, callerContext) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exa lookup failed: ${e.message}")
            noInformationAvailable()
        }
    }

    private fun doLookup(question: String, callerContext: String?): KnowledgeAnswer {
        val query = if (callerContext.isNullOrBlank()) question else "$question ($callerContext)"
        val reqBody = ExaSearchRequest(
            query = query,
            numResults = 5,
            type = "auto",
            includeDomains = HEALTH_DOMAINS,
            contents = ExaContents(highlights = ExaHighlightsOpts(maxCharacters = 600)),
        )
        val body = json.encodeToString(reqBody).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(searchUrl)
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Exa HTTP ${response.code}: ${response.message}")
            }
            val text = response.body?.string() ?: throw IOException("Exa: empty response body")
            val parsed = json.decodeFromString<ExaSearchResponse>(text)
            val results = parsed.results.filter { it.highlights.isNotEmpty() || !it.text.isNullOrBlank() }
            if (results.isEmpty()) return noInformationAvailable()

            val top = results.take(3)
            val answer = top.joinToString("\n") { r ->
                val snippet = r.highlights.firstOrNull() ?: r.text?.take(400).orEmpty()
                "${r.title.orEmpty()}: $snippet".trim(':', ' ')
            }
            return KnowledgeAnswer(
                answerEn = answer,
                sources = top.map { it.url },
                provider = "exa",
            )
        }
    }

    companion object {
        private const val TAG = "ExaKnowledge"

        /** Reputable health domains, per the brief (spec: restrict to these where the API allows). */
        val HEALTH_DOMAINS = listOf(
            "who.int",
            "nhs.uk",
            "mayoclinic.org",
            "medlineplus.gov",
            "icddrb.org",
            "dghs.gov.bd",
        )

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .writeTimeout(6, TimeUnit.SECONDS)
                .build()
        }
    }
}

@Serializable
internal data class ExaSearchRequest(
    val query: String,
    val numResults: Int,
    val type: String,
    val includeDomains: List<String>,
    val contents: ExaContents,
)

@Serializable
internal data class ExaContents(
    val highlights: ExaHighlightsOpts? = null,
)

@Serializable
internal data class ExaHighlightsOpts(
    val maxCharacters: Int? = null,
)

@Serializable
internal data class ExaSearchResponse(
    val results: List<ExaResult> = emptyList(),
)

@Serializable
internal data class ExaResult(
    val title: String? = null,
    val url: String,
    val text: String? = null,
    val highlights: List<String> = emptyList(),
)
