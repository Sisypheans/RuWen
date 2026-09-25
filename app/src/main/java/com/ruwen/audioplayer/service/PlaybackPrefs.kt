package com.ruwen.audioplayer.service

import android.content.Context
import android.content.SharedPreferences
// RepeatMode 是 PlaybackService 的内部枚举（PlaybackService.RepeatMode），必须显式导入
import com.ruwen.audioplayer.service.PlaybackService.RepeatMode

/**
 * 播放相关偏好设置的持久化封装。
 *
 *  - 循环模式（RepeatMode）以「枚举 name 字符串」存储，而非 ordinal（见 saveRepeatMode）：
 *    以后若调整枚举声明顺序，ordinal 会变，读旧值就会错配；存 name 则完全免疫顺序变化。
 *  - 上次播放的音频（LastPlayed）以 playlistId + audioId + index + title 存储，供冷启动后
 *    迷你播放栏显示「记忆态」。存 audioId 是因为列表可能被增删/重排，冷启动时要用 audioId
 *    在查出的列表里 re-find 出正确的 index，单纯存 index 会指错歌。
 */
class PlaybackPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取循环模式；无记录、记录为空或不是合法枚举名时返回 NONE */
    fun getRepeatMode(): RepeatMode {
        val name = prefs.getString(KEY_REPEAT_MODE, null)
        return if (name != null) {
            runCatching { RepeatMode.valueOf(name) }.getOrDefault(RepeatMode.NONE)
        } else {
            RepeatMode.NONE
        }
    }

    /** 立即落盘循环模式（存枚举 name） */
    fun saveRepeatMode(mode: RepeatMode) {
        prefs.edit().putString(KEY_REPEAT_MODE, mode.name).apply()
    }

    /** 立即落盘「上次播放」快照（封面路径可空：音频没有封面时存 null，迷你栏回退默认图标） */
    fun saveLastPlayed(playlistId: Long, audioId: Long, index: Int, title: String, coverPath: String?) {
        prefs.edit()
            .putLong(KEY_LAST_PLAYED_PLAYLIST_ID, playlistId)
            .putLong(KEY_LAST_PLAYED_AUDIO_ID, audioId)
            .putInt(KEY_LAST_PLAYED_INDEX, index)
            .putString(KEY_LAST_PLAYED_TITLE, title)
            .putString(KEY_LAST_PLAYED_COVER, coverPath)
            .apply()
    }

    /**
     * 读取「上次播放」快照。playlistId / audioId / title 齐全且 id 均 > 0 才算有效，否则返回 null
     * （首次启动、或从未播放过时无有效记录）。coverPath 允许缺失（旧版本快照没有该字段）。
     */
    fun getLastPlayed(): LastPlayed? {
        val playlistId = prefs.getLong(KEY_LAST_PLAYED_PLAYLIST_ID, 0L)
        val audioId = prefs.getLong(KEY_LAST_PLAYED_AUDIO_ID, 0L)
        val index = prefs.getInt(KEY_LAST_PLAYED_INDEX, 0)
        val title = prefs.getString(KEY_LAST_PLAYED_TITLE, null)
        if (playlistId <= 0L || audioId <= 0L || title == null) return null
        return LastPlayed(playlistId, audioId, index, title, prefs.getString(KEY_LAST_PLAYED_COVER, null))
    }

    /** 清除「上次播放」快照（列表被删等场景可调用） */
    fun clearLastPlayed() {
        prefs.edit()
            .remove(KEY_LAST_PLAYED_PLAYLIST_ID)
            .remove(KEY_LAST_PLAYED_AUDIO_ID)
            .remove(KEY_LAST_PLAYED_INDEX)
            .remove(KEY_LAST_PLAYED_TITLE)
            .remove(KEY_LAST_PLAYED_COVER)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ruwen_playback_settings"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_LAST_PLAYED_PLAYLIST_ID = "last_played_playlist_id"
        private const val KEY_LAST_PLAYED_AUDIO_ID = "last_played_audio_id"
        private const val KEY_LAST_PLAYED_INDEX = "last_played_index"
        private const val KEY_LAST_PLAYED_TITLE = "last_played_title"
        private const val KEY_LAST_PLAYED_COVER = "last_played_cover_path"
    }
}

/** 「上次播放」的音频快照，用于冷启动后迷你播放栏的「记忆态」展示 */
data class LastPlayed(
    val playlistId: Long,
    val audioId: Long,
    val index: Int,
    val title: String,
    /** 封面文件路径；旧版本快照 / 无封面音频为 null，展示时回退默认图标 */
    val coverPath: String? = null
)
