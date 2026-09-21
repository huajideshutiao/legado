package io.legado.app.utils

import io.legado.app.constant.AppPattern

/**
 * 文本中可点击链接的匹配结果 (供 Compose `linkifyText` 与单元测试共用)。
 *
 * @param range 可点击 URL 在原始文本中的区间
 * @param url   清洗后的真实 URL (已去掉 `,{...}` 请求规格与尾部标点噪声)
 */
data class LinkifyMatch(val range: IntRange, val url: String)

/**
 * 在纯文本中查找可点击的 URL。
 *
 * URL 判定比 Android Linkify.WEB_URLS 更保守:
 * - 匹配到空白/尖括号/引号/花括号即停: 这些字符不可能是合法 URI 的一部分
 *   (RFC 3986 需百分号编码), 常见场景是 `<img src="...">` 或 `"https://..."` 包裹,
 *   避免把尾部 `">` 等吞进链接
 * - 书源 URL 规则 `url,{"method":"POST","body":"..."}` 的 `,{...}` 请求规格段不是真实
 *   URL, 匹配在 `{` 处截断, 尾部遗留的 `,` 由 [cleanLinkUrl] 去掉
 * - 尾部标点/右括号 (句号/逗号/分号/冒号/感叹号/问号/右括号等) 是文本噪声, 一并修剪
 */
fun findLinkifyMatches(text: String): List<LinkifyMatch> {
    return linkifyUrlRegex.findAll(text).map { match ->
        val url = cleanLinkUrl(match.value)
        LinkifyMatch(
            range = match.range.first until match.range.first + url.length,
            url = url,
        )
    }.toList()
}

private val linkifyUrlRegex = Regex("https?://[^\\s<>\"'{}]+")

/**
 * 修剪 URL 匹配结果的尾部噪声:
 *
 * 1. 书源 URL 规则 `url,{...}` 的请求规格段 (AppPattern.urlParamPattern 语义:
 *    `,` 后紧跟 `{` 视为 UrlOption 分隔) — 截断点之前才是真实地址
 * 2. 尾部标点/右括号 (文本中 URL 后常见的句号/逗号/感叹号等, 与 Android Linkify 行为一致)
 */
private fun cleanLinkUrl(raw: String): String {
    var url = raw
    AppPattern.urlParamPattern.find(url)?.let { url = url.substring(0, it.range.first) }
    return url.trimEnd(',', ';', '.', ':', '!', '?', ')', ']', '}')
}
