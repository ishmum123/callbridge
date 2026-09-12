package bd.callbridge.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import bd.callbridge.store.dao.CallDao
import bd.callbridge.store.dao.CallerDao
import bd.callbridge.store.dao.PatientProfileDao
import bd.callbridge.store.dao.ProfileUpdateDao
import bd.callbridge.store.dao.TurnDao

@Database(
    entities = [
        CallerEntity::class,
        CallEntity::class,
        TurnEntity::class,
        PatientProfileEntity::class,
        ProfileUpdateEntity::class,
    ],
    // v2: added patient_profiles/profile_updates (demo "Patient profile" feature). No migration
    // path from v1 is written (demo/hackathon build, pre-pilot data) — see
    // fallbackToDestructiveMigration() below.
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
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
