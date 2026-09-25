package com.ruwen.audioplayer.data.db

import androidx.room.TypeConverter
import com.ruwen.audioplayer.data.entity.DownloadStatus

/**
 * [DownloadStatus] 按 ordinal 落库。
 *
 * 与 SubtitleStatusConverter 同样约定：**只能往枚举末尾追加取值**，
 * 调整顺序或在中间插入都会让已存库的整数指向错误的含义。
 */
class DownloadStatusConverter {

    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus): Int = status.ordinal

    @TypeConverter
    fun toDownloadStatus(ordinal: Int): DownloadStatus {
        val values = DownloadStatus.values()
        return if (ordinal in values.indices) values[ordinal] else DownloadStatus.NOT_DOWNLOADED
    }
}
