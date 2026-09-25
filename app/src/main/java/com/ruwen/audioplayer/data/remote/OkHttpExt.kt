package com.ruwen.audioplayer.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 同步执行一次 GET 并返回响应体字符串。
 *
 * 统一收口三件事：切到 IO 线程、非 2xx 直接抛错（带 HTTP 码，便于定位）、关闭 response。
 * 各处搜索/下载都走它，避免每个调用点各写一遍而漏掉关闭。
 */
internal suspend fun OkHttpClient.executeForBody(request: Request): String =
    withContext(Dispatchers.IO) {
        newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body.string()
        }
    }
