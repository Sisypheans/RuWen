package com.ruwen.audioplayer.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 从音频文件里一次性读出的元数据。
 *
 * 一次 [MediaMetadataRetriever] 打开就把时长 / 标题 / 内嵌封面全取出来：
 * 这个 API 每次都要打开文件并解析头部，是慢操作，逐个字段分别调用等于重复打开多次。
 */
data class AudioMeta(
    val duration: Long,
    /** 内嵌标题（ID3 TIT2 / M4A ©nam）；读不到或为空时是 null */
    val title: String?,
    /** 内嵌封面的原始字节（ID3 APIC / M4A covr）；没有时是 null */
    val embeddedPicture: ByteArray?
)

object AudioUtils {

    private const val TAG = "AudioUtils"

    fun getAudioDuration(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0
        } catch (e: Exception) {
            // 单个文件读不出时长不致命：返回 0 由调用方降级（不再 printStackTrace）
            Log.w(TAG, "读取音频时长失败：$uri", e)
            0
        } finally {
            // release() 失败无补救手段，且不影响后续流程，故只记 debug
            runCatching { retriever.release() }.onFailure { Log.d(TAG, "释放 retriever 失败", it) }
        }
    }

    /**
     * 读取音频自带元数据（导入时调用，必须在 IO 线程）。
     *
     * 失败（文件损坏 / 不支持的格式）时返回全空的 [AudioMeta]，
     * 由调用方按兜底链降级，不让单条坏音频中断整批导入。
     */
    fun readAudioMeta(context: Context, uri: Uri): AudioMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0
            // 有些文件的标签是空白或纯空格，trim 后为空要当成「没读到」
            val title = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            AudioMeta(
                duration = duration,
                title = title,
                embeddedPicture = retriever.embeddedPicture
            )
        } catch (e: Exception) {
            Log.w(TAG, "读取音频元数据失败：$uri（按无元数据处理）", e)
            AudioMeta(duration = 0, title = null, embeddedPicture = null)
        } finally {
            runCatching { retriever.release() }.onFailure { Log.d(TAG, "释放 retriever 失败", it) }
        }
    }

    fun getAudioTitle(filePath: String): String {
        val file = File(filePath)
        val name = file.name
        return if (name.contains(".")) {
            name.substringBeforeLast(".")
        } else {
            name
        }
    }

    fun getFileSize(filePath: String): Long = runCatching { File(filePath).length() }
        .onFailure { Log.d(TAG, "读取文件大小失败：$filePath", it) }
        .getOrDefault(0L)

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
