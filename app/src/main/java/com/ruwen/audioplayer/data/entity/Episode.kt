package com.ruwen.audioplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播客单集（一条音频）。
 *
 * guid 与 podcastId 组成唯一约束：RSS 刷新时用它判断"这一集是否已存在"。
 * 若源里没有 guid，解析层需回退用 audioUrl（或 link）填充，保证非空，
 * 否则唯一索引会因多条空 guid 而冲突。
 */
@Entity(
    tableName = "episodes",
    foreignKeys = [ForeignKey(
        entity = Podcast::class,
        parentColumns = ["id"],
        childColumns = ["podcastId"],
        // 取消订阅删除播客时，级联删除其全部单集
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("podcastId"),
        Index(value = ["podcastId", "guid"], unique = true)
    ]
)
data class Episode(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val podcastId: Long,
    val title: String,
    val description: String? = null,
    /** 发布时间（毫秒时间戳）；源中无日期时为 0 */
    val pubDate: Long = 0,
    /** 音频下载地址（RSS enclosure url） */
    val audioUrl: String,
    /** 音频字节大小（RSS enclosure length）；0 表示源未声明 */
    val sizeBytes: Long = 0,
    /** 时长（秒）；0 表示源未声明 */
    val durationSec: Long = 0,
    /** 单集封面，为空时 UI 回退用播客封面 */
    val imageUrl: String? = null,
    /** RSS guid，配合 podcastId 唯一 */
    val guid: String,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    /** 下载完成后的本地文件路径；未下载为 null */
    val localPath: String? = null,
    val downloadedAt: Long = 0
)

/**
 * 单集下载状态。
 *
 * 注意：Room 按 ordinal 落库（见 DownloadStatusConverter），
 * 新增取值只能追加到末尾，不能插入到中间或调整顺序。
 */
enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}
