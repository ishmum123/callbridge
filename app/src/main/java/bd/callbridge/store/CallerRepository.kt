package bd.callbridge.store

import kotlinx.coroutines.flow.Flow

/**
 * Consulted by [bd.callbridge.call.CallStateMachine]'s registered-caller policy (spec §3 step 2,
 * §4.1): known callers are answered immediately, unknown callers are rejected then called back.
 */
interface CallerRepository {
    suspend fun isRegistered(number: String): Boolean

    suspend fun find(number: String): CallerEntity?

    suspend fun register(
        number: String,
        name: String?,
        village: String?,
        occupation: String?,
        registeredByShop: String? = null,
    )

    fun observeAll(): Flow<List<CallerEntity>>
}

class RoomCallerRepository(private val db: CallBridgeDatabase) : CallerRepository {
    override suspend fun isRegistered(number: String): Boolean = find(number) != null

    override suspend fun find(number: String): CallerEntity? = db.callerDao().findByNumber(number)

    override suspend fun register(
        number: String,
        name: String?,
        village: String?,
        occupation: String?,
        registeredByShop: String?,
    ) {
        db.callerDao().upsert(
            CallerEntity(
                number = number,
                name = name,
                village = village,
                occupation = occupation,
                registeredByShop = registeredByShop,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    override fun observeAll(): Flow<List<CallerEntity>> = db.callerDao().observeAll()
}
