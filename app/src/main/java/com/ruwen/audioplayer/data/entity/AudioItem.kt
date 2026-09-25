package com.ruwen.audioplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audio_items",
    foreignKeys = [ForeignKey(
        entity = Playlist::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("playlistId")]
)
data class AudioItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val playlistId: Long,

    /**
     * 音频名称（**身份字段，不要改**）。
     *
     * 它参与 [SubtitleIdentity] 的共享 key（`标题|时长|文件大小`），字幕文件名
     * `shared_<md5(key)>.srt` 就是由它派生的。改了它，已生成字幕虽然仍能播
     * （subtitlePath 还指着自己的文件），但「按身份找到它」的索引会断。
     * 界面上要展示的新标题请用 [displayTitle]。
     */
    val title: String,
    val filePath: String,
    val duration: Long = 0,
    val fileSize: Long = 0,
    val position: Int = 0,

    /**
     * 展示用的音频标题：从音频文件自身的内嵌标签读取（兜底链见 AudioUtils）。
     *
     * null 表示「还没采集过」——DB v7 迁移上来的旧行就是这个值，
     * 界面回退显示 [title]；用户重新添加一次音频即可补上。
     */
    val displayTitle: String? = null,

    /**
     * 封面图片在 App 私有目录里的路径。
     *
     * 按 filePath 共享（同一音频加进多个播放列表时指向同一份文件），
     * 与字幕按身份共享不同——封面不依赖标题，因此标题变化不会让它失效。
     * null 表示没有可用封面，界面显示占位图。
     */
    val coverPath: String? = null,

    /**
     * 封面的**远程来源地址**（播客单集导入时 = 单集图 ?: 播客封面）。
     *
     * 存在的意义：导入那一刻如果没网，`downloadCover` 会失败、[coverPath] 就永远是 null，
     * 之后这个条目一直是占位图——除非用户重新添加一次。记住来源后，任何时候
     * （刷新播客时）都能按这个 URL 把封面补回来，不必再依赖 episode 行是否还在
     * （退订播客的单集行会被删掉，靠 filePath 反查就查不到了）。
     *
     * null = 没有远程来源（本地文件导入、或本来就没有封面），无从补起。
     */
    val coverSourceUrl: String? = null,

    val subtitlePath: String? = null,
    val subtitleStatus: SubtitleStatus = SubtitleStatus.NOT_GENERATED,

    /**
     * 生成失败原因（面向用户的文案）。
     *
     * 只有**离散状态**落库：字幕生成进度是连续变化值（解码阶段每 1% 一次），
     * 早前把它写进这张表，导致所有观察 audio_items 的列表被反复刷新、连点击都被吞掉。
     * 进度现在放在 [com.ruwen.audioplayer.data.SubtitleProgressStore]（内存 StateFlow），
     * 因此 DB v9 起不再有 subtitleProgress / subtitleEtaMillis 两列。
     */
    val subtitleError: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * 界面上应该显示的标题。
 *
 * 优先用从音频自身读到的 [AudioItem.displayTitle]；DB v7 之前导入的旧行没有采集过
 * 这个字段（为 null），回退显示 [AudioItem.title]。所有展示标题的地方都用它，
 * 避免各处各写一遍 `displayTitle ?: title` 而漏掉某处。
 */
val AudioItem.displayName: String
    get() = displayTitle?.takeIf { it.isNotBlank() } ?: title

enum class SubtitleStatus {
    NOT_GENERATED,
    GENERATING,
    GENERATED,
    FAILED,
    /** 已入队、尚未开始识别（FIFO 队列里排队等待）。放在末尾以兼容已存 DB 序号 */
    QUEUED
}

/**
 * 导入一条音频时携带的信息。
 *
 * 之所以要有这个类而不是只传 (title, uri)：字幕共享要靠
 * [SubtitleIdentity]（名称 + 时长 + 文件大小）判定「同一个音频」，
 * 因此导入时必须一并采集 duration 与 fileSize，否则这两个字段会一直是 0，
 * 共享判定就退化成「仅按名称」，失去防止同名音频误共享的能力。
 */
data class AudioImport(
    val title: String,
    val uri: String,
    val duration: Long = 0,
    val fileSize: Long = 0,
    /**
     * 展示标题：从音频自身的内嵌标签读到，读不到时由调用方按兜底链降级。
     * null 表示「这条没采集到」，界面会回退显示 [title]。
     */
    val displayTitle: String? = null,

    /**
     * 封面在 App 私有目录里的路径（已由调用方落盘）。
     * 内嵌封面取不到、播客网络图也下载失败时是 null，界面显示占位图。
     */
    val coverPath: String? = null,

    /**
     * 封面的远程来源（见 [AudioItem.coverSourceUrl]）。
     *
     * **只要调用方给了远程兜底地址就填上**，与这次有没有下载成功无关——
     * 失败的那次正是最需要记住来源的场景（下次刷新时好补回来）。
     */
    val coverSourceUrl: String? = null
)
