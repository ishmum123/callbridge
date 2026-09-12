package bd.callbridge.knowledge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
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
 * [HealthKnowledge] fallback backed by OpenAI chat completions. Model verified live 2026-09-12
 * against `GET /v1/models` on this project's key: `gpt-4o-mini` is present and, unlike the newer
 * `gpt-5-mini`/`gpt-5-nano` (also present), returns an answer with no hidden reasoning-token
 * overhead — a live timing check returned a full answer in ~2.2s vs. `gpt-5-mini` burning ~1600
 * reasoning tokens on the same prompt, which risks the 6s [timeoutMs] budget for no accuracy
 * benefit on a bounded factual-lookup task. See worker report / `docs/STATUS.md`.
 *
 * Never throws: any failure (missing key, HTTP error, timeout, malformed JSON, empty content)
 * returns [noInformationAvailable].
 */
class OpenAiKnowledge(
    private val apiKey: String,
    private val model: String = "gpt-4o-mini",
    private val client: OkHttpClient = defaultClient,
    private val timeoutMs: Long = 6_000L,
    /** Overridable for tests (point at a local MockWebServer); defaults to the real OpenAI endpoint. */
    private val chatCompletionsUrl: String = "https://api.openai.com/v1/chat/completions",
) : HealthKnowledge {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lookup(question: String, callerContext: String?): KnowledgeAnswer {
        if (apiKey.isBlank()) return noInformationAvailable()

        return try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) { doLookup(question, callerContext) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OpenAI lookup failed: ${e.message}")
            noInformationAvailable()
        }
    }

    private fun doLookup(question: String, callerContext: String?): KnowledgeAnswer {
        val userContent = if (callerContext.isNullOrBlank()) question else "$question\n\nCaller context: $callerContext"
        val reqBody = ChatCompletionRequest(
            model = model,
            maxTokens = 220,
            messages = listOf(
                ChatMessage(role = "system", content = SYSTEM_PROMPT),
                ChatMessage(role = "user", content = userContent),
            ),
        )
        val body = json.encodeToString(reqBody).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(chatCompletionsUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("OpenAI HTTP ${response.code}: ${response.message}")
            }
            val text = response.body?.string() ?: throw IOException("OpenAI: empty response body")
            val parsed = json.decodeFromString<ChatCompletionResponse>(text)
            val content = parsed.choices.firstOrNull()?.message?.content?.trim()
            if (content.isNullOrBlank()) return noInformationAvailable()

            return KnowledgeAnswer(
                answerEn = content,
                sources = listOf("OpenAI $model (general medical knowledge, not a live citation)"),
                provider = "openai",
            )
        }
    }

    companion object {
        private const val TAG = "OpenAiKnowledge"

        const val SYSTEM_PROMPT =
            "You are a clinical-guidance assistant for a rural Bangladesh telephone health " +
                "helpline. Give concise, evidence-based guidance in plain English, at most 120 " +
                "words. Always explicitly flag any red-flag symptoms that require in-person care " +
                "at the nearest clinic/health complex. Never give a definitive diagnosis. If the " +
                "question is not a health question, say briefly that you can only help with " +
                "health questions."

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
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
internal data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
internal data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
internal data class ChatChoice(
    val message: ChatMessage? = null,
)

