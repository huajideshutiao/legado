package org.jsoup

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser as KsParser
import io.legado.app.utils.InputStream
import org.jsoup.nodes.Document
import org.jsoup.nodes.asFacadeDocument

// 外部约束: ksoup-io 模块 (Ksoup.parseInputStream/DataUtil.load) 不在本仓库依赖树内,
// 此处按 jsoup DataUtil.detectCharset 语义自实现: BOM > 显式 charsetName > meta 声明 > UTF-8。
// keiyoushi 扩展源码未直接引用该重载, 供 Mihon 兼容层及流式响应使用。

internal fun jsoupParseInputStream(
    input: InputStream,
    charsetName: String?,
    baseUri: String,
    parser: KsParser,
): Document {
    val bytes = input.use { it.readBytes() }

    // 无显式字符集时先探测 BOM
    var bomLength = 0
    var effectiveName = charsetName
    if (effectiveName == null && bytes.size >= 2) {
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> {
                effectiveName = "UTF-8"
                bomLength = 3
            }

            bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
                effectiveName = "UTF-16BE"
                bomLength = 2
            }

            bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
                effectiveName = "UTF-16LE"
                bomLength = 2
            }
        }
    }

    if (effectiveName != null) {
        // 显式指定或命中 BOM: 直接按该字符集解码, 不再看 meta (对齐 jsoup)
        val charset = java.nio.charset.Charset.forName(effectiveName)
        val html = String(bytes, bomLength, bytes.size - bomLength, charset)
        return asFacadeDocument(Ksoup.parse(html, parser, baseUri))
    }

    // 无字符集线索: 先按 UTF-8 解析, meta 声明了不同字符集时重解码重解析 (对齐 jsoup)
    val first = Ksoup.parse(String(bytes, Charsets.UTF_8), parser, baseUri)
    val firstFacade = asFacadeDocument(first)
    val declared = detectDeclaredCharset(firstFacade) ?: return firstFacade
    if (declared.equals("UTF-8", ignoreCase = true)) return firstFacade
    return runCatching { java.nio.charset.Charset.forName(declared) }
        .map { charset -> asFacadeDocument(Ksoup.parse(String(bytes, charset), parser, baseUri)) }
        .getOrElse { firstFacade }
}

/** 从文档 meta 声明中提取字符集名 (http-equiv=content-type 的 content 优先, 其次 charset 属性) */
private fun detectDeclaredCharset(document: org.jsoup.nodes.Document): String? {
    val doc = document.ksoupDocument
    doc.selectFirst("meta[http-equiv=content-type]")?.attr("content")?.let { content ->
        CHARSET_IN_CONTENT.find(content)?.groupValues?.get(1)?.let { return it }
    }
    val declared = doc.selectFirst("meta[charset]")?.attr("charset")
    return declared?.ifEmpty { null }
}

private val CHARSET_IN_CONTENT = Regex("(?i)charset\\s*=\\s*\"?([\\w-]+)")
