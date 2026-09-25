package com.ruwen.audioplayer.util

import android.content.Context
import java.io.File

/**
 * App 私有目录下的**统一存储布局**。
 *
 * 之前这三个目录名分别硬编码在 `CoverStore`、`WhisperManager`、`WhisperModel` 里，
 * 而 `OrphanCleaner` 又各写了一遍——一旦某处改了目录名或比对方式，
 * 清理逻辑就会把**在用文件**当孤儿删掉（封面曾被整批误删就是这个原因）。
 * 现在目录定义只此一处，清理与读写都从这里取。
 */
object StorageLayout {

    const val DIR_COVERS = "covers"
    const val DIR_SUBTITLES = "subtitles"
    const val DIR_MODELS = "models"

    fun coversDir(context: Context): File =
        File(context.filesDir, DIR_COVERS).apply { mkdirs() }

    fun subtitlesDir(context: Context): File =
        File(context.filesDir, DIR_SUBTITLES).apply { mkdirs() }

    fun modelsDir(context: Context): File =
        File(context.filesDir, DIR_MODELS).apply { mkdirs() }
}
