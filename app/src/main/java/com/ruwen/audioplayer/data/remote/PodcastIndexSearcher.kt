package com.ruwen.audioplayer.data.remote

import android.content.Context
import com.ruwen.audioplayer.BuildConfig
import com.ruwen.audioplayer.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Podcast Index 搜索（api.podcastindex.org）。
 *
 * 鉴权（官方要求，四个请求头缺一不可）：
 *  - `X-Auth-Key`      = API Key
 *  - `X-Auth-Date`     = 当前 Unix 时间（秒）
 *  - `Authorization`   = sha1(Key + Secret + Date)
 *  - `User-Agent`      = 客户端标识（官方要求带，缺失会被拒）
 *
 * 凭据经 `local.properties` → `BuildConfig` 注入，不硬编码在源码里。
 */
class PodcastIndexSearcher(private val httpClient: OkHttpClient, private val context: Context) {

    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<PodcastSearchResult> {
        val key = BuildConfig.PODCAST_INDEX_API_KEY
        val secret = BuildConfig.PODCAST_INDEX_API_SECRET
        // 凭据缺失时给出可行动的错误，而不是让请求以 401 失败、用户只看到"搜索失败"
        if (key.isBlank() || secret.isBlank()) {
            error(context.getString(R.string.err_podcastindex_creds))
        }

        val date = (System.currentTimeMillis() / 1000).toString()
        val auth = sha1(key + secret + date)
        val url = "$BASE_URL/search/byterm?q=${urlEncode(query)}&max=$limit"

        val request = Request.Builder()
            .url(url)
            .header("X-Auth-Key", key)
            .header("X-Auth-Date", date)
            .header("Authorization", auth)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val body = httpClient.executeForBody(request)
        val root = JSONObject(body)
        // 接口以 status 字段表示成功（字符串 "true"），不是 HTTP 码
        if (root.optString("status") != "true") {
            error(root.optString("description").takeIf { it.isNotBlank() }
                ?: context.getString(R.string.err_search_failed))
        }
        return parseFeeds(root.optJSONArray("feeds"))
    }

    private fun parseFeeds(array: JSONArray?): List<PodcastSearchResult> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val feed = array.optJSONObject(index) ?: return@mapNotNull null
            val feedUrl = feed.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PodcastSearchResult(
                title = feed.optString("title").takeIf { it.isNotBlank() } ?: feedUrl,
                author = feed.optString("author").takeIf { it.isNotBlank() },
                feedUrl = feedUrl,
                imageUrl = feed.optString("image").takeIf { it.isNotBlank() }
                    ?: feed.optString("artwork").takeIf { it.isNotBlank() },
                description = feed.optString("description").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val BASE_URL = "https://api.podcastindex.org/api/1.0"
        private const val USER_AGENT = "RuWen/1.0"
        private const val DEFAULT_LIMIT = 25
    }
}
