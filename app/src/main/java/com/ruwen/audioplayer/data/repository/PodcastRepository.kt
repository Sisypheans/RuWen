package com.ruwen.audioplayer.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.prof18.rssparser.RssParser
import com.prof18.rssparser.model.RssChannel
import com.prof18.rssparser.model.RssItem
import com.ruwen.audioplayer.data.dao.PodcastDao
import com.ruwen.audioplayer.data.db.TransactionRunner
import com.ruwen.audioplayer.data.entity.DownloadStatus
import com.ruwen.audioplayer.data.entity.Episode
import com.ruwen.audioplayer.data.entity.Podcast
import com.ruwen.audioplayer.R
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 播客数据仓库：RSS 拉取 / 解析 / 落库，以及订阅与单集状态维护。
 *
 * 边界：本仓库只负责"订阅 + 元数据 + 下载状态"，**不负责播放**。
 * 下载完成的单集由用户在播放列表详情页"从播客添加"引入播放列表。
 *
 * RSS 解析使用 com.prof18.rssparser（Apache-2.0）。字段以官方 README 与
 * model 源码为准：RssItem.guid/pubDate/audio/rawEnclosure/itunesItemData、
 * RssChannel.image/itunesChannelData。
 */
class PodcastRepository(
    private val podcastDao: PodcastDao,
    /** 多步写库的原子化执行器（见 TransactionRunner 注释） */
    private val tx: TransactionRunner,
    private val context: Context
) {

    private val rssParser = RssParser()

    // ------------------------------------------------------------------
    //  查询
    // ------------------------------------------------------------------

    fun observePodcasts(): LiveData<List<Podcast>> = podcastDao.observePodcasts()

    suspend fun getPodcasts(): List<Podcast> = podcastDao.getPodcasts()

    suspend fun getPodcastById(id: Long): Podcast? = podcastDao.getPodcastById(id)

    /**
     * 保存该播客的单集排序（**每个播客各一套**，写进 podcasts 表）。
     * 之前是全局 SharedPreferences，用户要求按播客独立保存。
     */
    suspend fun updateSortOrder(podcastId: Long, byTitle: Boolean, ascending: Boolean) {
        podcastDao.updateSortOrder(podcastId, byTitle, ascending)
    }

    suspend fun getPodcastByFeedUrl(feedUrl: String): Podcast? =
        podcastDao.getPodcastByFeedUrl(feedUrl)

    fun observeEpisodes(podcastId: Long): LiveData<List<Episode>> =
        podcastDao.observeEpisodes(podcastId)

    suspend fun getEpisodes(podcastId: Long): List<Episode> = podcastDao.getEpisodes(podcastId)

    suspend fun getEpisodesByIds(ids: List<Long>): List<Episode> =
        podcastDao.getEpisodesByIds(ids)

    /** 已下载的单集，供播放列表"从播客添加"勾选 */
    suspend fun getDownloadedEpisodes(podcastId: Long): List<Episode> =
        podcastDao.getDownloadedEpisodes(podcastId, DownloadStatus.DOWNLOADED)

    // ------------------------------------------------------------------
    //  订阅 / 刷新
    // ------------------------------------------------------------------

    /**
     * 订阅播客：拉取 RSS → 解析 → 落库（含首轮单集）。
     * 同一 RSS 地址已订阅过时直接复用，不重复插入。
     */
    suspend fun subscribe(feedUrl: String): Result<Podcast> = runCatching {
        val channel = rssParser.getRssChannel(feedUrl)
        // 播客行 + 首轮单集 + 刷新时间必须一起成功，否则会留下"有播客没单集"的半成品
        tx.runInTransaction {
            val existing = podcastDao.getPodcastByFeedUrl(feedUrl)
            val podcastId = existing?.id
                ?: podcastDao.insertPodcast(channel.toPodcast(feedUrl)).also {
                    if (it <= 0) error(context.getString(R.string.err_podcast_save_failed))
                }
            saveNewEpisodes(podcastId, channel)
            podcastDao.updateRefreshedAt(podcastId, System.currentTimeMillis())
        }
        podcastDao.getPodcastByFeedUrl(feedUrl) ?: error(context.getString(R.string.err_podcast_save_failed))
    }

    /**
     * 刷新：重新拉取 RSS，只写入新增单集，并**顺带修正已有单集的封面**。
     * @return 新增的单集数量
     */
    suspend fun refresh(podcastId: Long): Result<Int> = runCatching {
        val podcast = podcastDao.getPodcastById(podcastId) ?: error(context.getString(R.string.err_podcast_not_found))
        val channel = rssParser.getRssChannel(podcast.feedUrl)
        val newCount = saveNewEpisodes(podcastId, channel)
        fixEpisodeImages(podcastId, channel)
        podcastDao.updateRefreshedAt(podcastId, System.currentTimeMillis())
        newCount
    }

    /**
     * 修正已入库单集的封面地址。
     *
     * 为什么需要：[saveNewEpisodes] 用的是 INSERT IGNORE，**已存在的行一个字段都不会更新**。
     * 早期版本从 `RssItem.image` 取封面，而那是个脏字段（见 [asImageUrlOrNull]），
     * 部分源会把网页链接当成封面存进来。不修的话，这些行永远是错的——
     * 用户除了退订重订没有任何补救途径。
     *
     * 只改 `imageUrl` 一列：**绝不**动 `localPath` / `downloadStatus`，
     * 否则刷新一次就会把已下载的单集变成「未下载」。
     */
    private suspend fun fixEpisodeImages(podcastId: Long, channel: RssChannel) {
        val parsed = channel.items.mapNotNull { it.toEpisode(podcastId) }
        if (parsed.isEmpty()) return
        val stored = podcastDao.getEpisodes(podcastId).associateBy { it.guid }
        val changed = parsed.filter { episode ->
            val current = stored[episode.guid] ?: return@filter false
            current.imageUrl != episode.imageUrl
        }
        if (changed.isEmpty()) return
        tx.runInTransaction {
            changed.forEach { episode ->
                podcastDao.updateEpisodeImageUrl(podcastId, episode.guid, episode.imageUrl)
            }
        }
    }

    /**
     * 写入单集。使用 IGNORE 而非 REPLACE：
     * REPLACE 会整行覆盖，把已下载单集的 localPath / downloadStatus 一起清掉，
     * 表现为"刷新一下下载就没了"。
     *
     * @return 新增数量（Room 对 IGNORE 掉的行返回 -1，故只统计 > 0 的）
     */
    private suspend fun saveNewEpisodes(podcastId: Long, channel: RssChannel): Int {
        val episodes = channel.items.mapNotNull { it.toEpisode(podcastId) }
        if (episodes.isEmpty()) return 0
        return podcastDao.insertEpisodes(episodes).count { it > 0 }
    }

    /**
     * 订阅前预览：拉取并解析 RSS，**只返回内存对象、不落库**
     * （AntennaPod 的 OnlineFeedViewActivity 也会先下载解析、并把 feed 落成"未订阅"态；
     * 我们的库没有"未订阅"状态，故更简单地不写库，等用户点订阅再走 [subscribe]）。
     *
     * 单集的 podcastId 填 0：预览用的单集不归属任何已存播客，仅用于展示。
     */
    suspend fun previewFeed(feedUrl: String): Result<PodcastPreview> = runCatching {
        val channel = rssParser.getRssChannel(feedUrl)
        val podcast = channel.toPodcast(feedUrl)
        PodcastPreview(
            title = podcast.title,
            author = podcast.author,
            description = podcast.description,
            imageUrl = podcast.imageUrl,
            feedUrl = feedUrl,
            link = podcast.link,
            episodes = channel.items.mapNotNull { it.toEpisode(0L) }
        )
    }

    // ------------------------------------------------------------------
    //  取消订阅 / 删除单集
    // ------------------------------------------------------------------

    /**
     * 删除单集的**下载**：把状态复位为「未下载」并返回要一并删除的本地文件路径
     * （从未下载过的不会出现在结果里）。文件删除交给调用方执行，避免数据层持有 Context。
     *
     * **必须保留单集记录**：早前这里直接删行，表现为「点了删除，这一集从单集列表里消失了」，
     * 是用户报的 bug。单集应当留在列表里、变回未下载（与 AntennaPod 一致），
     * 刷新 RSS 也只是把已有单集设为已存在、不会重复插入。
     */
    suspend fun clearEpisodeDownloads(ids: List<Long>): List<String> {
        val episodes = podcastDao.getEpisodesByIds(ids)
        val paths = episodes.mapNotNull { it.localPath }
        episodes.forEach { episode ->
            podcastDao.updateDownloadState(
                episode.id, DownloadStatus.NOT_DOWNLOADED, null, 0
            )
        }
        return paths
    }

    /**
     * 取消订阅：删除该播客及其全部单集（外键已配 CASCADE）。
     *
     * 只负责「算出该删哪些本地文件」，**删除动作由调用方执行**（文件 IO 不在事务里做）：
     *  - [UnsubscribeCleanup.audioPaths]：已下载的单集音频（连带播放列表条目、字幕、封面）；
     *  - [UnsubscribeCleanup.coverUrls]：播客封面与所有单集封面的远程 URL，
     *    用来清掉 Coil 的磁盘缓存——封面缓存从 cacheDir 挪到 filesDir 后是持久的，
     *    不清就会一直占着空间，属于「退订后仍留在机器上的数据」。
     */
    suspend fun unsubscribe(podcastId: Long): UnsubscribeCleanup = tx.runInTransaction {
        val episodes = podcastDao.getEpisodes(podcastId)
        val audioPaths = episodes.mapNotNull { it.localPath }
        val coverUrls = buildList {
            podcastDao.getPodcastById(podcastId)?.imageUrl
                ?.takeIf { it.isNotBlank() }?.let(::add)
            episodes.mapNotNull { it.imageUrl?.takeIf { it.isNotBlank() } }.forEach(::add)
        }
        podcastDao.deleteEpisodesOfPodcast(podcastId)
        podcastDao.deletePodcastById(podcastId)
        UnsubscribeCleanup(audioPaths = audioPaths, coverUrls = coverUrls)
    }

    // ------------------------------------------------------------------
    //  下载状态
    // ------------------------------------------------------------------

    suspend fun markDownloading(id: Long) =
        podcastDao.updateDownloadState(id, DownloadStatus.DOWNLOADING, null, 0)

    suspend fun markDownloaded(id: Long, path: String) =
        podcastDao.updateDownloadState(id, DownloadStatus.DOWNLOADED, path, System.currentTimeMillis())

    suspend fun markDownloadFailed(id: Long) =
        podcastDao.updateDownloadState(id, DownloadStatus.FAILED, null, 0)

    suspend fun markNotDownloaded(id: Long) =
        podcastDao.updateDownloadState(id, DownloadStatus.NOT_DOWNLOADED, null, 0)
}

/**
 * 取消订阅后需要清理的本地残留（由 [PodcastRepository.unsubscribe] 算出，调用方负责删）。
 */
data class UnsubscribeCleanup(
    /** 已下载的单集音频绝对路径 */
    val audioPaths: List<String>,
    /** 播客封面 + 各单集封面的远程 URL（用于清 Coil 磁盘缓存） */
    val coverUrls: List<String>
)

/**
 * 订阅前的播客预览（未落库）。字段与 [Podcast] 对齐，另带解析出的单集列表。
 */
data class PodcastPreview(
    val title: String,
    val author: String?,
    val description: String?,
    val imageUrl: String?,
    val feedUrl: String,
    val link: String?,
    val episodes: List<Episode>
)

// ----------------------------------------------------------------------
//  RSS -> 实体 转换
// ----------------------------------------------------------------------

private fun RssChannel.toPodcast(feedUrl: String): Podcast = Podcast(
    title = title?.takeIf { it.isNotBlank() } ?: feedUrl,
    author = itunesChannelData?.author,
    description = description ?: itunesChannelData?.summary,
    imageUrl = image?.url?.takeIf { it.isNotBlank() } ?: itunesChannelData?.image,
    feedUrl = feedUrl,
    link = link,
    addedAt = System.currentTimeMillis(),
    lastRefreshedAt = System.currentTimeMillis()
)

/**
 * 单集转换。没有音频地址的单集无法下载，直接丢弃。
 *
 * guid 必须非空（唯一索引依赖它）：源里没有 guid 时回退用音频地址，
 * 避免多条空 guid 撞唯一约束。
 */
private fun RssItem.toEpisode(podcastId: Long): Episode? {
    val audioUrl = audio?.takeIf { it.isNotBlank() }
        ?: rawEnclosure?.url?.takeIf { it.isNotBlank() }
        ?: return null

    return Episode(
        podcastId = podcastId,
        title = title?.takeIf { it.isNotBlank() } ?: audioUrl,
        description = description ?: content,
        pubDate = parsePubDate(pubDate),
        audioUrl = audioUrl,
        sizeBytes = rawEnclosure?.length?.toString()?.toLongOrNull() ?: 0L,
        durationSec = parseDurationSeconds(itunesItemData?.duration?.toString()),
        imageUrl = image.asImageUrlOrNull() ?: itunesItemData?.image.asImageUrlOrNull(),
        guid = guid?.takeIf { it.isNotBlank() } ?: audioUrl
    )
}

/**
 * 只接受「看起来像图片」的地址，否则返回 null。
 *
 * 为什么必须过滤：rssparser 的 `RssItem.image` 是**脏字段**——`<media:content>` 的 url
 * 与 item 的 `<link>` 文本都会被写进去（后者覆盖前者），且都不校验内容。
 * 实测某源最终把 `https://omny.fm/shows/xxx`（一个网页）当成单集封面，
 * Coil 拿到 HTML 必然解码失败，表现为「整列单集都没封面」。
 *
 * 用**扩展名白名单**而不是黑名单：坏值往往根本没有扩展名（网页链接），黑名单拦不住它。
 * 误杀的代价也可控——被过滤只是回退到下一个候选（`itunes:image`），
 * 不会比硬加载一个坏 URL 更差。
 *
 * 不放行 svg：Coil 默认不解码 SVG（需要额外解码器依赖），放行只是多一次失败。
 */
private val IMAGE_EXTENSIONS =
    setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif", "bmp")

private fun String?.asImageUrlOrNull(): String? {
    val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    // 先剥掉查询串与锚点：`image.jpg?t=123&size=Large` 的扩展名在 ? 之前
    val ext = raw.substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', "")
        .lowercase()
    return raw.takeIf { ext in IMAGE_EXTENSIONS }
}

/**
 * RSS 日期是字符串，且格式不统一（RFC 822 最常见，也有 ISO 8601）。
 * 逐个尝试，全失败时返回 0（UI 需兼容 0 的情况）。
 */
private fun parsePubDate(value: String?): Long {
    val raw = value?.takeIf { it.isNotBlank() } ?: return 0L
    val patterns = arrayOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd"
    )
    for (pattern in patterns) {
        try {
            val parsed = SimpleDateFormat(pattern, Locale.US).parse(raw)
            if (parsed != null) return parsed.time
        } catch (_: Exception) {
            // 换下一种格式
        }
    }
    return 0L
}

/**
 * itunes:duration 可能是纯秒数，也可能是 MM:SS / HH:MM:SS，统一转成秒。
 */
private fun parseDurationSeconds(value: String?): Long {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
    raw.toLongOrNull()?.let { return it }
    val parts = raw.split(":")
    return try {
        when (parts.size) {
            2 -> parts[0].toLong() * 60 + parts[1].toLong()
            3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
            else -> 0L
        }
    } catch (_: Exception) {
        0L
    }
}
