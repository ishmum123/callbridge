package bd.callbridge.store

import kotlinx.coroutines.flow.Flow

/**
 * Read-side access to patient profiles + their per-call update history (demo "Patient profile"
 * feature). Writes happen exclusively through [bd.callbridge.profile.ProfileSummarizer]; this
 * repository is what the UI (`ui.PatientsListActivity` / `ui.PatientDetailActivity`) reads from.
 */
interface ProfileRepository {
    fun observeAll(): Flow<List<PatientProfileEntity>>

    fun observe(number: String): Flow<PatientProfileEntity?>

    suspend fun find(number: String): PatientProfileEntity?

    fun observeUpdates(number: String): Flow<List<ProfileUpdateEntity>>
}

class RoomProfileRepository(private val db: CallBridgeDatabase) : ProfileRepository {
    override fun observeAll(): Flow<List<PatientProfileEntity>> = db.patientProfileDao().observeAll()

    override fun observe(number: String): Flow<PatientProfileEntity?> = db.patientProfileDao().observe(number)

    override suspend fun find(number: String): PatientProfileEntity? = db.patientProfileDao().find(number)

    override fun observeUpdates(number: String): Flow<List<ProfileUpdateEntity>> =
        db.profileUpdateDao().observeForNumber(number)
}
