package com.ruwen.audioplayer.whisper

import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 多相（polyphase）窗函数 sinc 重采样器 —— 16-bit PCM → 归一化 float
 *
 * ## 为什么不能只用线性插值
 * 语音识别用的目标采样率是 16 kHz。把 44.1/48 kHz 降到 16 kHz 属于「抽取」，
 * 按采样定理必须先做截止 8 kHz 的低通滤波，否则 8 kHz 以上的高频成分会
 * **混叠（aliasing）** 折叠回 0~8 kHz 的语音频段，形成不可逆的噪声，
 * 直接拉低 whisper 的识别准确率。线性插值本身只有极弱的低通作用，
 * 衰减远不够，因此业界（libsamplerate / libsoxr / FFmpeg swresample）
 * 一律采用「窗函数 sinc 低通 + 多相实现」。
 *
 * ## 本实现采用的方案
 * - 原型滤波器：截止 fc = 0.5 * min(1, dst/src)（归一化到输入采样率）的
 *   理想低通 h(x) = sin(2π·fc·x) / (π·x)，h(0) = 2·fc；
 * - 加 **Blackman 窗** 抑制吉布斯振铃：w(u) = 0.42 + 0.5cos(πu) + 0.08cos(2πu)，
 *   在 |u| = 1 处严格归零；
 * - 多相量化：把小数相位量化到 [PHASES] 档，预生成 PHASES × TAPS 的系数表，
 *   内循环退化成一次点积，这是 libsamplerate 的标准优化；
 * - 每个相位的系数归一化到和为 1，保证直流增益为 1（不会整体变响/变轻）。
 *
 * 抽头数 49（每侧 24）是质量与速度的折中：对 44.1 kHz → 16 kHz 而言，
 * Blackman 窗的过渡带约 6/49 ≈ 0.12（归一化），小于该场景所需的 0.137，
 * 因此混叠被压到很低。
 */
internal object AudioResampler {

    /** 每侧抽头数 */
    private const val HALF = 24
    /** 总抽头数（奇数，保证关于插值点对称） */
    private const val TAPS = HALF * 2 + 1
    /** 相位量化档数 */
    private const val PHASES = 64

    private const val BLOCK = 8192
    private const val CHUNK = 16384

    /**
     * 生成多相系数表：kernels[phase * TAPS + tap]
     */
    fun buildKernels(srcRate: Int, dstRate: Int): FloatArray {
        val fc = 0.5 * minOf(1.0, dstRate.toDouble() / srcRate.toDouble())
        val radius = (HALF + 1).toDouble()
        val kernels = FloatArray(PHASES * TAPS)

        for (p in 0 until PHASES) {
            val frac = p.toDouble() / PHASES
            val base = p * TAPS
            var sum = 0.0

            for (t in 0 until TAPS) {
                // k = t - HALF 是相对 base 的源样本偏移，插值点在 frac 处
                val x = frac - (t - HALF)

                val h = if (x == 0.0) {
                    2.0 * fc
                } else {
                    sin(2.0 * PI * fc * x) / (PI * x)
                }

                val u = x / radius
                val w = if (u <= -1.0 || u >= 1.0) {
                    0.0
                } else {
                    0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2.0 * PI * u)
                }

                val v = h * w
                kernels[base + t] = v.toFloat()
                sum += v
            }

            // 归一化：保证每个相位的直流增益为 1
            if (sum != 0.0) {
                val inv = (1.0 / sum).toFloat()
                for (t in 0 until TAPS) {
                    kernels[base + t] = kernels[base + t] * inv
                }
            }
        }
        return kernels
    }

    /**
     * 把 [src] 中的 [srcCount] 个 16-bit 单声道样本，按 [srcRate] → [dstRate]
     * 重采样为归一化 float（[-1, 1]），写入 [dst] 共 [dstCount] 个采样点。
     *
     * [src] 会被重新定位（position），调用方无需关心；读取通过批量 get 完成，
     * 因此即使 [src] 是 mmap 出来的 ShortBuffer 也有可接受的性能。
     */
    fun resample(
        src: ShortBuffer,
        srcCount: Int,
        srcRate: Int,
        dstRate: Int,
        dst: FloatBuffer,
        dstCount: Int
    ) {
        require(dstCount >= 0) { "dstCount must be >= 0" }

        if (srcRate == dstRate) {
            // 采样率一致，只需做 s16 → f32 的定点到浮点转换，不需要滤波
            convertOnly(src, srcCount, dst, dstCount)
            return
        }

        val kernels = buildKernels(srcRate, dstRate)
        val winSize = (BLOCK.toLong() * srcRate / dstRate + TAPS + 4).toInt()
        val win = ShortArray(winSize)

        var i = 0
        while (i < dstCount) {
            val i1 = min(i + BLOCK, dstCount)

            // 本轮输出所需的源样本区间 [s0, s1)
            val s0 = (i.toLong() * srcRate / dstRate) - HALF
            val s1 = ((i1 - 1).toLong() * srcRate / dstRate) + HALF + 1
            val from = maxOf(0L, s0)
            val to = minOf(srcCount.toLong(), s1)

            // 窗口起点对齐到 s0（s0 可能为负，表示需要前置补零）
            win.fill(0)
            val n = (to - from).toInt().coerceAtLeast(0)
            if (n > 0) {
                src.position(from.toInt())
                src.get(win, (from - s0).toInt(), n)
            }

            for (ii in i until i1) {
                val idx = ii.toLong() * srcRate
                val base = idx / dstRate
                val phase = ((idx % dstRate) * PHASES / dstRate).toInt()
                val off = (base - HALF - s0).toInt()
                val kBase = phase * TAPS

                var acc = 0f
                var t = 0
                while (t < TAPS) {
                    acc += win[off + t] * kernels[kBase + t]
                    t++
                }
                dst.put(acc * (1f / 32768f))
            }
            i = i1
        }
    }

    private fun convertOnly(src: ShortBuffer, srcCount: Int, dst: FloatBuffer, dstCount: Int) {
        val buf = ShortArray(CHUNK)
        var pos = 0
        var remaining = dstCount
        while (remaining > 0) {
            val n = min(min(remaining, CHUNK), srcCount - pos)
            if (n <= 0) break
            src.position(pos)
            src.get(buf, 0, n)
            for (i in 0 until n) {
                dst.put(buf[i] * (1f / 32768f))
            }
            pos += n
            remaining -= n
        }
    }
}
