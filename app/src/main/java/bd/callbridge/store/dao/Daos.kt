package bd.callbridge.store.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import bd.callbridge.store.CallEntity
import bd.callbridge.store.CallerEntity
import bd.callbridge.store.PatientProfileEntity
import bd.callbridge.store.ProfileUpdateEntity
import bd.callbridge.store.TurnEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(caller: CallerEntity)

    @Query("SELECT * FROM callers WHERE number = :number")
    suspend fun findByNumber(number: String): CallerEntity?

    @Query("SELECT * FROM callers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CallerEntity>>
}

@Dao
interface CallDao {
    @Insert
    suspend fun insert(call: CallEntity): Long

    @Update
    suspend fun update(call: CallEntity)

    @Query("SELECT * FROM calls WHERE id = :id")
    suspend fun findById(id: Long): CallEntity?

    @Query("SELECT COUNT(*) FROM calls WHERE startedAt >= :sinceEpochMs")
    fun observeCallsSince(sinceEpochMs: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(estCostUsd), 0.0) FROM calls WHERE startedAt >= :sinceEpochMs")
    fun observeCostSince(sinceEpochMs: Long): Flow<Double>

    /** Debug/demo helper (DebugInjectReceiver's DEBUG_SUMMARIZE with no callId): most recent call. */
    @Query("SELECT * FROM calls ORDER BY id DESC LIMIT 1")
    suspend fun findLatest(): CallEntity?
}

@Dao
interface TurnDao {
    @Insert
    suspend fun insert(turn: TurnEntity): Long

    @Query("SELECT * FROM turns WHERE callId = :callId ORDER BY tMs ASC")
    suspend fun forCall(callId: Long): List<TurnEntity>
}

@Dao
interface PatientProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: PatientProfileEntity)

    @Query("SELECT * FROM patient_profiles WHERE number = :number")
    suspend fun find(number: String): PatientProfileEntity?

    @Query("SELECT * FROM patient_profiles ORDER BY lastUpdated DESC")
    fun observeAll(): Flow<List<PatientProfileEntity>>

    @Query("SELECT * FROM patient_profiles WHERE number = :number")
    fun observe(number: String): Flow<PatientProfileEntity?>
}

@Dao
interface ProfileUpdateDao {
    @Insert
    suspend fun insert(update: ProfileUpdateEntity): Long

    @Query("SELECT * FROM profile_updates WHERE number = :number ORDER BY timestamp DESC")
    fun observeForNumber(number: String): Flow<List<ProfileUpdateEntity>>

    @Query("SELECT * FROM profile_updates WHERE number = :number ORDER BY timestamp DESC")
    suspend fun forNumber(number: String): List<ProfileUpdateEntity>
}
