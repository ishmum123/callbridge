package bd.callbridge.profile

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory fakes, same style as gemini/TranscriptRecorderTest.kt's — pure JVM, no Room. */
private class FakeCallDao(private val calls: MutableMap<Long, CallEntity>) : CallDao {
    override suspend fun insert(call: CallEntity): Long = throw NotImplementedError()
    override suspend fun update(call: CallEntity) { calls[call.id] = call }
    override suspend fun findById(id: Long): CallEntity? = calls[id]
    override fun observeCallsSince(sinceEpochMs: Long): Flow<Int> = flowOf(0)
    override fun observeCostSince(sinceEpochMs: Long): Flow<Double> = flowOf(0.0)
    override suspend fun findLatest(): CallEntity? = calls.values.maxByOrNull { it.id }
}

private class FakeTurnDao(private val turns: List<TurnEntity>) : TurnDao {
    override suspend fun insert(turn: TurnEntity): Long = throw NotImplementedError()
    override suspend fun forCall(callId: Long): List<TurnEntity> = turns.filter { it.callId == callId }
}

private class FakePatientProfileDao : PatientProfileDao {
    val upserted = mutableListOf<PatientProfileEntity>()
    var existing: PatientProfileEntity? = null
    override suspend fun upsert(profile: PatientProfileEntity) {
        upserted.add(profile)
        existing = profile
    }
    override suspend fun find(number: String): PatientProfileEntity? = existing
    override fun observeAll(): Flow<List<PatientProfileEntity>> = flowOf(listOfNotNull(existing))
    override fun observe(number: String): Flow<PatientProfileEntity?> = MutableStateFlow(existing)
}

private class FakeProfileUpdateDao : ProfileUpdateDao {
    val inserted = mutableListOf<ProfileUpdateEntity>()
    override suspend fun insert(update: ProfileUpdateEntity): Long {
        inserted.add(update)
        return inserted.size.toLong()
    }
    override fun observeForNumber(number: String): Flow<List<ProfileUpdateEntity>> =
        flowOf(inserted.filter { it.number == number })
    override suspend fun forNumber(number: String): List<ProfileUpdateEntity> = inserted.filter { it.number == number }
    override suspend fun existsForCall(callId: Long): Boolean = inserted.any { it.callId == callId }
    override suspend fun countForNumber(number: String): Int = inserted.count { it.number == number }
    override suspend fun deleteForCall(callId: Long) {
        inserted.removeAll { it.callId == callId }
    }
}

class ProfileSummarizerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newSummarizer(
        callDao: CallDao,
        turnDao: TurnDao,
        profileDao: FakePatientProfileDao,
        updateDao: FakeProfileUpdateDao,
    ): ProfileSummarizer {
        val client = OkHttpClient()
        // Point at the mock server instead of the real Gemini endpoint by wrapping the base URL:
        // ProfileSummarizer builds its own absolute URL, so we can't redirect it without a real
        // interceptor. Use an interceptor that rewrites the host to the mock server's.
        val redirecting = client.newBuilder().addInterceptor { chain ->
            val original = chain.request()
            val newUrl = original.url.newBuilder()
                .scheme(server.url("/").scheme)
                .host(server.url("/").host)
                .port(server.url("/").port)
                .build()
            chain.proceed(original.newBuilder().url(newUrl).build())
        }.build()

        return ProfileSummarizer(
            turnDao = turnDao,
            callDao = callDao,
            profileDao = profileDao,
            profileUpdateDao = updateDao,
            apiKey = { "test-key" },
            modelId = "gemini-2.5-flash",
            retryBackoffsMs = listOf(0L),
            client = redirecting,
        )
    }

    private val call = CallEntity(id = 1L, number = "01700000001", direction = CallDirection.INBOUND, startedAt = 0L)

    private val successJsonBody = """
        {
          "displayName": "Karim",
          "ageYears": 40,
          "sex": "male",
          "village": null,
          "chronicConditions": ["diabetes"],
          "currentSymptoms": ["fever"],
          "medications": ["metformin"],
          "allergies": [],
          "riskFlags": ["fever >3 days"],
          "adviceGiven": ["drink water"],
          "followUpNeeded": true,
          "followUpNote": "check in 2 days",
          "summaryBn": "রোগীর জ্বর আছে।",
          "summaryEn": "Patient has a fever.",
          "deltaSummary": "Reported new fever."
        }
    """.trimIndent()

    private fun mockGenerateContentResponse(modelText: String): String {
        // Real API wraps the model's JSON output as a string inside candidates[0].content.parts[0].text.
        val escaped = modelText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"candidates":[{"content":{"parts":[{"text":"$escaped"}]}}]}"""
    }

    @Test
    fun `empty transcript is skipped, no HTTP call made, profile untouched`() = runTest {
        val callDao = FakeCallDao(mutableMapOf(1L to call))
        val turnDao = FakeTurnDao(emptyList())
        val profileDao = FakePatientProfileDao()
        val updateDao = FakeProfileUpdateDao()
        val summarizer = newSummarizer(callDao, turnDao, profileDao, updateDao)

        summarizer.onCallFinished(1L)

        assertEquals(0, server.requestCount)
        assertTrue(profileDao.upserted.isEmpty())
        assertTrue(updateDao.inserted.isEmpty())
    }

    @Test
    fun `successful response merges into a new profile and records a delta update`() = runTest {
        val callDao = FakeCallDao(mutableMapOf(1L to call))
        val turnDao = FakeTurnDao(
            listOf(
                TurnEntity(callId = 1L, role = TurnRole.CALLER, text = "আমার জ্বর হয়েছে", tMs = 0L),
                TurnEntity(callId = 1L, role = TurnRole.ASSISTANT, text = "পানি পান করুন", tMs = 1000L),
            )
        )
        val profileDao = FakePatientProfileDao()
        val updateDao = FakeProfileUpdateDao()
        val summarizer = newSummarizer(callDao, turnDao, profileDao, updateDao)

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockGenerateContentResponse(successJsonBody)))

        summarizer.onCallFinished(1L)

        assertEquals(1, server.requestCount)
        val profile = profileDao.existing
        assertNotNull(profile)
        assertEquals("Karim", profile!!.displayName)
        assertEquals(40, profile.ageYears)
        assertEquals(listOf("diabetes"), profile.chronicConditions)
        assertEquals(listOf("fever >3 days"), profile.riskFlags)
        assertTrue(profile.followUpNeeded)
        assertEquals("Patient has a fever.", profile.summaryEn)
        assertEquals(1, profile.callCount)
        assertNull(profile.lastError)

        assertEquals(1, updateDao.inserted.size)
        assertEquals("Reported new fever.", updateDao.inserted.first().deltaSummary)
        assertEquals(1L, updateDao.inserted.first().callId)
    }

    @Test
    fun `HTTP failure records lastError and leaves existing profile fields untouched`() = runTest {
        val callDao = FakeCallDao(mutableMapOf(1L to call))
        val turnDao = FakeTurnDao(listOf(TurnEntity(callId = 1L, role = TurnRole.CALLER, text = "hi", tMs = 0L)))
        val profileDao = FakePatientProfileDao()
        val existingProfile = PatientProfileEntity(
            number = "01700000001",
            displayName = "Karim",
            summaryEn = "Prior summary.",
            callCount = 2,
            lastUpdated = 123L,
        )
        profileDao.existing = existingProfile
        val updateDao = FakeProfileUpdateDao()
        val summarizer = newSummarizer(callDao, turnDao, profileDao, updateDao)

        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))

        summarizer.onCallFinished(1L)

        assertEquals(1, server.requestCount)
        assertTrue(updateDao.inserted.isEmpty())
        val profile = profileDao.existing
        assertNotNull(profile)
        assertEquals("Karim", profile!!.displayName)
        assertEquals("Prior summary.", profile.summaryEn)
        assertEquals(2, profile.callCount) // unchanged, not incremented on failure
        assertNotNull(profile.lastError)
    }

    @Test
    fun `unparsable model output records lastError instead of throwing`() = runTest {
        val callDao = FakeCallDao(mutableMapOf(1L to call))
        val turnDao = FakeTurnDao(listOf(TurnEntity(callId = 1L, role = TurnRole.CALLER, text = "hi", tMs = 0L)))
        val profileDao = FakePatientProfileDao()
        val updateDao = FakeProfileUpdateDao()
        val summarizer = newSummarizer(callDao, turnDao, profileDao, updateDao)

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockGenerateContentResponse("not valid json")))

        summarizer.onCallFinished(1L)

        assertTrue(updateDao.inserted.isEmpty())
        assertNotNull(profileDao.existing?.lastError)
    }

    // --- N3: accumulate semantics for list fields --------------------------------------------

    @Test
    fun `model returns empty arrays, existing allergies and conditions are retained`() = runTest {
        val callDao = FakeCallDao(mutableMapOf(1L to call))
        val turnDao = FakeTurnDao(listOf(TurnEntity(callId = 1L, role = TurnRole.CALLER, text = "hi", tMs = 0L)))
        val profileDao = FakePatientProfileDao()
        profileDao.existing = PatientProfileEntity(
            number = "01700000001",
            chronicConditions = listOf("diabetes"),
            medications = listOf("metformin"),
            allergies = listOf("penicillin"),
            riskFlags = listOf("pregnant"),
            adviceGiven = listOf("drink water"),
            callCount = 1,
        )
        val updateDao = FakeProfileUpdateDao()
        val summarizer = newSummarizer(callDao, turnDao, profileDao, updateDao)

        val emptyListsBody = """
            {
              "displayName": null, "ageYears": null, "sex": null, "village": null,
              "chronicConditions": [], "currentSymptoms": [], "medications": [], "allergies": [],
              "riskFlags": [], "adviceGiven": [], "followUpNeeded": false, "followUpNote": null,
              "summaryBn": "স", "summaryEn": "s", "deltaSummary": "nothing new"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(mockGenerateContentResponse(emptyListsBody)))

        summarizer.onCallFinished(1L)

        val profile = profileDao.existing
        assertNotNull(profile)
        assertEquals(listOf("diabetes"), profile!!.chronicConditions)
        assertEquals(listOf("metformin"), profile.medications)
        assertEquals(listOf("penicillin"), profile.allergies)
        assertEquals(listOf("pregnant"), profile.riskFlags)
        assertEquals(listOf("drink water"), profile.adviceGiven)
    }

    @Test
    fun `list fields union case-insensitively without duplicating existing entries`() = runTest {
        val callDao = FakeCallDao(mutableMapOf(1L to call))
        val turnDao = FakeTurnDao(listOf(TurnEntity(callId = 1L, role = TurnRole.CALLER, text = "hi", tMs = 0L)))
        val profileDao = FakePatientProfileDao()
        profileDao.existing = PatientProfileEntity(
            number = "01700000001",
            chronicConditions = listOf("Diabetes"),
            riskFlags = listOf("pregnant"),
        )
        val updateDao = FakeProfileUpdateDao()
        val summarizer = newSummarizer(callDao, turnDao, profileDao, updateDao)

        val body = """
            {
              "displayName": null, "ageYears": null, "sex": null, "village": null,
              "chronicConditions": ["diabetes", "hypertension"], "currentSymptoms": [],
              "medications": [], "allergies": [], "riskFlags": ["pregnant", "fever >3 days"],
              "adviceGiven": [], "followUpNeeded": false, "followUpNote": null,
              "summaryBn": "স", "summaryEn": "s", "deltaSummary": "new condition"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(mockGenerateContentResponse(body)))

        summarizer.onCallFinished(1L)

        val profile = profileDao.existing!!
        // "diabetes" not duplicated despite case difference; "hypertension" newly added.
        assertEquals(listOf("Diabetes", "hypertension"), profile.chronicConditions)
        assertEquals(listOf("pregnant", "fever >3 days"), profile.riskFlags)
    }

    // --- n7: idempotent per callId, callCount derived from profile_updates rows --------------

    @Test
    fun `re-running onCallFinished for the same callId without force is a no-op`() = runTest {
        val callDao = FakeCallDao(mutableMapOf(1L to call))
        val turnDao = FakeTurnDao(listOf(TurnEntity(callId = 1L, role = TurnRole.CALLER, text = "hi", tMs = 0L)))
        val profileDao = FakePatientProfileDao()
        val updateDao = FakeProfileUpdateDao()
        val summarizer = newSummarizer(callDao, turnDao, profileDao, updateDao)

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockGenerateContentResponse(successJsonBody)))
        summarizer.onCallFinished(1L)
        assertEquals(1, server.requestCount)
        assertEquals(1, profileDao.existing?.callCount)
        assertEquals(1, updateDao.inserted.size)

        // Second call, same callId, no force: should skip entirely (no HTTP call, no new row).
        summarizer.onCallFinished(1L)
        assertEquals(1, server.requestCount)
        assertEquals(1, profileDao.existing?.callCount)
        assertEquals(1, updateDao.inserted.size)
    }

    @Test
    fun `force re-run replaces the call's update row instead of duplicating it`() = runTest {
        val callDao = FakeCallDao(mutableMapOf(1L to call))
        val turnDao = FakeTurnDao(listOf(TurnEntity(callId = 1L, role = TurnRole.CALLER, text = "hi", tMs = 0L)))
        val profileDao = FakePatientProfileDao()
        val updateDao = FakeProfileUpdateDao()
        val summarizer = newSummarizer(callDao, turnDao, profileDao, updateDao)

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockGenerateContentResponse(successJsonBody)))
        summarizer.onCallFinished(1L)
        assertEquals(1, updateDao.inserted.size)
        assertEquals(1, profileDao.existing?.callCount)

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockGenerateContentResponse(successJsonBody)))
        summarizer.onCallFinished(1L, force = true)

        assertEquals(2, server.requestCount)
        // Still exactly one update row for this call (replaced, not duplicated) so callCount
        // doesn't inflate from re-summarizing the same call twice.
        assertEquals(1, updateDao.inserted.size)
        assertEquals(1, profileDao.existing?.callCount)
    }

    @Test
    fun `callCount is derived from distinct calls with an update row, not an incrementing counter`() = runTest {
        val call2 = call.copy(id = 2L)
        val callDao = FakeCallDao(mutableMapOf(1L to call, 2L to call2))
        val turnDao = FakeTurnDao(
            listOf(
                TurnEntity(callId = 1L, role = TurnRole.CALLER, text = "hi", tMs = 0L),
                TurnEntity(callId = 2L, role = TurnRole.CALLER, text = "hi again", tMs = 0L),
            )
        )
        val profileDao = FakePatientProfileDao()
        val updateDao = FakeProfileUpdateDao()
        val summarizer = newSummarizer(callDao, turnDao, profileDao, updateDao)

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockGenerateContentResponse(successJsonBody)))
        summarizer.onCallFinished(1L)
        server.enqueue(MockResponse().setResponseCode(200).setBody(mockGenerateContentResponse(successJsonBody)))
        summarizer.onCallFinished(2L)

        assertEquals(2, profileDao.existing?.callCount)
        assertEquals(2, updateDao.inserted.size)
    }

    // --- n2/n3: multi-part responses and MAX_TOKENS finishReason ------------------------------

    @Test
    fun `text parts are concatenated across multiple response parts`() = runTest {
        val callDao = FakeCallDao(mutableMapOf(1L to call))
        val turnDao = FakeTurnDao(listOf(TurnEntity(callId = 1L, role = TurnRole.CALLER, text = "hi", tMs = 0L)))
        val profileDao = FakePatientProfileDao()
        val updateDao = FakeProfileUpdateDao()
        val summarizer = newSummarizer(callDao, turnDao, profileDao, updateDao)

        val splitPoint = successJsonBody.length / 2
        val part1 = successJsonBody.substring(0, splitPoint).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val part2 = successJsonBody.substring(splitPoint).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val multiPartResponse = """{"candidates":[{"content":{"parts":[{"text":"$part1"},{"text":"$part2"}]}}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(multiPartResponse))

        summarizer.onCallFinished(1L)

        assertEquals("Karim", profileDao.existing?.displayName)
        assertNull(profileDao.existing?.lastError)
    }

    @Test
    fun `MAX_TOKENS finishReason is recorded as an error, not parsed as a truncated profile`() = runTest {
        val callDao = FakeCallDao(mutableMapOf(1L to call))
        val turnDao = FakeTurnDao(listOf(TurnEntity(callId = 1L, role = TurnRole.CALLER, text = "hi", tMs = 0L)))
        val profileDao = FakePatientProfileDao()
        val updateDao = FakeProfileUpdateDao()
        val summarizer = newSummarizer(callDao, turnDao, profileDao, updateDao)

        val escaped = successJsonBody.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val truncatedResponse = """{"candidates":[{"content":{"parts":[{"text":"$escaped"}]},"finishReason":"MAX_TOKENS"}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(truncatedResponse))

        summarizer.onCallFinished(1L)

        assertTrue(updateDao.inserted.isEmpty())
        assertNotNull(profileDao.existing?.lastError)
        assertTrue(profileDao.existing!!.lastError!!.contains("MAX_TOKENS"))
    }

    // --- n4: CancellationException must propagate, never be swallowed ------------------------

    @Test(expected = CancellationException::class)
    fun `CancellationException from a dao is rethrown, not swallowed`() = runTest {
        val cancellingTurnDao = object : TurnDao {
            override suspend fun insert(turn: TurnEntity): Long = throw NotImplementedError()
            override suspend fun forCall(callId: Long): List<TurnEntity> = throw CancellationException("cancelled")
        }
        val callDao = FakeCallDao(mutableMapOf(1L to call))
        val profileDao = FakePatientProfileDao()
        val updateDao = FakeProfileUpdateDao()
        val summarizer = newSummarizer(callDao, cancellingTurnDao, profileDao, updateDao)

        summarizer.onCallFinished(1L)
    }
}
