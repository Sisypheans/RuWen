package com.ruwen.audioplayer.data.entity

import com.ruwen.audioplayer.util.md5

/**
 * 字幕共享标识：用「音频名称 + 时长 + 文件大小」三者共同判定「同一个音频」。
 *
 * 背景：同一个音频被导入到多个播放列表时，数据库里是多条相互独立的 [AudioItem] 行
 * （id / playlistId 都不同），但名称、时长、文件大小一致。字幕本质上属于「这个音频」，
 * 而不是「某一条列表记录」，所以用这三者拼成的 key 来让它们共享同一份字幕文件。
 *
 * 为什么不只用名称：不同内容却同名的音频（例如都叫「朗读.mp3」）会被误判为同一个音频，
 * 导致字幕串台。加上时长与文件大小后，几乎不可能误命中。
 *
 * 只影响「改功能之后新生成」的字幕：历史字幕文件是按 audioId 命名的（<id>.srt），
 * 与本类的 shared_<hash>.srt 互不干扰，因此无需迁移旧数据。
 */
object SubtitleIdentity {

    /** 共享 key：名称 + 时长 + 文件大小 */
    fun keyOf(title: String, duration: Long, fileSize: Long): String =
        "$title|$duration|$fileSize"

    fun keyOf(item: AudioItem): String =
        keyOf(item.title, item.duration, item.fileSize)

    /**
     * 共享字幕文件名（对同一音频稳定不变）。
     * 取 MD5 是为了避免音频名里的非法字符或超长名称导致文件不可用。
     */
    fun fileNameOf(title: String, duration: Long, fileSize: Long): String =
        "shared_${md5(keyOf(title, duration, fileSize))}.srt"

    fun fileNameOf(item: AudioItem): String =
        fileNameOf(item.title, item.duration, item.fileSize)
}
