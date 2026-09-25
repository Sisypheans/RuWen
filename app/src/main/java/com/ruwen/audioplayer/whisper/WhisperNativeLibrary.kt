package com.ruwen.audioplayer.whisper

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

/**
 * native 库加载策略：按 CPU 能力选择编译产物。
 *
 * 完全对齐 whisper.cpp 官方 Android 示例 `LibWhisper.kt` 的做法
 * （项目内 `examples/whisper.android/lib/src/main/java/com/whispercpp/whisper/LibWhisper.kt`）：
 * 官方为 arm64-v8a 额外编译了一个 `-march=armv8.2-a+fp16` 的
 * `whisper_v8fp16_va` 变体，运行时读 /proc/cpuinfo 决定加载哪一个。
 *
 * 本项目在此基础上还打开了 `+dotprod`（量化模型 Q5_0/Q5_1 的 int8 点积快路径），
 * 因此除了检测 `fphp`（FP16 向量算术）之外还要检测 `asimddp`（dot product）。
 * 两者都具备才加载优化变体，否则回退基线库 —— 这样 ARMv8.0 的老设备
 * （骁龙 820/835 等）不会因执行不支持的指令而 SIGILL。
 *
 * 注意：绝不使用 -march=native。交叉编译时它描述的是**编译主机**的 CPU，
 * 生成的代码在目标手机上几乎必然非法指令崩溃。
 */
internal object WhisperNativeLibrary {

    private const val TAG = "WhisperNativeLibrary"

    private const val BASELINE = "ruwen_whisper"
    private const val OPTIMIZED = "ruwen_whisper_v8fp16_va"

    /** 已成功加载的库名；未加载成功时为 null */
    @Volatile
    var loadedLibrary: String? = null
        private set

    @Volatile
    private var cpuFeaturesLogged = false

    /**
     * 加载 native 库。
     * @param forceBaseline 强制使用基线库（见 [WhisperSettings.forceBaselineNative]）
     * @return 加载成功返回 true
     */
    fun load(forceBaseline: Boolean = false): Boolean {
        val isArm64 = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        val cpuInfo = readCpuInfo()

        val hasFp16 = cpuInfo?.contains("fphp") == true
        val hasDotProd = cpuInfo?.contains("asimddp") == true
        val useOptimized = isArm64 && !forceBaseline && hasFp16 && hasDotProd

        if (!cpuFeaturesLogged) {
            cpuFeaturesLogged = true
            Log.i(
                TAG,
                "CPU features: abi=${Build.SUPPORTED_ABIS.firstOrNull()}, " +
                    "fphp=$hasFp16, asimddp=$hasDotProd, forceBaseline=$forceBaseline"
            )
        }

        // 优化变体优先；任何一步失败都回退基线库，绝不因为选库失败而无法使用
        if (useOptimized) {
            if (tryLoad(OPTIMIZED)) {
                loadedLibrary = OPTIMIZED
                Log.i(TAG, "Loaded optimized native library ($OPTIMIZED): fp16_va + dotprod")
                return true
            }
            Log.w(TAG, "Optimized native library unavailable, falling back to $BASELINE")
        }

        if (tryLoad(BASELINE)) {
            loadedLibrary = BASELINE
            Log.i(TAG, "Loaded baseline native library ($BASELINE): armv8-a, no fp16_va/dotprod")
            return true
        }

        loadedLibrary = null
        Log.e(TAG, "Failed to load any whisper native library")
        return false
    }

    private fun tryLoad(name: String): Boolean = try {
        System.loadLibrary(name)
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.w(TAG, "System.loadLibrary($name) failed: ${e.message}")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "System.loadLibrary($name) denied: ${e.message}")
        false
    }

    private fun readCpuInfo(): String? = try {
        BufferedReader(FileReader("/proc/cpuinfo")).useLines { lines ->
            // CPU 特性行（"Features" / "flags" / "CPU features"）里包含 fphp / asimddp
            lines.filter {
                it.startsWith("Features") || it.startsWith("flags") || it.startsWith("CPU features")
            }.joinToString(separator = " ")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read /proc/cpuinfo", e)
        null
    }.takeUnless { it.isNullOrEmpty() }
}
