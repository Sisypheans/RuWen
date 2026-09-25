package com.ruwen.audioplayer.whisper

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Debug
import android.util.Log
import com.ruwen.audioplayer.R
import com.ruwen.audioplayer.util.StorageLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Whisper 语音识别管理器
 *
 * 基于 whisper.cpp 实现端侧语音转文字，设计上对齐 whisper.cpp 官方 Android 示例
 * （项目内已 vendored：app/src/main/cpp/whisper/examples/whisper.android/）：
 *
 * - **线程封闭**：whisper.h 明确写着 `whisper_full` "Not thread safe for same context"，
 *   官方 LibWhisper.kt 使用 `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`
 *   把所有 native 调用收敛到同一个线程。这里沿用同一做法（比 Mutex 更严格：
 *   Mutex 只保证互斥，不保证每次都是同一个线程）。
 * - **模型加载**：优先 assets 流式加载（官方 jni.c 的 whisper_init_from_asset，
 *   AAssetManager + whisper_model_loader），也支持从私有目录的文件加载
 *   （用户下载的量化小模型走这条路）。
 * - **音频用 direct ByteBuffer 传入**：GetDirectBufferAddress 零拷贝，
 *   避免大 float 数组跨 JNI 时的一次全量复制。
 *
 * 设计原则：任何一步失败都向上抛出 [SubtitleGenerationException]，由上层（Worker）标记失败，
 * 绝不再静默写入与音频无关的演示字幕。
 */
class WhisperManager(private val context: Context) {

    // ---- whisper.cpp 线程封闭 ---------------------------------------------
    // 所有 native 调用（init / transcribe / release）都派发到这个单线程上执行。
    private val whisperDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ruwen-whisper").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // 以下字段只在 whisperDispatcher 线程上读写（线程封闭，无需 volatile / 锁）
    private var nativePointer: Long = 0
    private var isInitialized = false
    // 当前已加载进内存的模型 ID：用户在设置里换档位后必须重新初始化上下文，
    // 否则会拿着旧模型继续识别（native 侧无法感知 Kotlin 的选择变化）
    private var initializedModelId: String? = null

    // 取消标记（跨线程可见）
    private val cancelRequested = AtomicBoolean(false)

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress

    /** 识别阶段剩余时间估计（毫秒）；<=0 表示还无法估计 */
    private val _etaMillis = MutableStateFlow(0L)
    val etaMillis: StateFlow<Long> = _etaMillis

    // 进入 whisper 识别阶段的墙钟时间，用于推算 ETA
    @Volatile
    private var recognitionStartMs = 0L

    // 字幕输出目录
    private val subtitleDir: File
        get() = StorageLayout.subtitlesDir(context)

    // ---- 空闲释放 -----------------------------------------------------------
    /** 是否正在生成字幕（跨线程可见）：内存紧张时不能把正在用的上下文拆掉 */
    @Volatile
    private var generating = false

    /** 释放模型用的后台作用域（release 是 suspend，且必须落在 whisper 单线程上） */
    private val releaseScope = CoroutineScope(SupervisorJob() + whisperDispatcher)

    /**
     * 内存紧张时释放 native 模型上下文（几百 MB）。
     *
     * 模型常驻能省掉下次生成前的加载时间，但在中低端机上长期占着内存会被系统杀进程。
     * 因此只在**没有生成任务**时释放；下次生成会由 `ensureInitialized()` 自动重新加载。
     * 由 [com.ruwen.audioplayer.RuWenApplication.onTrimMemory] 在切后台 / 内存偏低时调用。
     */
    fun releaseModelIfIdle() {
        if (generating) return
        if (!isInitialized) return
        Log.i(TAG, "内存回收：释放 whisper 上下文")
        releaseScope.launch { release() }
    }

    // ---- native 方法声明 ---------------------------------------------------
    private external fun nativeInitFromAsset(
        assetManager: android.content.res.AssetManager,
        assetPath: String
    ): Long

    private external fun nativeInitFromFile(modelPath: String): Long
    private external fun nativeRelease(nativePointer: Long)
    private external fun nativeCancel()
    private external fun nativeTranscribe(
        nativePointer: Long,
        samples: ByteBuffer,
        nSamples: Int,
        language: String,
        nThreads: Int,
        translate: Boolean,
        wordTimestamps: Boolean
    ): String
    private external fun nativeGetSystemInfo(nativePointer: Long): String

    companion object {
        private const val TAG = "WhisperManager"
        private const val SAMPLE_RATE = 16000

        /** 进度低于该值时不给 ETA（前几个窗口抖动太大） */
        private const val MIN_PROGRESS_FOR_ETA = 3
        /** 识别耗时短于该值时不给 ETA */
        private const val MIN_ELAPSED_FOR_ETA_MS = 20_000L

        // native 库是否成功加载（使用 @Volatile 保证可见性）
        @Volatile
        private var nativeLibraryLoaded = false

        /**
         * 上一次加载时使用的「兼容模式」取值。
         *
         * 必须参与缓存判据：用户在设置里切换 forceBaselineNative 后若不重新选库，
         * 逃生舱（应对 llama.cpp #5108 那种「某些 SoC 上 fp16-va 反而更慢」的情况）
         * 就形同虚设 —— 而这正是我们唯一的对比手段。
         */
        @Volatile
        private var loadedForceBaseline = false

        @Volatile
        private var instance: WhisperManager? = null

        fun getInstance(context: Context): WhisperManager {
            return instance ?: synchronized(this) {
                instance ?: WhisperManager(context.applicationContext).also { manager ->
                    instance = manager
                    manager.ensureNativeLibrary()
                    // 启动时清理 private dir 里残留下来的无效模型文件
                    // （例如被截断的 medium.en.bin），避免它们被误判为已下载、
                    // 顶替真正可用的模型，导致「模型初始化失败」。
                    WhisperModelManager.pruneOrphanModels(context.applicationContext)
                }
            }
        }
    }

    // ----------------------------------------------------------------------
    //  状态查询
    // ----------------------------------------------------------------------

    fun isNativeLibraryAvailable(): Boolean {
        if (!nativeLibraryLoaded) ensureNativeLibrary()
        return nativeLibraryLoaded
    }

    /** 当前生效的模型（用于日志 / UI 展示） */
    fun currentModel(): WhisperModel = WhisperSettings.selectedModel(context)

    /**
     * 磁盘上是否已有「当前选中模型」的文件。
     *
     * **只检查当前选中的模型**，不再用 [WhisperModel.DEFAULT_MODEL]（英语 small）兜底。
     * 早期只有英语、且 small.en 是内置默认档，所以那时加了兜底；在英/日双语下它会掩盖真实问题：
     * 例如选中的日语模型被删、而英语 small 还在时，检查仍会通过 → 白白解码一遍音频，
     * 直到初始化才报错，且提示内容与用户当前选择的语言对不上。
     */
    fun isModelAvailable(): Boolean =
        WhisperModelManager.isDownloaded(context, WhisperSettings.selectedModel(context))

    /** 安装包内是否内置了「当前选中模型」（当前版本不内置任何模型，恒为 false） */
    fun isModelInAssets(): Boolean =
        WhisperModelManager.isBundled(context, WhisperSettings.selectedModel(context))

    /** 模型是否以任一形式可用 */
    fun isModelReady(): Boolean = isModelInAssets() || isModelAvailable()

    fun getModelFileSize(): Long {
        val model = WhisperSettings.selectedModel(context)
        val f = WhisperModelManager.localFile(context, model)
        return if (f.exists()) f.length() else 0
    }

    /**
     * native 库加载（按 CPU 能力选择编译产物）。
     *
     * 缓存判据必须包含 [loadedForceBaseline]：否则用户在设置里切换「兼容模式」后，
     * 因为 `nativeLibraryLoaded` 已经是 true 就永远不再选库，开关等于失效。
     * System.loadLibrary 对同一个 .so 是幂等的，重复调用不会有副作用。
     */
    private fun ensureNativeLibrary() {
        val forceBaseline = WhisperSettings.forceBaselineNative(context)
        if (nativeLibraryLoaded && loadedForceBaseline == forceBaseline) return

        val loaded = WhisperNativeLibrary.load(forceBaseline = forceBaseline)
        if (loaded) {
            nativeLibraryLoaded = true
            loadedForceBaseline = forceBaseline
        } else {
            nativeLibraryLoaded = false
        }
    }

    // ----------------------------------------------------------------------
    //  初始化
    // ----------------------------------------------------------------------

    /**
     * 确保 whisper 上下文已就绪。
     *
     * 模型来源优先级：
     * 1. 用户在设置里选的、且已下载到私有目录的模型（量化小模型走这条路）；
     * 2. APK assets 里内置的模型（官方 whisper.android 示例做法，
     *    不产生 1.5GB 的磁盘拷贝）；
     * 3. 内置模型文件（历史版本可能已拷贝过）。
     *
     * 注意：必须在 whisperDispatcher 线程上调用。
     */
    private suspend fun ensureInitialized() = withContext(whisperDispatcher) {
        val preferred = WhisperSettings.selectedModel(context)
        if (isInitialized && nativePointer != 0L && initializedModelId == preferred.id) {
            return@withContext
        }

        // 模型档位变了（或首次初始化）：先释放旧上下文，避免拿着旧模型继续识别
        if (nativePointer != 0L) {
            runCatching { nativeRelease(nativePointer) }
            Log.i(TAG, "Released previous whisper context (model=$initializedModelId)")
        }
        nativePointer = 0
        isInitialized = false
        initializedModelId = null

        val source = WhisperModelManager.resolve(context, preferred)

        when (source) {
            is WhisperModelSource.FromFile -> {
                Log.i(
                    TAG,
                    "Loading model from file: ${source.file.absolutePath} " +
                        "(${source.file.length()} bytes, model=${source.model.id})"
                )
                val ptr = nativeInitFromFile(source.file.absolutePath)
                if (ptr != 0L) {
                    nativePointer = ptr
                    isInitialized = true
                    initializedModelId = source.model.id
                    logSystemInfo(ptr)
                    return@withContext
                }
                Log.w(TAG, "从文件加载模型失败：${source.file.absolutePath}")
            }

            is WhisperModelSource.FromAssets -> {
                Log.i(
                    TAG,
                    "Loading model from assets: ${source.assetPath} (model=${source.model.id})"
                )
                val ptr = nativeInitFromAsset(context.assets, source.assetPath)
                if (ptr != 0L) {
                    nativePointer = ptr
                    isInitialized = true
                    initializedModelId = source.model.id
                    logSystemInfo(ptr)
                    return@withContext
                }
                Log.w(TAG, "从 assets 加载模型失败：${source.assetPath}")
            }

            is WhisperModelSource.Missing -> {
                throw SubtitleGenerationException(
                    context.getString(R.string.subtitle_err_model_not_ready, source.model.displayName)
                )
            }
        }

        nativePointer = 0
        isInitialized = false
        initializedModelId = null
    }

    private fun logSystemInfo(ptr: Long) {
        runCatching {
            Log.i(TAG, "System info: ${nativeGetSystemInfo(ptr)}")

            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            runCatching { activityManager?.getMemoryInfo(memInfo) }
            Log.i(
                TAG,
                "Memory: avail=${memInfo.availMem / (1024 * 1024)}MB, " +
                    "lowMemory=${memInfo.lowMemory}, " +
                    "nativeHeapAlloc=${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)}MB"
            )
        }.onFailure { Log.w(TAG, "Failed to get system info: ${it.message}") }
    }

    // ----------------------------------------------------------------------
    //  字幕生成
    // ----------------------------------------------------------------------

    /**
     * 生成字幕
     * @param audioUri 音频文件 URI
     * @param subtitleFileName 字幕文件名。由 [com.ruwen.audioplayer.data.entity.SubtitleIdentity]
     *       从「名称+时长+文件大小」派生：同名同时长同大小的音频（即使分散在不同播放列表、
     *       是不同的数据库行）会算出同一个文件名，从而共享同一份字幕。
     *       （历史字幕是按 audioId 命名的 <id>.srt，与之互不干扰，故无需迁移。）
     * @param progressCallback 进度回调 (进度 0-100, 预计剩余毫秒；<=0 表示暂无法估计)
     * @return 生成的 SRT 字幕文件路径
     * @throws SubtitleGenerationException 任一步骤失败时抛出（不再静默降级）
     */
    suspend fun generateSubtitle(
        audioUri: Uri,
        subtitleFileName: String,
        progressCallback: suspend (Int, Long) -> Unit = { _, _ -> }
    ): String = withContext(Dispatchers.Default) {

        subtitleDir.mkdirs()
        val outputFile = File(subtitleDir, subtitleFileName)

        // 进度流重置，避免残留上一次的识别进度
        _progress.value = 0
        _etaMillis.value = 0L
        cancelRequested.set(false)

        // 1. native 库与模型必须可用
        if (!isNativeLibraryAvailable()) {
            throw SubtitleGenerationException(context.getString(R.string.subtitle_err_native_library))
        }
        if (!isModelReady()) {
            throw SubtitleGenerationException(context.getString(R.string.subtitle_err_model_missing))
        }

        progressCallback(5, 0L)

        // 2. 解码 + 重采样（不涉及 native 上下文，放到 whisper 线程之外）
        //    产出的 direct ByteBuffer 常驻 native 内存，不占 Java 堆
        //
        // 阶段计时（排查「进度条卡在 50% 长达 8 分钟」）：
        // 50% 是下面解码结束后写死的检查点，而 whisper 那段实测只要 33.5s
        // （28.9s 音频 / 2 线程）。两者对不上，说明那 8 分钟要么烧在解码+重采样，
        // 要么音频远长于测试用的 28.9s。这里把两段的真实耗时打进 logcat，
        // 下次跑完一眼就能分辨，不用再猜。
        val tDecodeStart = System.currentTimeMillis()
        val pcm = decodeAudioTo16kPcm(audioUri) { pct ->
            progressCallback(5 + (pct * 0.45f).toInt(), 0L)
        }
        val samples = pcm.buffer
        val sampleCount = samples.capacity() / 4 // float = 4 bytes
        Log.i(
            TAG,
            "PHASE decode+resample: ${System.currentTimeMillis() - tDecodeStart} ms " +
                "(audio ${sampleCount / SAMPLE_RATE}s, $sampleCount samples)"
        )

        progressCallback(50, 0L)
        _progress.value = 50

        val threadCount = WhisperSettings.resolveThreadCount(context)
        val model = WhisperSettings.selectedModel(context)
        Log.i(
            TAG,
            "Start transcription: audio=${sampleCount / SAMPLE_RATE}s ($sampleCount samples), " +
                "model=${model.id}, threads=$threadCount, nativeLib=${WhisperNativeLibrary.loadedLibrary}"
        )
        // 3. 识别：把 native 进度（onNativeProgress → _progress）桥接到回调
        val progressJob = CoroutineScope(coroutineContext).launch {
            _progress.collect { progressCallback(it, _etaMillis.value) }
        }
        // 协程被取消时（用户取消 / Worker 停止）立即让 whisper 中止，
        // 否则 native 调用会一直跑到结束才返回
        val cancelHook = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                cancelRequested.set(true)
                runCatching { nativeCancel() }
            }
        }

        val tWhisperStart = System.currentTimeMillis()
        // 识别阶段标记：期间即使系统报内存吃紧，也不能把正在用的上下文释放掉。
        // 放在这里（而不是函数开头）是为了让它在 finally 里一定会被复位——
        // 函数开头的早退分支（原生库/模型缺失）不会经过这里，不会有"卡在 true"的问题。
        generating = true
        try {
            val srtContent = withContext(whisperDispatcher) {
                ensureInitialized()
                if (nativePointer == 0L) {
                    throw SubtitleGenerationException(context.getString(R.string.subtitle_err_model_init))
                }
                // 计时点必须放在 ensureInitialized() **之后**：
                // ensureInitialized() 内部要加载模型（默认 small.en Q5_1，约 0.2GB，按需下载）
                // 并初始化 whisper 上下文，这段耗时与音频时间轴无关。若算进 elapsed，
                // 首轮 ETA 会被放大数倍，用户会看到「预计还需 7 小时」这种离谱数字。
                // 当前默认模型已较小，加载通常很快，但保持「计时起点 = 真正开始识别」
                // 的约定，ETA 才始终只反映真实的推理速率（模型慢时 ETA 偏大是速率本身慢，
                // 不是公式问题——这类情况先查 native 库是否加载了优化变体）。
                recognitionStartMs = System.currentTimeMillis()
                val selectedModel = WhisperSettings.selectedModel(context)
                val langCode = if (selectedModel.isEnglishOnly) "en"
                    else WhisperSettings.selectedLanguage(context).whisperLangCode
                nativeTranscribe(
                    nativePointer,
                    samples,
                    sampleCount,
                    langCode,
                    threadCount,
                    false,
                    WhisperSettings.wordTimestamps(context)
                )
            }
            _etaMillis.value = 0L

            if (cancelRequested.get()) {
                throw CancellationException(context.getString(R.string.subtitle_err_cancelled))
            }

            // 4. 落盘
            progressCallback(95, 0L)
            if (srtContent.isBlank()) {
                throw SubtitleGenerationException(context.getString(R.string.subtitle_err_empty_result))
            }
            outputFile.writeText(srtContent)

            progressCallback(100, 0L)
            outputFile.absolutePath
        } finally {
            generating = false
            cancelHook?.dispose()
            progressJob.cancel()
            // 识别用的 PCM 是 mmap 出来的临时文件：识别一结束就删文件。
            // （映射本身由 GC 释放；文件不删的话，缓存目录会按 90MB/条 地堆积。）
            // 这里放在 finally 里，取消/失败路径也一定会清掉。
            runCatching { pcm.backingFile.delete() }
        }
    }

    /** 请求取消当前正在进行的识别 */
    fun cancel() {
        cancelRequested.set(true)
        runCatching { nativeCancel() }
    }

    /**
     * JNI 回调：native 层进度更新
     * 此方法由 C++ 代码在 whisper 工作线程上调用
     */
    fun onNativeProgress(progress: Int) {
        // Whisper 识别进度映射到 50~95（0~50 由解码阶段覆盖）
        // +0.5f 做四舍五入：长音频下每个 30s 窗口只推进约 2%，2 * 0.45 = 0.9 会被
        // toInt() 直接截断成 0，进度条连着好几个窗口纹丝不动，观感就是「卡住」。
        val adjustedProgress = 50 + (progress * 0.45f + 0.5f).toInt()
        _progress.value = adjustedProgress.coerceIn(0, 100)
        updateEta(progress)
    }

    /**
     * 基于「已用时间 / 已完成比例」外推剩余时间。
     *
     * whisper.cpp 的 progress 是按**音频时间轴**线性推进的
     * （whisper.cpp:5152 `progress_cur = 100*(seek-seek_start)/(seek_end-seek_start)`），
     * 所以「已处理音频时长 / 已耗时」就是一个稳定的速率，可以直接线性外推。
     *
     * 做了两件事避免抖动误导用户：
     * 1. 进度太小 / 耗时太短时不出估计（前几个窗口受预热、调度影响很大）；
     * 2. 用指数平滑（0.7/0.3）抑制单窗口抖动。
     */
    private fun updateEta(nativeProgress: Int) {
        val p = nativeProgress.coerceIn(0, 100)
        val elapsed = System.currentTimeMillis() - recognitionStartMs
        if (recognitionStartMs <= 0L || p < MIN_PROGRESS_FOR_ETA || elapsed < MIN_ELAPSED_FOR_ETA_MS) {
            return
        }
        val estimate = (elapsed.toDouble() * (100.0 - p.toDouble()) / p.toDouble()).toLong()
        val previous = _etaMillis.value
        _etaMillis.value = if (previous > 0L) {
            (previous * 0.7 + estimate * 0.3).toLong()
        } else {
            estimate
        }
    }

    // ----------------------------------------------------------------------
    //  解码：音频文件 → 16kHz 单声道 float PCM（direct ByteBuffer）
    // ----------------------------------------------------------------------

    /**
     * 解码音频为 16kHz 单声道 float PCM。
     *
     * 内存策略（旧实现会在 Java 堆上同时驻留 3~4 份全量 PCM，长音频必然 OOM）：
     * 1. MediaExtractor + MediaCodec 解码，边解码边降混，把单声道 16-bit PCM
     *    **流式写入临时文件**，堆上只保留几十 KB 的暂存缓冲；
     * 2. 用 mmap 把临时文件映射成 ShortBuffer（文件页，内存紧张时内核可回收）；
     * 3. 通过 [AudioResampler] 一次成型地写入 **同样是 mmap 出来的 float 文件映射**交给 JNI，
     *    全程不在 Java 堆、也**不在 native/direct 内存**上留下全量 PCM。
     *
     * 第 3 步为什么不用 `ByteBuffer.allocateDirect`：DirectByteBuffer 占的是 native/direct 内存，
     * 且只能等 GC 触发 Cleaner 才归还。连续生成两条长音频时（例如 25 分钟 → 每条约 90MB），
     * 上一条往往还没被回收，第二条再申请就会抛 OutOfMemoryError，
     * 表现为「音频过长，无法分配识别所需的内存」——与音频长度其实无关。
     * mmap 的页是文件页，内核可随时回收，不受这个上限约束。
     *
     * 解码循环遵循 Android 官方 MediaCodec 状态机（与 ExoPlayer MediaCodecRenderer
     * 的 DRAIN_STATE_WAIT_END_OF_STREAM、FFmpeg libavcodec/mediacodecdec.c 的 drain
     * 实现一致）：输入侧 EOS 只排队一次，输出侧靠 BUFFER_FLAG_END_OF_STREAM 结束。
     */
    private suspend fun decodeAudioTo16kPcm(
        audioUri: Uri,
        progressCallback: suspend (Int) -> Unit
    ): PcmBuffer = withContext(Dispatchers.IO) {

        val cacheDir = context.cacheDir.apply { mkdirs() }
        val tmpPcm = File.createTempFile("ruwen_pcm_", ".pcm", cacheDir)

        // 供 finally 释放用的引用（可为 null）；循环体内使用非空的 val，避免智能转换失效
        var extractorRef: MediaExtractor? = null
        var codecRef: MediaCodec? = null
        var writerRef: Pcm16Writer? = null

        try {
            val extractor = MediaExtractor()
            extractorRef = extractor
            try {
                extractor.setDataSource(context, audioUri, null)
            } catch (e: Exception) {
                throw SubtitleGenerationException(
                    context.getString(R.string.subtitle_err_open_file, e.message.orEmpty()), e
                )
            }

            // 找到音频轨道
            var audioTrackIndex = -1
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mimeType = trackFormat.getString(MediaFormat.KEY_MIME)
                if (mimeType?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    mime = mimeType
                    break
                }
            }
            if (audioTrackIndex == -1) {
                throw SubtitleGenerationException(context.getString(R.string.subtitle_err_no_track))
            }

            extractor.selectTrack(audioTrackIndex)

            val codec = MediaCodec.createDecoderByType(mime)
            codecRef = codec
            val format = extractor.getTrackFormat(audioTrackIndex)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: SAMPLE_RATE
            var channelCount = format.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            val totalDurationUs = format.getLongOrNull(MediaFormat.KEY_DURATION) ?: 0L

            Log.d(
                TAG,
                "Decoding audio: mime=$mime, sampleRate=$sampleRate, " +
                    "channels=$channelCount, duration=${totalDurationUs / 1000}ms"
            )

            // 流式写入临时文件：堆上只有一个固定大小的暂存区
            val writer = Pcm16Writer(BufferedOutputStream(FileOutputStream(tmpPcm), 1 shl 18))
            writerRef = writer

            val bufferInfo = MediaCodec.BufferInfo()
            var sawOutputEOS = false
            var inputEOSQueued = false
            var presentationTimeUs: Long = 0
            var outFrameCount = 0
            // 正常解码阶段：适度超时，避免无意义忙等
            val dequeueTimeoutUs = 10_000L       // 10ms
            // 排空(drain)阶段：用较长阻塞超时，给解码器足够时间吐出尾部帧，避免丢帧
            val drainTimeoutUs = 1_000_000L      // 1s
            // 最后兜底：仅当解码器确实异常、墙钟长时间不回送输出 EOS 时才强制退出
            val maxDrainWallMs = 10_000L
            var drainStartTimeMs = 0L

            while (!sawOutputEOS) {
                // ---- 输入侧：仅在未排队 EOS 时喂数据；到末尾只排队一次 EOS ----
                if (!inputEOSQueued) {
                    val inputBufferIndex = codec.dequeueInputBuffer(dequeueTimeoutUs)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                            ?: throw SubtitleGenerationException(
                                context.getString(R.string.subtitle_err_input_buffer)
                            )
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEOSQueued = true
                            drainStartTimeMs = System.currentTimeMillis()
                        } else {
                            presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize, presentationTimeUs, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // ---- 输出侧 ----
                val outTimeout = if (inputEOSQueued) drainTimeoutUs else dequeueTimeoutUs
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, outTimeout)

                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputEOSQueued &&
                        drainStartTimeMs != 0L &&
                        (System.currentTimeMillis() - drainStartTimeMs) > maxDrainWallMs
                    ) {
                        Log.w(
                            TAG,
                            "解码器排空超时（>${maxDrainWallMs}ms），强制结束（可能丢失少量尾部帧）"
                        )
                        break
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = codec.outputFormat
                    sampleRate = outFormat.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                    channelCount =
                        outFormat.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channelCount
                    Log.d(TAG, "Output format changed: sampleRate=$sampleRate, channels=$channelCount")
                } else if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        ?: throw SubtitleGenerationException(
                            context.getString(R.string.subtitle_err_output_buffer)
                        )

                    try {
                        if (bufferInfo.size < 2) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEOS = true
                            }
                        } else {
                            // 官方写法：先按 offset / size 设置 position 与 limit 再取数据
                            val start = bufferInfo.offset.coerceIn(0, outputBuffer.capacity())
                            val end = (bufferInfo.offset + bufferInfo.size)
                                .coerceIn(start, outputBuffer.capacity())
                            outputBuffer.position(start)
                            outputBuffer.limit(end)

                            val shortView: ShortBuffer = outputBuffer.asShortBuffer()
                            val nShorts = shortView.remaining()
                            if (nShorts > 0) {
                                val chunk = ShortArray(nShorts)
                                shortView.get(chunk)

                                if (channelCount <= 1) {
                                    for (s in chunk) writer.put(s)
                                } else {
                                    // 多声道 → 单声道：按帧取平均（通用降混，支持 >2 声道）
                                    val frames = nShorts / channelCount
                                    var f = 0
                                    while (f < frames) {
                                        val base = f * channelCount
                                        var sum = 0
                                        var c = 0
                                        while (c < channelCount) {
                                            sum += chunk[base + c].toInt()
                                            c++
                                        }
                                        writer.put((sum / channelCount).toShort())
                                        f++
                                    }
                                }
                            }

                            outFrameCount++
                            val rawPct = if (totalDurationUs > 0) {
                                ((presentationTimeUs * 100) / totalDurationUs).toInt().coerceIn(0, 100)
                            } else {
                                (outFrameCount * 2).coerceAtMost(100)
                            }
                            progressCallback(rawPct)

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEOS = true
                            }
                        }
                    } finally {
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }

            writer.flush()
            writer.close()
            writerRef = null

            val monoSamples = writer.samples
            if (monoSamples <= 0) {
                throw SubtitleGenerationException(context.getString(R.string.subtitle_err_decode_empty))
            }
            if (monoSamples > Int.MAX_VALUE.toLong()) {
                throw SubtitleGenerationException(context.getString(R.string.subtitle_err_too_long))
            }

            // 目标采样点数（先按 Long 计算，避免溢出）
            val dstCount = (monoSamples * SAMPLE_RATE / sampleRate).toInt()
            val dstBytes = dstCount.toLong() * 4L
            if (dstBytes > Int.MAX_VALUE) {
                throw SubtitleGenerationException(context.getString(R.string.subtitle_err_too_long))
            }

            Log.d(
                TAG,
                "Decoded $monoSamples mono samples @${sampleRate}Hz -> " +
                    "$dstCount samples @${SAMPLE_RATE}Hz (${dstCount / SAMPLE_RATE}s)"
            )

            // 目标 float PCM：落盘成临时文件后用 mmap 交给 JNI（文件页，不占 native/direct 内存）。
            //
            // 两个关键点（之前踩过）：
            // 1. **必须先用 setLength 把文件撑到目标大小再映射**。对长度为 0 的文件做
            //    READ_WRITE 映射、再往映射里写，等于越界写文件末尾之后的部分，
            //    在部分设备/文件系统上会直接抛异常（甚至 SIGBUS）。用 RandomAccessFile
            //    预置长度是 mmap 写文件的通行做法。
            // 2. 用 RandomAccessFile("rw") 的 channel 映射，保证映射可写；
            //    若这一步仍失败（例如磁盘满），退回 direct 缓冲，保证功能可用。
            val floatPcm = File.createTempFile("ruwen_pcm16k_", ".f32", cacheDir)

            fun resampleInto(dst: ByteBuffer) {
                FileInputStream(tmpPcm).use { fis ->
                    val channel: FileChannel = fis.channel
                    // 源 PCM 也 mmap：文件页由内核按需换入换出，不占用 Java 堆
                    val mappedSrc = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    AudioResampler.resample(
                        src = mappedSrc.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(),
                        srcCount = monoSamples.toInt(),
                        srcRate = sampleRate,
                        dstRate = SAMPLE_RATE,
                        dst = dst.asFloatBuffer(),
                        dstCount = dstCount
                    )
                }
            }

            val buffer: ByteBuffer = try {
                val mapped = RandomAccessFile(floatPcm, "rw").use { raf ->
                    raf.setLength(dstBytes)
                    raf.channel
                        .map(FileChannel.MapMode.READ_WRITE, 0, dstBytes)
                        .order(ByteOrder.nativeOrder())
                }
                resampleInto(mapped)
                mapped
            } catch (mmapError: Throwable) {
                Log.w(
                    TAG,
                    "为识别准备 PCM 时 mmap 失败(${mmapError.javaClass.name})，退回 direct 缓冲",
                    mmapError
                )
                try {
                    val direct = ByteBuffer.allocateDirect(dstBytes.toInt())
                        .order(ByteOrder.nativeOrder())
                    resampleInto(direct)
                    direct
                } catch (directError: Throwable) {
                    floatPcm.delete()
                    throw SubtitleGenerationException(
                        context.getString(
                            R.string.subtitle_err_pcm_prepare,
                            dstBytes / 1024 / 1024,
                            "${mmapError.javaClass.simpleName}(${mmapError.message.orEmpty()})",
                            "${directError.javaClass.simpleName}(${directError.message.orEmpty()})"
                        ),
                        directError
                    )
                }
            }

            PcmBuffer(buffer, floatPcm)
        } finally {
            runCatching { writerRef?.close() }
            runCatching { codecRef?.stop() }
            runCatching { codecRef?.release() }
            runCatching { extractorRef?.release() }
            runCatching { tmpPcm.delete() }
        }
    }

    /**
     * 识别用的 float PCM（16kHz 单声道）及其落盘文件。
     * buffer 是文件映射（MappedByteBuffer），交给 JNI 时按 direct buffer 使用；
     * backingFile 需在识别结束后删除，否则缓存目录会留下 ~90MB/条的临时文件。
     */
    private class PcmBuffer(val buffer: ByteBuffer, val backingFile: File)

    // ----------------------------------------------------------------------
    //  释放
    // ----------------------------------------------------------------------

    suspend fun release() = withContext(whisperDispatcher) {
        if (nativePointer != 0L) {
            try {
                nativeRelease(nativePointer)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to release native: ${e.message}")
            }
        }
        nativePointer = 0
        isInitialized = false
        initializedModelId = null
    }

    // ----------------------------------------------------------------------
    //  工具
    // ----------------------------------------------------------------------

    private fun MediaFormat.getIntOrNull(key: String): Int? =
        try {
            if (containsKey(key)) getInteger(key) else null
        } catch (e: Exception) {
            null
        }

    private fun MediaFormat.getLongOrNull(key: String): Long? =
        try {
            if (containsKey(key)) getLong(key) else null
        } catch (e: Exception) {
            null
        }

}

/**
 * 把 16-bit 小端 PCM 流式写入输出流，堆上只保留固定大小的暂存区。
 */
private class Pcm16Writer(private val out: OutputStream) {

    private val buf = ByteArray(1 shl 16) // 64KB
    private var pos = 0

    /** 已写入的单声道样本数 */
    var samples: Long = 0L
        private set

    fun put(value: Short) {
        if (pos + 2 > buf.size) flush()
        val v = value.toInt()
        buf[pos++] = (v and 0xFF).toByte()
        buf[pos++] = ((v ushr 8) and 0xFF).toByte()
        samples++
    }

    fun flush() {
        if (pos > 0) {
            out.write(buf, 0, pos)
            pos = 0
        }
    }

    fun close() {
        flush()
        out.close()
    }
}
