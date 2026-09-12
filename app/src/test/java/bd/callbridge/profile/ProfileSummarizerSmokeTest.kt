package bd.callbridge.profile

import bd.callbridge.Config
import bd.callbridge.store.CallDirection
import bd.callbridge.store.CallEntity
import bd.callbridge.store.PatientProfileEntity
import bd.callbridge.store.ProfileUpdateEntity
import bd.callbridge.store.TurnEntity
import bd.callbridge.store.TurnRole
import bd.callbridge.store.dao.CallDao
import bd.callbridge.store.dao.PatientProfileDao
import bd.callbridge.store.dao.ProfileUpdateDao
import bd.callbridge.store.dao.TurnDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * Live smoke test against the real Gemini REST `generateContent` endpoint (not the Live API —
 * see [ProfileSummarizer]'s doc comment). Verifies [Config.GEMINI_SUMMARY_MODEL_ID] actually
 * accepts a `responseSchema` request and returns parseable JSON with this key, the same way
 * `GeminiLiveSmokeTest` verifies the Live model ids.
 *
 * Skipped unless BOTH the `liveSmoke` system property is `"true"` (see
 * `./gradlew testDebugUnitTest -PliveSmoke=true --tests "*ProfileSummarizerSmokeTest*"`) and
 * `GEMINI_API_KEY` is present in `local.properties`.
 */
class ProfileSummarizerSmokeTest {

    private fun readApiKey(): String? {
        val f = File("../local.properties").takeIf { it.exists() } ?: File("local.properties")
        if (!f.exists()) return null
        val props = Properties().apply { f.inputStream().use { load(it) } }
        return props.getProperty("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
    }

    private class FakeCallDao(private val call: CallEntity) : CallDao {
        var updated: CallEntity? = null
        override suspend fun insert(call: CallEntity): Long = throw NotImplementedError()
        override suspend fun update(call: CallEntity) { updated = call }
        override suspend fun findById(id: Long): CallEntity? = call.takeIf { it.id == id }
        override fun observeCallsSince(sinceEpochMs: Long): Flow<Int> = flowOf(0)
        override fun observeCostSince(sinceEpochMs: Long): Flow<Double> = flowOf(0.0)
        override suspend fun findLatest(): CallEntity? = call
    }

    private class FakeTurnDao(private val turns: List<TurnEntity>) : TurnDao {
        override suspend fun insert(turn: TurnEntity): Long = throw NotImplementedError()
        override suspend fun forCall(callId: Long): List<TurnEntity> = turns
    }

    private class FakePatientProfileDao : PatientProfileDao {
        var stored: PatientProfileEntity? = null
        override suspend fun upsert(profile: PatientProfileEntity) { stored = profile }
        override suspend fun find(number: String): PatientProfileEntity? = stored
        override fun observeAll(): Flow<List<PatientProfileEntity>> = flowOf(listOfNotNull(stored))
        override fun observe(number: String): Flow<PatientProfileEntity?> = flowOf(stored)
    }

    private class FakeProfileUpdateDao : ProfileUpdateDao {
        var inserted: ProfileUpdateEntity? = null
        override suspend fun insert(update: ProfileUpdateEntity): Long { inserted = update; return 1L }
        override fun observeForNumber(number: String): Flow<List<ProfileUpdateEntity>> = flowOf(listOfNotNull(inserted))
        override suspend fun forNumber(number: String): List<ProfileUpdateEntity> = listOfNotNull(inserted)
        override suspend fun existsForCall(callId: Long): Boolean = inserted?.callId == callId
        override suspend fun countForNumber(number: String): Int = if (inserted?.number == number) 1 else 0
        override suspend fun deleteForCall(callId: Long) { if (inserted?.callId == callId) inserted = null }
    }

    @Test
    fun `real generateContent call returns a parseable structured profile`() = runBlocking {
        assumeTrue(
            "liveSmoke system property not set to true; skipping",
            System.getProperty("liveSmoke") == "true",
        )
        val apiKey = readApiKey()
        assumeTrue("GEMINI_API_KEY not present in local.properties; skipping", apiKey != null)

        val call = CallEntity(id = 1L, number = "01700000000", direction = CallDirection.INBOUND, startedAt = 0L)
        val turns = listOf(
            TurnEntity(callId = 1L, role = TurnRole.CALLER, text = "আমার তিন দিন ধরে জ্বর, কাশি হচ্ছে।", tMs = 0L),
            TurnEntity(callId = 1L, role = TurnRole.ASSISTANT, text = "প্যারাসিটামল খান, পানি পান করুন। শ্বাসকষ্ট হলে ডাক্তার দেখান।", tMs = 3000L),
        )
        val profileDao = FakePatientProfileDao()
        val updateDao = FakeProfileUpdateDao()

        val summarizer = ProfileSummarizer(
            turnDao = FakeTurnDao(turns),
            callDao = FakeCallDao(call),
            profileDao = profileDao,
            profileUpdateDao = updateDao,
            apiKey = { apiKey!! },
            modelId = Config.GEMINI_SUMMARY_MODEL_ID,
        )

        summarizer.onCallFinished(1L)

        val profile = profileDao.stored
        println("LIVE SMOKE TEST (ProfileSummarizer): profile=$profile update=${updateDao.inserted}")
        assertNull("expected no lastError on a successful real call", profile?.lastError)
        assertTrue("expected a non-blank English summary from the real model", profile?.summaryEn?.isNotBlank() == true)
        assertTrue("expected a non-blank delta summary from the real model", updateDao.inserted?.deltaSummary?.isNotBlank() == true)
    }
}
