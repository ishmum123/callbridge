package bd.callbridge.store.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import bd.callbridge.store.CallEntity
import bd.callbridge.store.CallerEntity
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
}

@Dao
interface TurnDao {
    @Insert
    suspend fun insert(turn: TurnEntity): Long

    @Query("SELECT * FROM turns WHERE callId = :callId ORDER BY tMs ASC")
    suspend fun forCall(callId: Long): List<TurnEntity>
}
