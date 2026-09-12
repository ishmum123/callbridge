package bd.callbridge.store

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Pinned to API 34, same reason as CaptureWavDumperTest: Robolectric 4.13 doesn't yet support
 *  simulating targetSdk 36. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatientProfileDaoTest {

    private lateinit var db: CallBridgeDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CallBridgeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert then find round-trips all fields including list columns`() = runTest {
        val profile = PatientProfileEntity(
            number = "01700000001",
            displayName = "রহিমা বেগম",
            ageYears = 28,
            sex = "female",
            village = "গ্রাম",
            chronicConditions = listOf("hypertension"),
            currentSymptoms = listOf("fever", "cough"),
            medications = listOf("paracetamol"),
            allergies = emptyList(),
            riskFlags = listOf("pregnant", "fever >3 days"),
            adviceGiven = listOf("drink water", "rest"),
            followUpNeeded = true,
            followUpNote = "recheck in 3 days",
            summaryBn = "সংক্ষিপ্ত বিবরণ",
            summaryEn = "Short summary",
            lastUpdated = 1_000L,
            callCount = 2,
            lastError = null,
        )

        db.patientProfileDao().upsert(profile)
        val loaded = db.patientProfileDao().find("01700000001")

        assertEquals(profile, loaded)
    }

    @Test
    fun `upsert replaces existing row for the same number`() = runTest {
        db.patientProfileDao().upsert(PatientProfileEntity(number = "01700000002", callCount = 1))
        db.patientProfileDao().upsert(PatientProfileEntity(number = "01700000002", callCount = 5, riskFlags = listOf("chest pain reported")))

        val loaded = db.patientProfileDao().find("01700000002")
        assertEquals(5, loaded?.callCount)
        assertEquals(listOf("chest pain reported"), loaded?.riskFlags)
    }

    @Test
    fun `find returns null for unknown number`() = runTest {
        assertNull(db.patientProfileDao().find("no-such-number"))
    }

    @Test
    fun `profile update history round-trips and orders by timestamp descending`() = runTest {
        db.patientProfileDao().upsert(PatientProfileEntity(number = "01700000003"))
        db.profileUpdateDao().insert(ProfileUpdateEntity(number = "01700000003", callId = 1L, timestamp = 100L, deltaSummary = "first call"))
        db.profileUpdateDao().insert(ProfileUpdateEntity(number = "01700000003", callId = 2L, timestamp = 200L, deltaSummary = "second call"))

        val history = db.profileUpdateDao().forNumber("01700000003")
        assertEquals(2, history.size)
        assertEquals("second call", history.first().deltaSummary)
        assertEquals("first call", history.last().deltaSummary)
    }
}
