package bd.callbridge.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import bd.callbridge.store.dao.CallDao
import bd.callbridge.store.dao.CallerDao
import bd.callbridge.store.dao.PatientProfileDao
import bd.callbridge.store.dao.ProfileUpdateDao
import bd.callbridge.store.dao.TurnDao

/**
 * v1 -> v2: added `patient_profiles`/`profile_updates` (demo "Patient profile" feature). SQL
 * copied verbatim from the exported schema at `app/schemas/.../2.json` for these two tables (no
 * other table changed between v1 and v2). [CallBridgeDatabase.fallbackToDestructiveMigration] is
 * kept only as a backstop for any *future* version bump that ships without its own migration.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `patient_profiles` (`number` TEXT NOT NULL, " +
                "`displayName` TEXT, `ageYears` INTEGER, `sex` TEXT, `village` TEXT, " +
                "`chronicConditions` TEXT NOT NULL, `currentSymptoms` TEXT NOT NULL, " +
                "`medications` TEXT NOT NULL, `allergies` TEXT NOT NULL, `riskFlags` TEXT NOT NULL, " +
                "`adviceGiven` TEXT NOT NULL, `followUpNeeded` INTEGER NOT NULL, " +
                "`followUpNote` TEXT, `summaryBn` TEXT NOT NULL, `summaryEn` TEXT NOT NULL, " +
                "`lastUpdated` INTEGER NOT NULL, `callCount` INTEGER NOT NULL, `lastError` TEXT, " +
                "PRIMARY KEY(`number`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `profile_updates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`number` TEXT NOT NULL, `callId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`deltaSummary` TEXT NOT NULL, FOREIGN KEY(`number`) REFERENCES `patient_profiles`(`number`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_profile_updates_number` ON `profile_updates` (`number`)"
        )
    }
}

@Database(
    entities = [
        CallerEntity::class,
        CallEntity::class,
        TurnEntity::class,
        PatientProfileEntity::class,
        ProfileUpdateEntity::class,
    ],
    // v2: added patient_profiles/profile_updates (demo "Patient profile" feature). See
    // MIGRATION_1_2 above for the real v1->v2 path; fallbackToDestructiveMigration() below is
    // only a backstop for a future version bump that lacks its own migration.
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CallBridgeDatabase : RoomDatabase() {
    abstract fun callerDao(): CallerDao
    abstract fun callDao(): CallDao
    abstract fun turnDao(): TurnDao
    abstract fun patientProfileDao(): PatientProfileDao
    abstract fun profileUpdateDao(): ProfileUpdateDao

    companion object {
        private const val DB_NAME = "callbridge.db"

        @Volatile
        private var instance: CallBridgeDatabase? = null

        fun getInstance(context: Context): CallBridgeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CallBridgeDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
