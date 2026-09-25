package com.ruwen.audioplayer.whisper

import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import kotlin.math.ceil

/**
 * Whisper 推理线程数策略
 *
 * 算法原型来自 whisper.cpp 官方 Android 示例
 * `examples/whisper.android/lib/src/main/java/com/whispercpp/whisper/WhisperCpuConfig.kt`
 * （项目内已 vendored），但官方实现有两个真实存在的退化风险，这里做了修正：
 *
 * 1. `getHighPerfCpuCountByFrequencies()` 读
 *    `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`。Android 上该路径
 *    **经常不存在或不可读**（SELinux / 权限），一旦抛异常就整条路失败；
 *    即使全部读到 0，`countDroppingMin()`（`count { it > min }`）也会返回 0，
 *    最终被 `coerceAtLeast(2)` 兜成 **2 线程** —— 8 核旗舰机只用 2 核。
 *
 * 2. `getHighPerfCpuCountByVariant()` 用 `countKeepingMin()`
 *    （返回「最低 variant 簇」的数量），而按主频的那条路用的是
 *    `countDroppingMin()`（返回「丢掉最低簇之后」的数量）。两者语义相反，
 *    且 /proc/cpuinfo 的 "CPU variant" 是 MIDR 的 variant 字段，
 *    并不保证「大核 variant 更大」。实测在部分机型上它会返回**小核**数量。
 *    这里统一成 `countDroppingMin()` 语义，并在「无法分簇」时退回总核数。
 *
 * 另外补了一条官方没有的兜底：两条启发式都失效时，取「总核数的 2/3」
 * 而不是官方的 `availableProcessors() - 4`（8 核机上是 4，6 核机上只有 2，
 * 4 核机上直接是 0 → 被兜成 2）。
 *
 * 为什么不能简单用「总核数」：ggml 的多线程是按行静态切分的同步并行，
 * 一旦线程数超过大核数，整体耗时就被最慢的小核拖住（木桶效应），
 * 反而比只用大核更慢。
 */
internal object WhisperCpuConfig {

    private const val TAG = "WhisperCpuConfig"

    /**
     * 自动策略的线程数上界 —— **这是整个推理性能的头号开关，不要随意调大。**
     *
     * 实测依据（Redmi Turbo 4 Pro / 骁龙 8s Gen 4，small.en-q5_1，28.9 s 音频）：
     *
     * | 线程数 | 总耗时  | xRT   | ms/token |
     * |--------|---------|-------|----------|
     * | 6      | 268.6 s | 0.108 | 2827     |
     * | 2      |  33.5 s | 0.863 |  353     |
     *
     * 也就是说 **2 线程比 6 线程快 8.0 倍**，与「核越多越快」的直觉完全相反。
     *
     * 原因不是小核拖后腿，而是 ggml 的线程模型：`ggml_graph_compute()` 每次调用
     * 都在函数内部 pthread_create / join 出 n_threads-1 个线程（ggml.c 里
     * `// create thread pool` 那一段），**没有常驻线程池**。
     *
     * - 编码器：一整个 30 s 窗口只跑 1 次 graph compute，计算量巨大，多线程净赚。
     * - 解码器：**每生成 1 个 token 就要跑 1 次** graph compute，而每个 op 是
     *   M=1 的矩阵-向量（每线程实测只有约 0.4 M 次乘加），计算量极小。
     *   于是「建线程 + 栅栏同步」的固定开销彻底淹没真实计算。
     *
     * 从 ggml 基准反推出的单次 graph compute 固定开销：
     * 1 线程 ~0 ms｜2 线程 ~0.15 ms｜4 线程 ~0.6 ms｜6 线程 ~8.4 ms｜8 线程 ~14 ms
     * —— 超线性增长，所以线程一多，解码端直接塌方。
     *
     * 只有当 ggml 换成常驻线程池（whisper.cpp 后续版本才引入）之后，
     * 提高线程数才会重新变成正收益。在此之前自动档位宁可少、不要多。
     */
    private const val AUTO_MAX_THREADS = 4

    private const val MIN_THREADS = 1

    /** 自动策略下使用的线程数（不含用户在设置里的手动覆盖） */
    val preferredThreadCount: Int
        get() = resolveThreadCount()

    private fun resolveThreadCount(): Int {
        val total = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        val byFrequency = readHighPerfCpuCountByFrequency()
        val byVariant = readHighPerfCpuCountByVariant()

        val highPerf = listOfNotNull(byFrequency, byVariant).firstOrNull { it in MIN_THREADS..total }
            ?: fallbackHighPerfCount(total)

        // 防御空区间：availableProcessors() 在 Android 上受 cpuset / cgroup 限制，
        // WorkManager 后台执行时完全可能只返回 1（部分定制 ROM 甚至返回 1~2）。
        // 此时若写成 highPerf.coerceIn(MIN_THREADS, total) 就是 coerceIn(2, 1)，
        // Kotlin 的 Int.coerceIn 在 min > max 时会直接抛
        // IllegalArgumentException("Cannot coerce value to an empty range")，
        // 一路冒到 Worker 的 catch 里就变成一句毫无线索的「字幕生成失败」，100% 失败。
        // 因此：上界先压到 total（并保证 ≥1），下界取 MIN_THREADS 但不超过上界。
        val upperBound = total.coerceAtLeast(1).coerceAtMost(AUTO_MAX_THREADS)
        val lowerBound = MIN_THREADS.coerceAtMost(upperBound)
        val threads = highPerf.coerceIn(lowerBound, upperBound)

        Log.i(
            TAG,
            "Thread count=$threads (availableProcessors=$total, " +
                "byFrequency=$byFrequency, byVariant=$byVariant)"
        )
        return threads
    }

    /** 两条启发式都失败时的兜底：假设约 2/3 的核是「非小核」 */
    private fun fallbackHighPerfCount(total: Int): Int =
        ceil(total * 2.0 / 3.0).toInt().coerceAtLeast(MIN_THREADS)

    /**
     * 按最高主频分簇，丢掉最低频那一簇（小核），剩下的就是「非小核」。
     * 任何一个核读不到主频就整条路失败（返回 null），交由下一种策略处理。
     */
    private fun readHighPerfCpuCountByFrequency(): Int? = runCatching {
        val cpuInfo = readCpuInfo()
        val freqs = cpuInfo.mapNotNull { line ->
            if (!line.startsWith("processor")) return@mapNotNull null
            val index = line.substringAfter(':').trim().toIntOrNull() ?: return@mapNotNull null
            readMaxCpuFrequency(index)
        }
        if (freqs.isEmpty()) return@runCatching null

        val min = freqs.minOrNull() ?: return@runCatching null
        val count = freqs.count { it > min }
        Log.d(TAG, "Binned cpu frequencies (frequency, count): ${freqs.groupingBy { it }.eachCount()}")
        if (count > 0) count else null
    }.getOrNull()

    /**
     * 读不到主频时，退化为按 CPU variant 分簇。
     * 与官方不同：这里用 `count { it > min }` 而不是 `count { it == min }`，
     * 与按主频分簇的语义保持一致；若所有核 variant 相同（无法分簇），
     * 返回总核数而不是 0。
     */
    private fun readHighPerfCpuCountByVariant(): Int? = runCatching {
        val variants = readCpuInfo().mapNotNull { line ->
            if (!line.startsWith("CPU variant")) return@mapNotNull null
            val raw = line.substringAfter(':').trim()
            raw.substringAfter("0x").toIntOrNull(radix = 16)
        }
        if (variants.isEmpty()) return@runCatching null

        val min = variants.minOrNull() ?: return@runCatching null
        val count = variants.count { it > min }
        Log.d(TAG, "Binned cpu variants (variant, count): ${variants.groupingBy { it }.eachCount()}")
        when {
            count > 0 -> count
            else -> variants.size // 所有核 variant 相同，无法分簇，按对称多核处理
        }
    }.getOrNull()

    private fun readCpuInfo(): List<String> =
        BufferedReader(FileReader(CPU_INFO_PATH)).useLines { it.toList() }

    /** 读不到就抛异常（由 runCatching 兜住），绝不能返回 0 —— 0 会污染分簇结果 */
    private fun readMaxCpuFrequency(cpuIndex: Int): Int {
        val path = "/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/cpuinfo_max_freq"
        return BufferedReader(FileReader(path)).use { it.readLine() }.trim().toInt()
    }

    private const val CPU_INFO_PATH = "/proc/cpuinfo"
}
