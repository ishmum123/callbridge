package bd.callbridge.store

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromCallDirection(value: CallDirection): String = value.name

    @TypeConverter
    fun toCallDirection(value: String): CallDirection = CallDirection.valueOf(value)

    @TypeConverter
    fun fromTurnRole(value: TurnRole): String = value.name

    @TypeConverter
    fun toTurnRole(value: String): TurnRole = TurnRole.valueOf(value)

    /** [PatientProfileEntity]'s list-of-string fields (chronicConditions, riskFlags, ...), stored
     *  as a single JSON-array TEXT column. */
    @TypeConverter
    fun fromStringList(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
