package bd.callbridge.store

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * MAJOR N4 fix: verifies [MIGRATION_1_2] actually works against a real on-disk v1 database,
 * rather than relying on [CallBridgeDatabase.fallbackToDestructiveMigration] (which would just
 * wipe callers/calls/turns on upgrade).
 *
 * No `androidx.room:room-testing` dependency exists in this project (see build.gradle.kts), and
 * `MigrationTestHelper` is normally exercised from androidTest anyway — so instead this test
 * builds a v1 SQLite file by hand (raw SQL copied from `app/schemas/.../1.json`, same tables as
 * v1 unchanged in v2), sets its `user_version` pragma to 1, then opens it through the real
 * [CallBridgeDatabase] with [MIGRATION_1_2] registered. Android's SQLiteOpenHelper machinery
 * (shadowed by Robolectric) drives `onUpgrade(1, 2)` exactly as it would on a real device, which
 * is what actually exercises the migration's SQL — the same fallback strategy the brief allows
 * when MigrationTestHelper isn't available in `testDebugUnitTest`.
 *
 * Pinned to API 34, same reason as PatientProfileDaoTest/CaptureWavDumperTest: Robolectric 4.13
 * doesn't yet support simulating targetSdk 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallBridgeMigrationTest {

    private lateinit var dbFile: File

    @Before
    fun setUp() {
        dbFile = RuntimeEnvironment.getApplication().getDatabasePath("migration-test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun `migration 1 to 2 creates patient_profiles and profile_updates and preserves v1 data`() = runTest {
        // Build a v1 database on disk: same callers/calls/turns SQL as 1.json (unchanged in v2),
        // with a seeded caller row so we can assert v1 data survives the upgrade.
        val v1 = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        v1.execSQL(
            "CREATE TABLE IF NOT EXISTS `callers` (`number` TEXT NOT NULL, `name` TEXT, " +
                "`village` TEXT, `occupation` TEXT, `languageNote` TEXT, `registeredByShop` TEXT, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`number`))"
        )
        v1.execSQL(
            "CREATE TABLE IF NOT EXISTS `calls` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`number` TEXT NOT NULL, `direction` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                "`endedAt` INTEGER, `endReason` TEXT, `inputSeconds` REAL NOT NULL, " +
                "`outputSeconds` REAL NOT NULL, `estCostUsd` REAL NOT NULL)"
        )
        v1.execSQL(
            "CREATE TABLE IF NOT EXISTS `turns` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`callId` INTEGER NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`tMs` INTEGER NOT NULL, FOREIGN KEY(`callId`) REFERENCES `calls`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        v1.execSQL("CREATE INDEX IF NOT EXISTS `index_turns_callId` ON `turns` (`callId`)")
        v1.execSQL(
            "INSERT INTO callers (number, name, village, occupation, languageNote, registeredByShop, createdAt) " +
                "VALUES ('01700000001', 'Karim', NULL, NULL, NULL, NULL, 1000)"
        )
        v1.version = 1 // sets PRAGMA user_version = 1, what Room's SQLiteOpenHelper checks on open
        v1.close()

        val db = Room.databaseBuilder(
            RuntimeEnvironment.getApplication(),
            CallBridgeDatabase::class.java,
            dbFile.absolutePath,
        )
            .addMigrations(MIGRATION_1_2)
            .build()

        try {
            // Opening triggers onUpgrade(1, 2) -> MIGRATION_1_2.migrate(). Pre-existing v1 data
            // must still be there afterwards.
            val caller = db.callerDao().findByNumber("01700000001")
            assertEquals("Karim", caller?.name)

            // The new v2 tables must exist and be fully usable (not just present but empty-schema).
            db.patientProfileDao().upsert(PatientProfileEntity(number = "01700000001", callCount = 1))
            val profile = db.patientProfileDao().find("01700000001")
            assertEquals(1, profile?.callCount)

            db.profileUpdateDao().insert(
                ProfileUpdateEntity(number = "01700000001", callId = 1L, timestamp = 1L, deltaSummary = "first call")
            )
            val history = db.profileUpdateDao().forNumber("01700000001")
            assertEquals(1, history.size)
            assertEquals("first call", history.first().deltaSummary)
        } finally {
            db.close()
        }
    }
}
