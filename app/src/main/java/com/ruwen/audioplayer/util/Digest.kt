package com.ruwen.audioplayer.util

import java.security.MessageDigest

/**
 * 字符串摘要。用于把「音频文件路径 / 音频身份」映射成稳定的文件名。
 *
 * 为什么需要它：路径与音频名里可能有非法字符或超长文本，直接做文件名会失败，
 * 所以统一取 MD5 再拼后缀。
 *
 * 注意：改动本实现会改变既有派生文件名（字幕 `shared_xxx.srt`、封面 `cover_xxx.jpg`），
 * 导致旧文件找不到。**不要改输出格式。**
 */
fun md5(input: String): String = try {
    MessageDigest.getInstance("MD5")
        .digest(input.toByteArray())
        // Byte 是有符号的，必须 & 0xFF 再格式化，否则负值会输出成 ffffff80 之类的脏字符
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
} catch (e: Exception) {
    // MD5 必然可用；万一不可用则退化为 hashCode，至少保证同一输入仍映射到同一文件名
    input.hashCode().toString()
}
