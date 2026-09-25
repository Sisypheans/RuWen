package com.ruwen.audioplayer.whisper

import android.content.Context
import android.util.Log
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.util.StorageLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 字幕识别语言
 *
 * 按 whisper.cpp 官方 `g_lang` 表中「识别质量最佳（WER 最低）的前 12 种语言」排列，
 * 这也是语言选择列表的展示顺序（英语恒在最前）。
 *
 * whisper.cpp 官方约定「模型名带 .en 的是纯英文模型，不带 .en 的是多语言模型」，
 * 因此英语单独使用 `.en` 模型；其余 11 种语言共用同一套多语言模型，
 * 在识别时用 [whisperLangCode] 把识别语言锁定为对应语种，而非交给模型「自动检测」。
 *
 * [displayName] 为语言自身的母语名（native name），语言选择列表直接用它，
 * 无需逐语言翻译，也是各类语言选择器的通行做法。
 */
enum class WhisperLanguage(
    val id: String,
    val whisperLangCode: String,
    /** 语言母语名，用于语言选择列表 */
    val displayName: String
) {
    ENGLISH("en", "en", "English"),
    CHINESE("zh", "zh", "中文"),
    GERMAN("de", "de", "Deutsch"),
    SPANISH("es", "es", "Español"),
    RUSSIAN("ru", "ru", "Русский"),
    KOREAN("ko", "ko", "한국어"),
    FRENCH("fr", "fr", "Français"),
    JAPANESE("ja", "ja", "日本語"),
    PORTUGUESE("pt", "pt", "Português"),
    POLISH("pl", "pl", "Polski"),
    DUTCH("nl", "nl", "Nederlands"),
    ITALIAN("it", "it", "Italiano");

    companion object {
        fun fromId(id: String?): WhisperLanguage =
            values().firstOrNull { it.id == id } ?: ENGLISH
    }
}

/**
 * Whisper 模型目录
 *
 * 设计为「3 尺寸 × 2 类 = 6 个」，全部 Q8_0 量化档（速度与精度最均衡的档位）：
 *  - **英文模型**（文件名带 `.en`）：medium.en / small.en / base.en，仅供英语识别；
 *  - **多语言模型**（不带 `.en`）：medium / small / base，供除英语外的 11 种语言共用。
 *
 * whisper.cpp 官方定义「模型名含 .en 为纯英文模型，不含 .en 为多语言模型」，
 * 后者可识别 99 种语言，本项目从中挑出官方推荐质量最佳的 12 种供用户选择。
 * 因此「语言」与「模型」是**解耦**的：切换识别语言不会强制更换模型，
 * 仅在所选模型与目标语言不匹配（英语 ↔ 多语言）时做一致性兜底。
 *
 * **本工程不再内置任何模型**：首次使用需在「设置 → 字幕识别模型」中按语言下载一个。
 */
enum class WhisperModel(
    /** 持久化在 SharedPreferences 里的稳定 ID */
    val id: String,
    /** 磁盘/下载文件名 */
    val fileName: String,
    /** 设置界面展示名 */
    val displayName: String,
    /** 大致体积（字节），仅用于 UI 展示，不用于校验 */
    val approxSizeBytes: Long,
    /** 是否为纯英文模型（文件名带 .en）；false = 多语言模型，供除英语外的所有语言共用 */
    val isEnglishOnly: Boolean,
    /** 是否随 APK 打包在 assets/models/ 下 */
    val bundledInAssets: Boolean,
    /** 未内置时是否支持从官方仓库下载 */
    val downloadable: Boolean
) {
    // ---- 英文（.en）----
    MEDIUM_EN_Q8_0(
        id = "medium.en-q8_0",
        fileName = "ggml-medium.en-q8_0.bin",
        displayName = "medium · Q8_0",
        approxSizeBytes = 823_382_461L,
        isEnglishOnly = true,
        bundledInAssets = false,
        downloadable = true
    ),

    SMALL_EN_Q8_0(
        id = "small.en-q8_0",
        fileName = "ggml-small.en-q8_0.bin",
        displayName = "small · Q8_0",
        approxSizeBytes = 264_477_561L,
        isEnglishOnly = true,
        bundledInAssets = false,
        downloadable = true
    ),

    BASE_EN_Q8_0(
        id = "base.en-q8_0",
        fileName = "ggml-base.en-q8_0.bin",
        displayName = "base · Q8_0",
        approxSizeBytes = 81_781_811L,
        isEnglishOnly = true,
        bundledInAssets = false,
        downloadable = true
    ),

    // ---- 多语言模型（不带 .en，供除英语外的 11 种语言共用）----
    MEDIUM_Q8_0(
        id = "medium-q8_0",
        fileName = "ggml-medium-q8_0.bin",
        displayName = "medium · Q8_0",
        approxSizeBytes = 823_369_779L,
        isEnglishOnly = false,
        bundledInAssets = false,
        downloadable = true
    ),

    SMALL_Q8_0(
        id = "small-q8_0",
        fileName = "ggml-small-q8_0.bin",
        displayName = "small · Q8_0",
        approxSizeBytes = 264_464_607L,
        isEnglishOnly = false,
        bundledInAssets = false,
        downloadable = true
    ),

    BASE_Q8_0(
        id = "base-q8_0",
        fileName = "ggml-base-q8_0.bin",
        displayName = "base · Q8_0",
        approxSizeBytes = 81_768_585L,
        isEnglishOnly = false,
        bundledInAssets = false,
        downloadable = true
    );

    val assetPath: String get() = "models/$fileName"

    fun downloadUrl(): String =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        /**
         * 默认模型（small · Q8_0 英语档）。
         *
         * 注意：名字里的 DEFAULT **不是**「内置在 assets 里」的意思 —— 本工程已不再内置任何模型，
         * 所有模型（包括这一个）的 bundledInAssets 都是 false，首次使用必须先下载一次。
         * 之所以不叫 BUNDLED，是因为旧命名会让人误以为 [WhisperModelManager.isBundled] 对它恒为 true。
         */
        val DEFAULT_MODEL: WhisperModel = SMALL_EN_Q8_0

        fun fromId(id: String?): WhisperModel =
            values().firstOrNull { it.id == id } ?: DEFAULT_MODEL

        /** 某语言对应的默认模型：英语→small.en，其余→small 多语言 */
        fun defaultForLanguage(language: WhisperLanguage): WhisperModel =
            if (language == WhisperLanguage.ENGLISH) SMALL_EN_Q8_0 else SMALL_Q8_0

        /** 某语言可用的全部模型（3 个尺寸，按体积从大到小） */
        fun valuesForLanguage(language: WhisperLanguage): List<WhisperModel> =
            values().filter { it.isEnglishOnly == (language == WhisperLanguage.ENGLISH) }
    }
}

/** 模型解析结果 */
sealed class WhisperModelSource {
    data class FromAssets(val assetPath: String, val model: WhisperModel) : WhisperModelSource()
    data class FromFile(val file: File, val model: WhisperModel) : WhisperModelSource()
    data class Missing(val model: WhisperModel) : WhisperModelSource()
}

/**
 * 模型文件管理：本地存放、可用性判断、下载、删除。
 */
object WhisperModelManager {

    private const val TAG = "WhisperModelManager"

    fun modelDir(context: Context): File =
        File(context.filesDir, StorageLayout.DIR_MODELS).apply { mkdirs() }

    fun localFile(context: Context, model: WhisperModel): File =
        File(modelDir(context), model.fileName)

    fun isDownloaded(context: Context, model: WhisperModel): Boolean {
        val f = localFile(context, model)
        return f.exists() && f.length() > MIN_VALID_SIZE_BYTES
    }

    fun isBundled(context: Context, model: WhisperModel): Boolean =
        model.bundledInAssets && runCatching {
            context.assets.list("models")?.contains(model.fileName) == true
        }.getOrDefault(false)

    /**
     * 解析当前应该使用的模型来源。
     * 优先级：已下载的选中模型 > APK 内置的选中模型 > 内置兜底模型 > 缺失。
     */
    fun resolve(context: Context, preferred: WhisperModel): WhisperModelSource {
        if (preferred.bundledInAssets && isBundled(context, preferred)) {
            return WhisperModelSource.FromAssets(preferred.assetPath, preferred)
        }
        if (isDownloaded(context, preferred)) {
            return WhisperModelSource.FromFile(localFile(context, preferred), preferred)
        }
        if (isBundled(context, preferred)) {
            return WhisperModelSource.FromAssets(preferred.assetPath, preferred)
        }
        return WhisperModelSource.Missing(preferred)
    }

    /**
     * 从官方仓库下载模型。写入 `.part` 临时文件后原子改名，
     * 避免中断留下半截文件被当成可用模型。
     *
     * @param onProgress 回调 (已下载字节, 总字节)，总字节可能为 -1（服务端未给长度）
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun download(
        context: Context,
        model: WhisperModel,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!model.downloadable) {
            return@withContext Result.failure(
                IOException(context.getString(R.string.err_model_no_download, model.fileName))
            )
        }

        val dir = modelDir(context)
        val target = File(dir, model.fileName)
        val part = File(dir, "${model.fileName}.part")
        if (part.exists()) part.delete()

        // 下载前先确认存储空间：medium 档近 800MB，写一半才失败体验很差
        val usable = runCatching { dir.usableSpace }.getOrDefault(0L)
        if (usable > 0 && usable < model.approxSizeBytes + 64L * 1024 * 1024) {
            return@withContext Result.failure(
                    IOException(
                        context.getString(
                            R.string.err_storage_insufficient,
                            formatSize(model.approxSizeBytes),
                            formatSize(usable)
                        )
                    )
            )
        }

        var connection: HttpURLConnection? = null
        var downloaded = 0L
        try {
            Log.i(TAG, "Downloading model ${model.fileName} from ${model.downloadUrl()}")
            connection = openFollowRedirects(context, model.downloadUrl())

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext Result.failure(
                    IOException(context.getString(R.string.err_download_http, responseCode, connection.url))
                )
            }

            // 关键校验：HuggingFace 的 resolve 端点会 302 到 CDN。若客户端没有跟随
            // 重定向，就会把「Found. Redirecting to ...」这约 1KB 的 text/plain 当成
            // 模型下载——进度条同样会走到 100%，最后才暴露成「文件为空」。
            val contentType = connection.contentType.orEmpty()
            if (contentType.startsWith("text/", ignoreCase = true)) {
                return@withContext Result.failure(
                    IOException(
                        context.getString(R.string.err_download_not_model, contentType)
                    )
                )
            }

            val total = runCatching {
                connection.getHeaderField("Content-Length")?.toLong()
            }.getOrNull() ?: -1L
            if (total == 0L) {
                return@withContext Result.failure(IOException(context.getString(R.string.err_download_empty)))
            }

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(1 shl 18) // 256KB
                    var lastEmit = 0L
                    while (true) {
                        if (!coroutineContext.isActive) {
                            return@withContext Result.failure(
                                IOException(context.getString(R.string.err_download_cancelled))
                            )
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastEmit >= PROGRESS_EMIT_INTERVAL_BYTES) {
                            lastEmit = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    output.flush()
                    onProgress(downloaded, total)
                }
            }

            if (!part.exists() || part.length() <= MIN_VALID_SIZE_BYTES) {
                return@withContext Result.failure(
                    IOException(
                        context.getString(
                            R.string.err_download_partial,
                            downloaded,
                            formatSize(downloaded)
                        )
                    )
                )
            }
            // 与官方公布的字节数比对（放宽到 98%，容忍服务端替换文件导致的轻微差异）
            if (downloaded < (model.approxSizeBytes * 98) / 100) {
                return@withContext Result.failure(
                    IOException(
                        context.getString(
                            R.string.err_download_incomplete,
                            formatSize(downloaded),
                            formatSize(model.approxSizeBytes)
                        )
                    )
                )
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                return@withContext Result.failure(IOException(context.getString(R.string.err_download_write, model.fileName)))
            }

            Log.i(TAG, "Model downloaded: ${target.absolutePath} (${target.length()} bytes)")
            Result.success(target)
        } catch (e: CancellationException) {
            Log.i(TAG, "Model download cancelled: ${model.fileName}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed: ${model.fileName}", e)
            Result.failure(e)
        } finally {
            runCatching { connection?.disconnect() }
            if (part.exists()) {
                part.delete()
            }
        }
    }

    /**
     * 建立连接并**手动**跟随重定向。
     *
     * 不依赖 [HttpURLConnection.instanceFollowRedirects] 的原因：
     * HuggingFace 的 `resolve/main` 会 302 到 `*.cdn.hf.co` / `cdn-lfs`，不同 Android 版本
     * 对重定向的处理存在差异（且 Java 的实现不跟随 308）。一旦没跟随，就会把约 1KB 的
     * 「Found. Redirecting to ...」文本当成模型下载，表现为进度条走到 100% 后报「文件为空」。
     * 手动跟随可统一覆盖 301 / 302 / 303 / 307 / 308。
     */
    private fun openFollowRedirects(context: Context, startUrl: String): HttpURLConnection {
        var url = startUrl
        for (i in 0..MAX_REDIRECTS) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code !in 300..399) return conn
            val location = conn.getHeaderField("Location")
            if (location.isNullOrBlank()) return conn
            if (i == MAX_REDIRECTS) {
                conn.disconnect()
                throw IOException(context.getString(R.string.err_download_redirect, url))
            }
            // Location 可能是相对路径，需基于当前 URL 解析
            val resolved = runCatching { URL(URL(url), location).toString() }
                .getOrDefault(location)
            Log.i(TAG, "Follow redirect $code -> ${resolved.take(120)}")
            conn.disconnect()
            url = resolved
        }
        throw IOException(context.getString(R.string.err_download_no_connection))
    }

    fun delete(context: Context, model: WhisperModel): Boolean {
        if (!model.downloadable) return false
        val f = localFile(context, model)
        return runCatching { f.delete() }.getOrDefault(false)
    }

    /**
     * 清理 files/models 目录下「不对应任何已知模型」的 .bin 文件。
     *
     * 典型场景：历史版本把 medium.en(FP16, 1.5GB) 或 q5_1 档模型拷到了私有目录，
     * 若下载中断会留下半截文件；这类文件大小超过 [MIN_VALID_SIZE_BYTES] 会被
     * [isDownloaded] 误判为已下载，从而顶替真正的模型、导致「模型初始化失败」。
     * 启动时清掉它们。
     */
    fun pruneOrphanModels(context: Context) {
        val validNames = WhisperModel.values().map { it.fileName }.toSet()
        val dir = modelDir(context)
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles { _, name ->
            name.endsWith(".bin", ignoreCase = true) && name !in validNames
        }?.forEach { file ->
            if (runCatching { file.delete() }.getOrDefault(false)) {
                Log.i(TAG, "Pruned orphan model file: ${file.absolutePath}")
            }
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "--"
        val mb = bytes / 1_048_576.0
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }

    private const val MIN_VALID_SIZE_BYTES = 8L * 1024 * 1024 // 8MB，小于此值视为残文件
    private const val MAX_REDIRECTS = 5
    private const val USER_AGENT = "RuWen-Android"
    private const val PROGRESS_EMIT_INTERVAL_BYTES = 4L * 1024 * 1024 // 每 4MB 回调一次
}
