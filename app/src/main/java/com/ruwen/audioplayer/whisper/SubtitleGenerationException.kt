package com.ruwen.audioplayer.whisper

/**
 * 字幕生成过程中抛出的异常。
 *
 * 用于取代原先“静默降级为演示字幕”的行为：任何一步（native 库缺失、模型不可用、
 * 解码失败、识别失败等）失败都抛出此异常，由上层（Worker / 调用方）统一处理为
 * “生成失败”状态并向用户报错，而不是写入与音频无关的假字幕。
 */
class SubtitleGenerationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
