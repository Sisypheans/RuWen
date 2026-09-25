package com.ruwen.audioplayer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 音频封面存储。
 *
 * 封面文件按 **音频文件路径** 的 md5 命名（`cover_<md5>.jpg`），而不是按音频身份
 * （名称+时长+大小）：同一音频被加进多个播放列表时 filePath 相同，天然共用一份；
 * 且它不依赖标题，所以展示标题变化不会让封面失效。
 *
 * 只存 256px 的 JPEG：音频内嵌封面动辄上千像素，原样落盘既占空间又会在列表里
 * 反复解码大图，这里统一降到列表展示需要的尺寸。
 */
object CoverStore {

    private const val TAG = "CoverStore"
    private const val MAX_EDGE = 256
    private const val JPEG_QUALITY = 90

    /** 该音频对应的封面文件（不管存不存在，用于先查是否已有一份） */
    fun fileFor(context: Context, audioFilePath: String): File =
        File(StorageLayout.coversDir(context), "cover_${md5(audioFilePath)}.jpg")

    /**
     * 保存封面（内存字节 → 本地文件）。
     * @return 成功返回绝对路径；图片无法解码时返回 null（调用方据此降级到占位图）
     */
    fun save(context: Context, audioFilePath: String, bytes: ByteArray): String? {
        val dir = StorageLayout.coversDir(context)

        val bitmap = decodeSampled(bytes) ?: return null
        val target = fileFor(context, audioFilePath)
        // 先写临时名再改名：中途失败只留临时文件，不会出现"文件在位却是半个坏图"
        val temp = File(dir, "${target.name}.part")
        return try {
            FileOutputStream(temp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    return null
                }
            }
            if (!temp.renameTo(target)) null else target.absolutePath
        } catch (e: Exception) {
            // 落盘失败（空间不足 / 权限异常）不致命：返回 null，界面回退占位图
            Log.w(TAG, "保存封面失败：${target.absolutePath}", e)
            null
        } finally {
            temp.delete()
            // Bitmap 不再需要，尽早释放（导入几十条时避免内存堆积）
            bitmap.recycle()
        }
    }

    /** 按目标边长降采样解码，避免把上千像素的原图整张读进内存 */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_EDGE && height / sample > MAX_EDGE) {
            sample *= 2
        }
        return sample
    }
}
