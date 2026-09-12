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
    private val client: OkHttpClient = OkHttpClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun onCallFinished(callId: Long) {
        withContext(Dispatchers.IO) {
            try {
                run(callId)
            } catch (e: Exception) {
                // Never throw to the caller (docs above) — this is a best-effort demo enrichment,
                // not something that may ever take down a live call.
                Log.e(TAG, "onCallFinished($callId) failed unexpectedly", e)
            }
        }
    }

    private suspend fun run(callId: Long) {
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

        val existing = profileDao.find(call.number)
        val requestJson = buildRequestBody(existing, turns)

        val result = runCatching { callGenerateContent(requestJson) }
            .onFailure { Log.e(TAG, "onCallFinished($callId): generateContent call failed", it) }
            .getOrNull()

        if (result == null) {
            recordError(call.number, existing, "generateContent call failed (see logcat)")
            return
        }

        val parsed = runCatching { parseGeneratedUpdate(result) }
            .onFailure { Log.e(TAG, "onCallFinished($callId): failed to parse model output: $result", it) }
            .getOrNull()

        if (parsed == null) {
            recordError(call.number, existing, "could not parse model JSON output")
            return
        }

        val merged = PatientProfileEntity(
            number = call.number,
            displayName = parsed.displayName ?: existing?.displayName,
            ageYears = parsed.ageYears ?: existing?.ageYears,
            sex = parsed.sex ?: existing?.sex,
            village = parsed.village ?: existing?.village,
            chronicConditions = parsed.chronicConditions,
            currentSymptoms = parsed.currentSymptoms,
            medications = parsed.medications,
            allergies = parsed.allergies,
            riskFlags = parsed.riskFlags,
            adviceGiven = parsed.adviceGiven,
            followUpNeeded = parsed.followUpNeeded,
            followUpNote = parsed.followUpNote,
            summaryBn = parsed.summaryBn,
            summaryEn = parsed.summaryEn,
            lastUpdated = nowMs(),
            callCount = (existing?.callCount ?: 0) + 1,
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
        Log.i(TAG, "onCallFinished($callId): profile updated for ${call.number}, callCount=${merged.callCount}")
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
                error("generateContent HTTP ${response.code}: $bodyStr")
            }
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val text = root["candidates"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
            return text ?: error("no candidates[0].content.parts[0].text in response: $bodyStr")
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
