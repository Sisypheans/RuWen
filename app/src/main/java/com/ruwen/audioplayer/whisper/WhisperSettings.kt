package com.ruwen.audioplayer.whisper

import android.content.Context

/**
 * Whisper 相关设置的持久化存储。
 *
 * 当前暴露的可调项：识别语言（12 种官方推荐语言，默认英语）、模型档位、兼容模式、词级时间戳。
 * 线程数不再由用户选择，统一走 [WhisperCpuConfig] 的自动策略（手动覆盖实测反而更慢）。
 */
object WhisperSettings {

    private const val PREFS_NAME = "ruwen_whisper_settings"
    private const val KEY_MODEL_ID = "model_id"
    private const val KEY_LANGUAGE = "language"                       // 识别语言（"en" / "ja"）
    private const val KEY_FORCE_BASELINE = "force_baseline_native"     // 强制使用基线 .so
    private const val KEY_WORD_TIMESTAMPS = "word_timestamps"         // 词级时间戳开关

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前选择的语言（默认英语） */
    fun selectedLanguage(context: Context): WhisperLanguage =
        WhisperLanguage.fromId(prefs(context).getString(KEY_LANGUAGE, null))

    fun setSelectedLanguage(context: Context, language: WhisperLanguage) {
        prefs(context).edit().putString(KEY_LANGUAGE, language.id).apply()
    }

    /**
     * 当前选择的模型。
     *
     * 语言一致性保护：若已存模型的「是否纯英文」与当前所选语言不符
     * （例如当前选了中文，但上次选的是英文 .en 模型），回退到该语言的默认模型，
     * 避免「切了语言却仍用错类别的模型」。找不到已存模型时也回退到默认模型。
     */
    fun selectedModel(context: Context): WhisperModel {
        val wantEnglishOnly = selectedLanguage(context) == WhisperLanguage.ENGLISH
        val stored = WhisperModel.fromId(prefs(context).getString(KEY_MODEL_ID, null))
        return if (stored.isEnglishOnly == wantEnglishOnly) stored
            else WhisperModel.defaultForLanguage(selectedLanguage(context))
    }

    fun setSelectedModel(context: Context, model: WhisperModel) {
        prefs(context).edit().putString(KEY_MODEL_ID, model.id).apply()
    }

    /**
     * 强制加载基线 native 库（armv8-a，无 FP16 向量算术 / 无 dotprod）。
     * 用途：
     * 1. 老设备（ARMv8.0）上的兼容性兜底；
     * 2. 个别机型上 FP16 向量算术反而更慢时（llama.cpp 讨论 #5108 在
     *    骁龙 888 上就观察到过），用它做 A/B 对比。
     */
    fun forceBaselineNative(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_BASELINE, false)

    fun setForceBaselineNative(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FORCE_BASELINE, enabled).apply()
    }

    /**
     * 是否启用词级时间戳（word-level timestamps）。
     *
     * 默认开启：small 档下词级时间戳能给出更细的逐字对齐，但会显著拖慢推理
     * （whisper.cpp 社区排障指南把「移除 --word_timestamps」列为提速手段之一，
     * 在我们的设备上也观察到 2~3 倍差距）。长音频、追求速度时建议关闭。
     * 该选项在下次生成字幕时生效（与模型档位一样会重建 whisper 上下文）。
     */
    fun wordTimestamps(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WORD_TIMESTAMPS, true)

    fun setWordTimestamps(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WORD_TIMESTAMPS, enabled).apply()
    }

    /**
     * 实际使用的线程数：自动策略即最佳（按大核数推断），不再允许用户手动覆盖。
     */
    fun resolveThreadCount(context: Context): Int = WhisperCpuConfig.preferredThreadCount
}
