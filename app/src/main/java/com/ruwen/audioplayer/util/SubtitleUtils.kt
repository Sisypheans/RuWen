package com.ruwen.audioplayer.util

import android.content.Context
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.data.entity.SubtitleCue
import java.io.File
import java.util.concurrent.TimeUnit

object SubtitleUtils {

    fun parseSrt(file: File): List<SubtitleCue> {
        if (!file.exists()) return emptyList()

        val cues = mutableListOf<SubtitleCue>()
        val content = file.readText()
        val blocks = content.trim().split("\\n\\n+".toRegex())

        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.size >= 3) {
                val index = lines[0].trim().toIntOrNull() ?: continue
                val timeLine = lines[1].trim()
                val text = lines.drop(2).joinToString("\n").trim()

                val times = timeLine.split(" --> ")
                if (times.size == 2) {
                    val startTime = parseSrtTime(times[0])
                    val endTime = parseSrtTime(times[1])
                    cues.add(SubtitleCue(index, startTime, endTime, text))
                }
            }
        }
        return cues
    }

    private fun parseSrtTime(timeStr: String): Long {
        // Format: 00:00:00,000
        val parts = timeStr.replace(",", ".").split(":")
        if (parts.size == 3) {
            val hours = parts[0].toLongOrNull() ?: 0
            val minutes = parts[1].toLongOrNull() ?: 0
            val secondsParts = parts[2].split(".")
            val seconds = secondsParts[0].toLongOrNull() ?: 0
            val millis = if (secondsParts.size > 1) {
                val msStr = secondsParts[1].padEnd(3, '0').take(3)
                msStr.toLongOrNull() ?: 0
            } else 0

            return TimeUnit.HOURS.toMillis(hours) +
                    TimeUnit.MINUTES.toMillis(minutes) +
                    TimeUnit.SECONDS.toMillis(seconds) +
                    millis
        }
        return 0
    }

    fun formatSrtTime(milliseconds: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60
        val millis = milliseconds % 1000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    fun writeSrt(cues: List<SubtitleCue>, file: File) {
        val sb = StringBuilder()
        for ((i, cue) in cues.withIndex()) {
            sb.append("${i + 1}\n")
            sb.append("${formatSrtTime(cue.startTime)} --> ${formatSrtTime(cue.endTime)}\n")
            sb.append("${cue.text}\n\n")
        }
        file.writeText(sb.toString())
    }

    fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    fun formatDurationLong(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    /**
     * 把「预计剩余毫秒」格式化成对用户友好的文案。
     *
     * 端侧 whisper 在手机上通常是「分钟级到小时级」的量级，所以只保留
     * 到分钟；超过 1 小时才显示小时。
     *
     * 需要 Context 取字符串资源：这些文案原先硬编码在代码里，绕过了本地化。
     *
     * @param etaMillis 剩余毫秒；<=0 返回 "--"
     */
    fun formatEta(context: Context, etaMillis: Long): String {
        if (etaMillis <= 0L) return "--"
        val totalMinutes = (etaMillis + 30_000L) / 60_000L // 四舍五入到分钟
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L -> context.getString(R.string.eta_hours_minutes, hours, minutes)
            minutes > 0L -> context.getString(R.string.eta_minutes, minutes)
            else -> context.getString(R.string.eta_less_than_minute)
        }
    }

    fun findCurrentCue(cues: List<SubtitleCue>, positionMs: Long): SubtitleCue? {
        // 先按开始时间排序，避免字幕乱序时提前 break 导致漏匹配
        val sorted = if (cues.size > 1) cues.sortedBy { it.startTime } else cues
        for (cue in sorted) {
            if (positionMs < cue.startTime) {
                // 已越过所有候选（已排序），不可能再有匹配
                return null
            }
            if (positionMs <= cue.endTime) {
                return cue
            }
        }
        return null
    }
}
