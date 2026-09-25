package com.ruwen.audioplayer.data.db

import androidx.room.TypeConverter
import com.ruwen.audioplayer.data.entity.SubtitleStatus

class SubtitleStatusConverter {

    @TypeConverter
    fun fromSubtitleStatus(status: SubtitleStatus): Int {
        return status.ordinal
    }

    @TypeConverter
    fun toSubtitleStatus(ordinal: Int): SubtitleStatus {
        val values = SubtitleStatus.values()
        return if (ordinal in values.indices) values[ordinal] else SubtitleStatus.NOT_GENERATED
    }
}
