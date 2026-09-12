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

/**
 * Patient profile (demo feature, not in spec): a running clinical picture of one caller, kept
 * across calls and refreshed by [bd.callbridge.profile.ProfileSummarizer] after each call
 * finishes. Keyed by phone number (1:1 with [CallerEntity], but intentionally not FK-linked to
 * it since a profile can exist for a caller who was never explicitly registered).
 *
 * List fields ([chronicConditions] etc.) are stored as JSON via [Converters]; Room only ever sees
 * a single TEXT column per field. [lastError] records the most recent summarizer failure (API
 * error, bad JSON, ...) without ever discarding the last-good profile fields.
 */
@Entity(tableName = "patient_profiles")
data class PatientProfileEntity(
    @PrimaryKey val number: String,
    val displayName: String? = null,
    val ageYears: Int? = null,
    val sex: String? = null,
    val village: String? = null,
    val chronicConditions: List<String> = emptyList(),
    val currentSymptoms: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val riskFlags: List<String> = emptyList(),
    val adviceGiven: List<String> = emptyList(),
    val followUpNeeded: Boolean = false,
    val followUpNote: String? = null,
    val summaryBn: String = "",
    val summaryEn: String = "",
    val lastUpdated: Long = 0L,
    val callCount: Int = 0,
    val lastError: String? = null,
)

/**
 * One row per call that touched a [PatientProfileEntity], so a manager can see what changed
 * call-over-call rather than only the latest merged snapshot.
 */
@Entity(
    tableName = "profile_updates",
    foreignKeys = [
        ForeignKey(
            entity = PatientProfileEntity::class,
            parentColumns = ["number"],
            childColumns = ["number"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("number")],
)
data class ProfileUpdateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val callId: Long,
    val timestamp: Long,
    val deltaSummary: String,
)
