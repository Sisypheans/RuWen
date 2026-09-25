package com.ruwen.audioplayer.data.repository

import android.content.Context
import android.util.Log
import com.ruwen.audioplayer.data.dao.AudioItemDao
import com.ruwen.audioplayer.data.dao.PodcastDao
import com.ruwen.audioplayer.util.CoverStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 缺失封面的**事后补下载**。
 *
 * 背景：播客单集导入播放列表时，音频没有内嵌封面就走远程兜底（`episode.imageUrl ?: podcast.imageUrl`）。
 * 那一刻如果没网（或图床不可达），`coverPath` 就永远是 null，条目一直显示占位图——
 * 而用户此后没有任何途径把它补回来，只能删掉重新导入一次。
 *
 * 补的时机由调用方决定（目前是播客详情页的刷新按钮）。补的依据按顺序是：
 *  1. `AudioItem.coverSourceUrl`（v11 起导入时记录，最可靠，且**不依赖 episode 行是否还在**）；
 *  2. 历史条目没有这个字段 → 用 `audio_items.filePath == episodes.localPath` 反查一次，
 *     查到就顺手把字段回填（下次不必再反查）。
 *
 * 两条纪律：
 *  - **静默**：补失败（没网 / 图床不可达）一律吞掉，绝不影响调用方的结果展示；
 *  - **控并发**：可能一次补几十上百张，固定并发度 4，不把连接池和带宽打满。
 */
class CoverBackfill(
    private val audioItemDao: AudioItemDao,
    private val podcastDao: PodcastDao,
    private val httpClient: OkHttpClient
) {

    /** @return 本次补上了几张封面 */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun backfill(context: Context): Int = withContext(Dispatchers.IO) {
        val missing = audioItemDao.getAudioItemsMissingCover()
        if (missing.isEmpty()) return@withContext 0

        // 同一音频可能在多个播放列表里有多行 → 按 filePath 去重，一张图只下一次
        val byPath = missing.groupBy { it.filePath }

        // 1) 优先用导入时记下的来源
        val urlByPath = HashMap<String, String>()
        val pathWithKnownUrl = HashSet<String>()
        byPath.forEach { (path, items) ->
            val known = items.firstOrNull { !it.coverSourceUrl.isNullOrBlank() }?.coverSourceUrl
            if (!known.isNullOrBlank()) {
                urlByPath[path] = known
                pathWithKnownUrl += path
            }
        }

        // 2) 剩下的用 filePath ↔ episode.localPath 反查（v11 之前导入的历史条目）
        val needLookup = byPath.keys.filter { it !in pathWithKnownUrl }
        if (needLookup.isNotEmpty()) {
            urlByPath += lookupUrlsFromEpisodes(needLookup)
        }
        if (urlByPath.isEmpty()) return@withContext 0

        val filled = coroutineScope {
            val limited = Dispatchers.IO.limitedParallelism(MAX_CONCURRENCY)
            urlByPath.entries.map { (path, url) ->
                async(limited) {
                    val saved = runCatching { downloadAndSave(context, path, url) }.getOrNull()
                    if (saved == null) {
                        0
                    } else {
                        audioItemDao.updateCoverPathByFilePath(path, saved)
                        // 来源是这次反查出来的 → 回填字段，下次直接可用
                        if (path !in pathWithKnownUrl) {
                            audioItemDao.updateCoverSourceUrlByFilePath(path, url)
                        }
                        1
                    }
                }
            }.awaitAll().sum()
        }

        Log.i(TAG, "封面补下载：缺失 ${byPath.size} 条，成功补上 $filled 张")
        filled
    }

    /**
     * 按本地音频路径反查单集封面地址。
     * 单集自己没图时回退播客封面——与导入时的兜底链（`episode.imageUrl ?: podcast.imageUrl`）保持一致。
     */
    private suspend fun lookupUrlsFromEpisodes(paths: List<String>): Map<String, String> {
        val episodes = podcastDao.getEpisodesByLocalPaths(paths)
        if (episodes.isEmpty()) return emptyMap()

        val podcastImages = podcastDao.getPodcastsByIds(episodes.map { it.podcastId }.distinct())
            .associate { it.id to it.imageUrl }

        val result = HashMap<String, String>()
        for (episode in episodes) {
            val path = episode.localPath?.takeIf { it.isNotBlank() } ?: continue
            val url = episode.imageUrl?.takeIf { it.isNotBlank() }
                ?: podcastImages[episode.podcastId]?.takeIf { it.isNotBlank() }
                ?: continue
            result[path] = url
        }
        return result
    }

    private fun downloadAndSave(context: Context, filePath: String, url: String): String? {
        val request = Request.Builder().url(url).build()
        val bytes = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.bytes()
        }
        return CoverStore.save(context, filePath, bytes)
    }

    companion object {
        private const val TAG = "CoverBackfill"
        private const val MAX_CONCURRENCY = 4
    }
}
