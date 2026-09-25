package com.ruwen.audioplayer.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Apple Podcasts 搜索（iTunes Search API）。
 *
 * 免鉴权、无需注册，官方地址 https://itunes.apple.com/search。
 * 关键参数：`media=podcast`（限定播客）、`entity=podcast`、`term`（关键词）、`limit`。
 *
 * 返回体：`{"resultCount": n, "results": [...]}`，
 * 其中播客条目用 `collectionName`（节目名）、`artistName`（作者）、
 * `feedUrl`（RSS 地址）、`artworkUrl600`（封面）。
 */
class ApplePodcastsSearcher(private val httpClient: OkHttpClient) {

    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<PodcastSearchResult> {
        val url = "https://itunes.apple.com/search" +
            "?media=podcast&entity=podcast&term=${urlEncode(query)}&limit=$limit"
        val request = Request.Builder().url(url).get().build()
        val body = httpClient.executeForBody(request)
        return parseResults(JSONObject(body).optJSONArray("results"))
    }

    private fun parseResults(array: org.json.JSONArray?): List<PodcastSearchResult> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            // 没有 feedUrl 的结果无法订阅，直接丢弃
            val feedUrl = item.optString("feedUrl").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            PodcastSearchResult(
                title = item.optString("collectionName").takeIf { it.isNotBlank() }
                    ?: item.optString("trackName").takeIf { it.isNotBlank() }
                    ?: feedUrl,
                author = item.optString("artistName").takeIf { it.isNotBlank() },
                feedUrl = feedUrl,
                imageUrl = item.optString("artworkUrl600").takeIf { it.isNotBlank() }
                    ?: item.optString("artworkUrl100").takeIf { it.isNotBlank() },
                description = null
            )
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val DEFAULT_LIMIT = 25
    }
}
