package com.ruwen.audioplayer.data.repository

import com.ruwen.audioplayer.data.remote.ApplePodcastsSearcher
import com.ruwen.audioplayer.data.remote.PodcastIndexSearcher
import com.ruwen.audioplayer.data.remote.PodcastSearchResult
import com.ruwen.audioplayer.data.remote.SearchSource
import android.content.Context
import okhttp3.OkHttpClient

/**
 * 在线播客搜索。
 *
 * 只做「查」，不做「订」——订阅统一交给 [PodcastRepository.subscribe]，
 * 这样无论是搜索结果、RSS 地址还是 OPML 导入，落库路径都是同一条。
 *
 * 两个源共用一个 OkHttpClient：复用连接池，避免每次搜索新建客户端。
 */
class PodcastSearchRepository(
    private val context: Context
) {

    private val httpClient = OkHttpClient()
    private val appleSearcher = ApplePodcastsSearcher(httpClient)
    private val podcastIndexSearcher = PodcastIndexSearcher(httpClient, context)

    /**
     * @throws Exception 网络失败、接口报错或凭据缺失（Podcast Index）
     */
    suspend fun search(source: SearchSource, query: String): List<PodcastSearchResult> =
        when (source) {
            SearchSource.APPLE -> appleSearcher.search(query)
            SearchSource.PODCAST_INDEX -> podcastIndexSearcher.search(query)
        }
}
