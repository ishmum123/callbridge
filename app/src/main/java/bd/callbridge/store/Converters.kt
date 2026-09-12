package bd.callbridge.store

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromCallDirection(value: CallDirection): String = value.name

    @TypeConverter
    fun toCallDirection(value: String): CallDirection = CallDirection.valueOf(value)

    @TypeConverter
    fun fromTurnRole(value: TurnRole): String = value.name

    @TypeConverter
    fun toTurnRole(value: String): TurnRole = TurnRole.valueOf(value)
}
