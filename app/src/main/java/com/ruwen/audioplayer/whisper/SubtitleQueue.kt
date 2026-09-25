package com.ruwen.audioplayer.whisper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 字幕生成 FIFO 队列（SharedPreferences 持久化）。
 *
 * 所有「生成字幕」请求先写进这里，再由唯一的 [SubtitleGenerationWorker] 循环抽干，
 * 保证严格按入队顺序串行生成，不会并发。
 *
 * - 持久化：用 "subtitle_queue" 这个 SharedPreferences 存一个 JSON 数组字符串，
 *   元素为 `{"id":Long,"uri":String}`。进程被杀后重启，Worker 重新跑时能接着处理。
 * - 去重：enqueue 时若队列里已有相同 audioId 直接返回 false。
 * - 线程安全：所有读写用 @Synchronized 串行化；prefs 为空/空串/损坏 JSON 时返回空队列且不抛异常。
 * - currentAudioId 仅内存（@Volatile），标记正在被 Worker 处理的是哪一条。
 */
object SubtitleQueue {

    private const val PREFS_NAME = "subtitle_queue"
    private const val KEY_QUEUE = "queue"

    data class Entry(val audioId: Long, val uri: String)

    /** 仅内存，标记当前正在处理（Worker 已 peek 但尚未 pop）的音频 id */
    @Volatile
    var currentAudioId: Long? = null

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(context: Context, audioId: Long, uri: String): Boolean {
        val list = readList(context)
        if (list.any { it.audioId == audioId }) return false
        list.add(Entry(audioId, uri))
        writeList(context, list)
        return true
    }

    @Synchronized
    fun peek(context: Context): Entry? = readList(context).firstOrNull()

    @Synchronized
    fun pop(context: Context): Entry? {
        val list = readList(context)
        if (list.isEmpty()) return null
        val head = list.removeAt(0)
        writeList(context, list)
        return head
    }

    @Synchronized
    fun remove(context: Context, audioId: Long) {
        val list = readList(context).toMutableList()
        list.removeAll { it.audioId == audioId }
        writeList(context, list)
    }

    @Synchronized
    fun size(context: Context): Int = readList(context).size

    private fun readList(context: Context): MutableList<Entry> {
        val raw = prefs(context).getString(KEY_QUEUE, null)
        if (raw.isNullOrBlank()) return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optLong("id", -1L)
                val uri = obj.optString("uri", "")
                if (id != -1L && uri.isNotEmpty()) out.add(Entry(id, uri))
            }
            out
        } catch (e: Exception) {
            // 损坏数据：返回空队列，不让上游崩溃
            mutableListOf()
        }
    }

    private fun writeList(context: Context, list: List<Entry>) {
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(JSONObject().apply {
                    put("id", e.audioId)
                    put("uri", e.uri)
                })
            }
            prefs(context).edit().putString(KEY_QUEUE, arr.toString()).apply()
        } catch (e: Exception) {
            // 写入失败不抛，避免影响上层
        }
    }
}
