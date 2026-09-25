package com.ruwen.audioplayer.data.remote

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.Charset

/** OPML 里的一条订阅（一个 RSS 地址）。 */
data class OpmlFeed(
    val title: String,
    val xmlUrl: String
)

/**
 * OPML 订阅列表解析。
 *
 * OPML 结构：<opml><body><outline text="..." type="rss" xmlUrl="..."/></body></opml>，
 * outline 可能嵌套（分组目录）。用 pull 解析时嵌套是平铺出现的，
 * 所以**不需要递归**——直接扫描所有 outline 标签即可，天然覆盖嵌套层级。
 *
 * 只保留带 xmlUrl 的条目：分组用的父 outline（如 text="科技"）没有 xmlUrl，不是可订阅的播客。
 */
object OpmlParser {

    fun parse(inputStream: InputStream): List<OpmlFeed> {
        val xml = decode(inputStream)
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        val feeds = mutableListOf<OpmlFeed>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == TAG_OUTLINE) {
                val xmlUrl = parser.getAttributeValue(null, ATTR_XML_URL)
                if (!xmlUrl.isNullOrBlank()) {
                    // text 是显示名；没有就退回 title 属性，再没有就用地址本身
                    val title = parser.getAttributeValue(null, ATTR_TEXT)
                        ?.takeIf { it.isNotBlank() }
                        ?: parser.getAttributeValue(null, ATTR_TITLE)
                            ?.takeIf { it.isNotBlank() }
                        ?: xmlUrl
                    feeds += OpmlFeed(title, xmlUrl)
                }
            }
            event = parser.next()
        }
        // 同一地址可能重复出现（不同分组里都引用了同一个播客），去重
        return feeds.distinctBy { it.xmlUrl }
    }

    /**
     * 按 XML 声明里的编码解码。
     * 先用 UTF-8 读出再重解码：OPML 多为 UTF-8，但导出自旧客户端的可能是 GBK/ISO-8859-1，
     * 直接按 UTF-8 解析会让中文标题变乱码。
     */
    private fun decode(inputStream: InputStream): String {
        val bytes = inputStream.use { it.readBytes() }
        val declared = Regex("""encoding\s*=\s*["']([^"']+)["']""")
            .find(String(bytes, Charsets.UTF_8).take(200))
            ?.groupValues?.getOrNull(1)
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() }
        return String(bytes, charset ?: Charsets.UTF_8)
    }

    private const val TAG_OUTLINE = "outline"
    private const val ATTR_XML_URL = "xmlUrl"
    private const val ATTR_TEXT = "text"
    private const val ATTR_TITLE = "title"
}
