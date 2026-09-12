package bd.callbridge.store

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** spec §4.5: `callers(number PK, name, village, occupation, language_note, registered_by_shop, created_at)` */
@Entity(tableName = "callers")
data class CallerEntity(
    @PrimaryKey val number: String,
    val name: String?,
    val village: String?,
    val occupation: String?,
    val languageNote: String? = null,
    val registeredByShop: String? = null,
    val createdAt: Long,
)

/** spec §4.5: `calls(id, number, direction, started_at, ended_at, end_reason, input_seconds, output_seconds, est_cost_usd)` */
@Entity(tableName = "calls")
data class CallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val direction: CallDirection,
    val startedAt: Long,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val inputSeconds: Double = 0.0,
    val outputSeconds: Double = 0.0,
    val estCostUsd: Double = 0.0,
)

enum class CallDirection { INBOUND, OUTBOUND_CALLBACK }

/** spec §4.5: `turns(id, call_id, role, text, t_ms)` */
@Entity(
    tableName = "turns",
    foreignKeys = [
        ForeignKey(
            entity = CallEntity::class,
            parentColumns = ["id"],
            childColumns = ["callId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("callId")],
)
data class TurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callId: Long,
    val role: TurnRole,
    val text: String,
    val tMs: Long,
)

enum class TurnRole { CALLER, ASSISTANT }
