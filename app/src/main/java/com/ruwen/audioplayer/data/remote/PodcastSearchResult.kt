package com.ruwen.audioplayer.data.remote

/** 在线搜索来源。对应搜索页上的「Apple Podcasts」与「博客索引」两个入口。 */
enum class SearchSource {
    APPLE,
    PODCAST_INDEX
}

/**
 * 在线搜索结果（尚未订阅）。
 *
 * 与本地实体 [com.ruwen.audioplayer.data.entity.Podcast] 区分开：
 * 这里只有订阅所必需的最小信息，落库由 PodcastRepository.subscribe 完成。
 */
data class PodcastSearchResult(
    val title: String,
    val author: String?,
    /** RSS 地址；为空的结果无法订阅，解析时应直接丢弃 */
    val feedUrl: String,
    val imageUrl: String?,
    val description: String?
)
