package com.ruwen.audioplayer.util

import android.content.Context
import android.util.Log
import com.ruwen.audioplayer.data.dao.AudioItemDao
import com.ruwen.audioplayer.data.dao.PlaylistDao
import com.ruwen.audioplayer.whisper.WhisperModel
import java.io.File

/**
 * 孤儿文件清理（「设置 → 清除缓存」）。
 *
 * 私有目录里的派生文件与其数据库引用的对应关系：
 *  - `covers/`   封面文件   ↔ audio_items.coverPath + playlists.coverUri（列表封面）
 *  - `subtitles/` 字幕文件   ↔ audio_items.subtitlePath
 *  - `models/`   识别模型   ↔ WhisperModel 枚举（不对应任何已知模型的就是孤儿）
 *
 * 凡是数据库里已无引用（或模型名未知）的文件都算孤儿，含下载残留的 `.part` 临时文件。
 * 只清这三个目录，**不碰**播客单集下载等用户内容。
 *
 * ⚠️ 路径比对必须用**文件名**，不能用数据库里的完整路径去和 `File.name` 比：
 * 数据库存的是绝对路径（`/data/user/0/.../covers/cover_xxx.jpg`），
 * 之前直接拿它和文件名 `cover_xxx.jpg` 做 contains 判断，永远不匹配 →
 * 把**所有**封面当成孤儿删光了。现在统一先 `File(path).name` 归一化再比。
 */
object OrphanCleaner {

    private const val TAG = "OrphanCleaner"

    data class Result(val deletedFiles: Int, val freedBytes: Long)

    suspend fun clean(
        context: Context,
        audioItemDao: AudioItemDao,
        playlistDao: PlaylistDao
    ): Result {
        // 归一化成「文件名」集合（数据库里存的是绝对路径）
        val referencedCoverNames = buildSet {
            audioItemDao.getAllCoverPaths().forEach { add(File(it).name) }
            playlistDao.getAllCoverUris().forEach { add(File(it).name) }
        }
        val referencedSubtitleNames = audioItemDao.getAllSubtitlePaths()
            .mapTo(mutableSetOf()) { File(it).name }
        val knownModelNames = WhisperModel.values().mapTo(mutableSetOf()) { it.fileName }

        var deleted = 0
        var freed = 0L

        fun deleteIfOrphan(file: File, referencedNames: Set<String>) {
            // `xxx.jpg.part` 这类下载残留一律清掉（正式文件才需要判断引用）
            val isPart = file.name.endsWith(".part")
            if (!isPart && file.name in referencedNames) return
            val length = runCatching { file.length() }.getOrDefault(0L)
            if (runCatching { file.delete() }.getOrDefault(false)) {
                deleted++
                freed += length
            }
        }

        File(context.filesDir, StorageLayout.DIR_COVERS).takeIf { it.isDirectory }?.listFiles()
            ?.forEach { deleteIfOrphan(it, referencedCoverNames) }
        File(context.filesDir, StorageLayout.DIR_SUBTITLES).takeIf { it.isDirectory }?.listFiles()
            ?.forEach { deleteIfOrphan(it, referencedSubtitleNames) }
        File(context.filesDir, StorageLayout.DIR_MODELS).takeIf { it.isDirectory }?.listFiles()
            ?.forEach { deleteIfOrphan(it, knownModelNames) }

        Log.i(TAG, "Cleaned $deleted orphan files, freed $freed bytes")
        return Result(deleted, freed)
    }
}
