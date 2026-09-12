package bd.callbridge.profile

import android.util.Log
import bd.callbridge.Config
import bd.callbridge.store.PatientProfileEntity
import bd.callbridge.store.ProfileUpdateEntity
import bd.callbridge.store.TurnEntity
import bd.callbridge.store.TurnRole
import bd.callbridge.store.dao.CallDao
import bd.callbridge.store.dao.PatientProfileDao
import bd.callbridge.store.dao.ProfileUpdateDao
import bd.callbridge.store.dao.TurnDao
import bd.callbridge.util.Redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Demo "Patient profile" feature (not in the original spec). After a call finishes, merges that
 * call's transcript into the caller's running clinical profile via a single Gemini REST
 * `generateContent` call (plain request/response JSON, *not* the Live API/WebSocket that
 * [bd.callbridge.gemini.GeminiLiveSession] uses).
 *
 * Wiring (left to whoever owns `CallController`/`BridgeSession` — not touched by this worker):
 * call `app.profileSummarizer.onCallFinished(callId)` right after the call's
 * `TranscriptRecorder.finish(endReason)` has completed (fire-and-forget from a non-blocking
 * scope is fine — this function is confined to [Dispatchers.IO], catches everything, and never
 * throws to the caller).
 *
 * Failure handling: an empty transcript is skipped entirely (no profile touched, no update row).
 * Any other failure (network, non-2xx, unparsable JSON) is logged and recorded as
 * [PatientProfileEntity.lastError] on the existing profile (creating a bare error-only profile
 * row if none existed yet) — the rest of the profile's fields are left exactly as they were.
 */
class ProfileSummarizer(
    private val turnDao: TurnDao,
    private val callDao: CallDao,
    private val profileDao: PatientProfileDao,
    private val profileUpdateDao: ProfileUpdateDao,
    private val apiKey: () -> String,
    private val modelId: String = Config.GEMINI_SUMMARY_MODEL_ID,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * @param force Bypasses the idempotency check (a [ProfileUpdateEntity] row already existing
     * for [callId]) so the debug `DEBUG_SUMMARIZE` broadcast can re-run one call on demand without
     * inflating [PatientProfileEntity.callCount] on the normal path (see class doc + n7 fix).
     */
    suspend fun onCallFinished(callId: Long, force: Boolean = false) {
        withContext(Dispatchers.IO) {
            try {
                run(callId, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never throw to the caller (docs above) — this is a best-effort demo enrichment,
                // not something that may ever take down a live call.
                Log.e(TAG, "onCallFinished($callId) failed unexpectedly", e)
            }
        }
    }

    private suspend fun run(callId: Long, force: Boolean) {
        val call = callDao.findById(callId)
        if (call == null) {
            Log.w(TAG, "onCallFinished($callId): no such call, skipping")
            return
        }
        val turns = turnDao.forCall(callId)
        if (turns.isEmpty()) {
            Log.i(TAG, "onCallFinished($callId): empty transcript, skipping")
            return
        }
        if (!force && profileUpdateDao.existsForCall(callId)) {
            Log.i(TAG, "onCallFinished($callId): already summarized (profile_updates row exists), skipping")
            return
        }

        val existing = profileDao.find(call.number)
        val requestJson = buildRequestBody(existing, turns)

        // Retry: the call ends right as the phone's default network flips (Wi-Fi ↔ LTE), and the
        // first attempt often times out on slow venue networks (observed 2026-09-12).
        var callResult: Result<String> = Result.failure(IllegalStateException("not attempted"))
        for ((attempt, backoffMs) in listOf(0L, 3_000L, 8_000L).withIndex()) {
            if (backoffMs > 0) kotlinx.coroutines.delay(backoffMs)
            callResult = runCatching { callGenerateContent(requestJson) }
            if (callResult.isSuccess) break
            Log.w(TAG, "onCallFinished($callId): generateContent attempt ${attempt + 1} failed: ${callResult.exceptionOrNull()?.message?.take(LOG_PREVIEW_CHARS)}")
        }
        callResult.onFailure {
            Log.e(TAG, "onCallFinished($callId): generateContent call failed: ${it.message?.take(LOG_PREVIEW_CHARS)}")
        }
        val result = callResult.getOrNull()

        if (result == null) {
            val reason = callResult.exceptionOrNull()?.message?.take(LOG_PREVIEW_CHARS) ?: "unknown"
            recordError(call.number, existing, "generateContent call failed: $reason")
            return
        }

        val parsed = runCatching { parseGeneratedUpdate(result) }
            .onFailure {
                Log.e(
                    TAG,
                    "onCallFinished($callId): failed to parse model output " +
                        "(len=${result.length}, preview=${result.take(LOG_PREVIEW_CHARS)})",
                    it,
                )
            }
            .getOrNull()

        if (parsed == null) {
            recordError(call.number, existing, "could not parse model JSON output")
            return
        }

        // Replace (not duplicate) this call's prior update row before counting/inserting —
        // relevant only on the force path (a no-op otherwise, since the idempotency check above
        // already ruled out an existing row for this callId). Doing this before countForNumber
        // below is what keeps callCount at "distinct calls processed" rather than growing every
        // time the same call is force-re-summarized.
        profileUpdateDao.deleteForCall(callId)

        val merged = PatientProfileEntity(
            number = call.number,
            displayName = parsed.displayName ?: existing?.displayName,
            ageYears = parsed.ageYears ?: existing?.ageYears,
            sex = parsed.sex ?: existing?.sex,
            village = parsed.village ?: existing?.village,
            // Accumulate semantics enforced in code (not left to the model): union of existing +
            // model output, case-insensitive dedupe, order preserved. currentSymptoms and the
            // follow-up/summary fields stay model-authoritative (latest assessment wins).
            chronicConditions = unionCaseInsensitive(existing?.chronicConditions, parsed.chronicConditions),
            currentSymptoms = parsed.currentSymptoms,
            medications = unionCaseInsensitive(existing?.medications, parsed.medications),
            allergies = unionCaseInsensitive(existing?.allergies, parsed.allergies),
            riskFlags = unionCaseInsensitive(existing?.riskFlags, parsed.riskFlags),
            adviceGiven = unionCaseInsensitive(existing?.adviceGiven, parsed.adviceGiven),
            followUpNeeded = parsed.followUpNeeded,
            followUpNote = parsed.followUpNote,
            summaryBn = parsed.summaryBn,
            summaryEn = parsed.summaryEn,
            lastUpdated = nowMs(),
            // Derived from profile_updates rows for this number, not incremented per run, so a
            // debug force-rerun of one call never double counts (n7 fix).
            callCount = profileUpdateDao.countForNumber(call.number) + 1,
            lastError = null,
        )
        profileDao.upsert(merged)
        profileUpdateDao.insert(
            ProfileUpdateEntity(
                number = call.number,
                callId = callId,
                timestamp = nowMs(),
                deltaSummary = parsed.deltaSummary,
            )
        )
        Log.i(TAG, "onCallFinished($callId): profile updated for ${Redact.phone(call.number)}, callCount=${merged.callCount}")
    }

    /** Case-insensitive union of [existing] + [incoming], preserving first-seen order, used for
     *  the list fields that must only ever accumulate (never shrink) across calls. */
    private fun unionCaseInsensitive(existing: List<String>?, incoming: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<String>()
        for (item in (existing ?: emptyList()) + incoming) {
            val trimmed = item.trim()
            if (trimmed.isEmpty()) continue
            if (seen.add(trimmed.lowercase())) out.add(trimmed)
        }
        return out
    }

    private suspend fun recordError(number: String, existing: PatientProfileEntity?, message: String) {
        val base = existing ?: PatientProfileEntity(number = number, lastUpdated = nowMs())
        profileDao.upsert(base.copy(lastError = message))
    }

    // --- Gemini REST call -----------------------------------------------------------------

    private fun callGenerateContent(requestBody: JsonObject): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey())
            .post(json.encodeToString(JsonObject.serializer(), requestBody).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Never surface the full HTTP body (may contain echoed request content / PII) —
                // length + a short preview is enough to diagnose from logcat.
                error(
                    "generateContent HTTP ${response.code}: len=${bodyStr.length} " +
                        "preview=${bodyStr.take(LOG_PREVIEW_CHARS)}"
                )
            }
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject

            val finishReason = candidate?.get("finishReason")?.jsonPrimitive?.content
            Log.i(TAG, "callGenerateContent: finishReason=$finishReason")
            if (finishReason == "MAX_TOKENS") {
                error("generateContent finishReason=MAX_TOKENS (output truncated)")
            }

            // Concatenate every part's text (not just parts[0]) — the model can split its JSON
            // output across multiple parts.
            val text = candidate
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                ?.joinToString("")
                ?.takeIf { it.isNotEmpty() }
            return text ?: error(
                "no candidates[0].content.parts[*].text in response: len=${bodyStr.length} " +
                    "preview=${bodyStr.take(LOG_PREVIEW_CHARS)}"
            )
        }
    }

    private fun buildRequestBody(existing: PatientProfileEntity?, turns: List<TurnEntity>): JsonObject {
        val transcriptText = turns.joinToString("\n") { t ->
            val speaker = if (t.role == TurnRole.CALLER) "Caller" else "Assistant"
            "$speaker: ${t.text}"
        }
        val existingJson = existing?.let {
            buildJsonObject {
                put("displayName", it.displayName)
                put("ageYears", it.ageYears)
                put("sex", it.sex)
                put("village", it.village)
                put("chronicConditions", it.chronicConditions.toJsonArray())
                put("currentSymptoms", it.currentSymptoms.toJsonArray())
                put("medications", it.medications.toJsonArray())
                put("allergies", it.allergies.toJsonArray())
                put("riskFlags", it.riskFlags.toJsonArray())
                put("adviceGiven", it.adviceGiven.toJsonArray())
                put("followUpNeeded", it.followUpNeeded)
                put("followUpNote", it.followUpNote)
                put("summaryBn", it.summaryBn)
                put("summaryEn", it.summaryEn)
            }.toString()
        } ?: "null (no prior profile for this caller)"

        val prompt = """
            You maintain a running clinical profile for a caller of a Bangla-language telehealth
            helpline, built up call-over-call. You are given the EXISTING profile (JSON, or "null"
            if this is the caller's first call) and the TRANSCRIPT of the call that just finished.

            Produce the COMPLETE UPDATED profile as a single JSON object matching the response
            schema exactly. Rules:
            - Keep every existing fact unless the new transcript clearly contradicts or updates it
              (e.g. a resolved symptom, a corrected age). Never silently drop prior facts.
              currentSymptoms should reflect symptoms reported as of THIS call (resolved ones
              should drop off; still-present ones stay).
              chronicConditions/medications/allergies/riskFlags/adviceGiven should accumulate
              (union of prior + new), not shrink, unless something is explicitly retracted.
            - riskFlags are short clinical flags in English, e.g. "pregnant", "chest pain
              reported", "fever >3 days".
            - followUpNeeded/followUpNote reflect the LATEST assessment (this call's call takes
              precedence).
            - summaryBn: 2-3 sentence plain-language clinical summary in Bangla.
            - summaryEn: the same summary in English.
            - deltaSummary: one short sentence (English) describing what specifically changed or
              was learned in THIS call only (for a per-call history view), not the whole profile.
            - If a field is unknown, use null (for scalars) or an empty list (for list fields) —
              never fabricate specifics not present in the transcript or existing profile.

            EXISTING PROFILE:
            $existingJson

            TRANSCRIPT:
            $transcriptText
        """.trimIndent()

        return buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("responseSchema", RESPONSE_SCHEMA)
            })
        }
    }

    private fun List<String>.toJsonArray(): JsonArray = JsonArray(map { JsonPrimitive(it) })

    private fun parseGeneratedUpdate(modelText: String): GeneratedProfileUpdate =
        json.decodeFromString(GeneratedProfileUpdate.serializer(), modelText)

    companion object {
        private const val TAG = "ProfileSummarizer"
        private const val LOG_PREVIEW_CHARS = 80
        private const val CALL_TIMEOUT_SECONDS = 60L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        private fun stringArraySchema() = buildJsonObject {
            put("type", "ARRAY")
            put("items", buildJsonObject { put("type", "STRING") })
        }

        val RESPONSE_SCHEMA: JsonObject = buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("displayName", buildJsonObject { put("type", "STRING"); put("nullable", true) })
                put("ageYears", buildJsonObject { put("type", "INTEGER"); put("nullable", true) })
                put("sex", buildJsonObject { put("type", "STRING"); put("nullable", true) })
                put("village", buildJsonObject { put("type", "STRING"); put("nullable", true) })
                put("chronicConditions", stringArraySchema())
                put("currentSymptoms", stringArraySchema())
                put("medications", stringArraySchema())
                put("allergies", stringArraySchema())
                put("riskFlags", stringArraySchema())
                put("adviceGiven", stringArraySchema())
                put("followUpNeeded", buildJsonObject { put("type", "BOOLEAN") })
                put("followUpNote", buildJsonObject { put("type", "STRING"); put("nullable", true) })
                put("summaryBn", buildJsonObject { put("type", "STRING") })
                put("summaryEn", buildJsonObject { put("type", "STRING") })
                put("deltaSummary", buildJsonObject { put("type", "STRING") })
            })
            put("required", buildJsonArray {
                listOf(
                    "chronicConditions", "currentSymptoms", "medications", "allergies",
                    "riskFlags", "adviceGiven", "followUpNeeded", "summaryBn", "summaryEn",
                    "deltaSummary",
                ).forEach { add(JsonPrimitive(it)) }
            })
        }
    }
}

/** Mirrors [ProfileSummarizer.RESPONSE_SCHEMA] exactly — decoded straight from the model's
 *  `responseMimeType: application/json` output text. */
@Serializable
data class GeneratedProfileUpdate(
    val displayName: String? = null,
    val ageYears: Int? = null,
    val sex: String? = null,
    val village: String? = null,
    val chronicConditions: List<String> = emptyList(),
    val currentSymptoms: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val riskFlags: List<String> = emptyList(),
    val adviceGiven: List<String> = emptyList(),
    val followUpNeeded: Boolean = false,
    val followUpNote: String? = null,
    val summaryBn: String = "",
    val summaryEn: String = "",
    val deltaSummary: String = "",
)
