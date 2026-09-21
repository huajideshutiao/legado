package io.legado.app.model.analyzeRule

import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.NetworkUtils

/**
 * AnalyzeUrlCore 的 URL 参数编码 actual 实现 (iOS / 鸿蒙)。
 *
 * 详见 commonMain/AnalyzeUrlCore.kt 中 [encodeUrlParams] expect 注释。
 *
 * 纯 Kotlin 实现, 行为对齐 jvmAndAndroidMain:
 * - charset 空 → UTF-8 编码
 * - charset == "escape" → EncoderUtils.escape (不 URL 编码)
 * - 其他 → 用指定 charset URL 编码 (iOS/鸿蒙仅支持 UTF-8, 非 UTF-8 charset 退化用 UTF-8)
 * - isQuery=true 且非 escape: 整段用 urlQueryEncoder 编码 (若已 encoded 则原样返回)
 * - isQuery=false: 按 '&' 分段, 每段按 '=' 分 key/value, 分别用表单编码器 encodeFormValue
 *   (对齐 jvmAndAndroidMain 的 java.net.URLEncoder, 见 AnalyzeUrlCoreExt.jvmAndAndroid.kt)
 *
 * 注: KMP 仅 UTF-8 原生支持, 非 UTF-8 charset 用 UTF-8 字节替代 (功能受限, 不崩)
 */
internal actual fun encodeUrlParams(params: String, charset: String?, isQuery: Boolean): String {
    val checkEncoded = charset.isNullOrEmpty()
    // KMP 仅 UTF-8 原生支持, 非 UTF-8 charset 退化用 UTF-8 (与 jvmAndAndroidMain 的 Charset.forName 行为在 UTF-8 场景一致)
    val isEscape = charset == "escape"
    if (isQuery && !isEscape) {
        return if (NetworkUtils.encodedQuery(params)) params
        else urlQueryEncoder.encode(params) { it.encodeToByteArray() }
    }
    // 与旧实现保持一致: 吃掉单个结尾 '&' 和所有开头 '&', 中间空段保留为 '&&'
    return params.removeSuffix("&")
        .split('&')
        .dropWhile { it.isEmpty() }
        .joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq == -1) {
                encodeOne(pair, checkEncoded, isEscape)
            } else {
                encodeOne(pair.substring(0, eq), checkEncoded, isEscape) +
                    "=" + encodeOne(pair.substring(eq + 1), checkEncoded, isEscape)
            }
        }
}

private fun encodeOne(value: String, checkEncoded: Boolean, isEscape: Boolean): String =
    when {
        checkEncoded && NetworkUtils.encodedForm(value) -> value
        isEscape -> EncoderUtils.escape(value)
        else -> encodeFormValue(value)
    }

/**
 * 表单编码器 (对齐 java.net.URLEncoder.encode(value, UTF-8) 语义):
 * safe 仅 RFC3986 unreserved (字母数字 -_.~), 空格编成 '+', 其余按 UTF-8 字节 %XX 大写。
 *
 * 不能复用 urlQueryEncoder: 其 safe 含 !$%&()*+,/:;=?@ 等 query 保留字符 (AnalyzeUrlCore.kt 约 654),
 * 用在单个表单 key/value 上会把含 '&'/'=' 的搜索词拆成多个参数; 整段 query 路径仍用 urlQueryEncoder。
 */
private fun encodeFormValue(value: String): String {
    if (value.isEmpty()) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        val code = c.code
        if (code < 0x80) {
            if (isFormUnreserved(code)) {
                out.append(c)
            } else if (code == 0x20) {
                out.append('+')
            } else {
                out.append('%').append(HEX_DIGITS[code ushr 4]).append(HEX_DIGITS[code and 0xF])
            }
            i++
            continue
        }
        // 非 ASCII: 按码点编码 (代理对合并为一个字符的 UTF-8 字节, 避免拆开 surrogate pair)
        val end = if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) i + 2 else i + 1
        for (b in value.substring(i, end).encodeToByteArray()) {
            val v = b.toInt() and 0xFF
            out.append('%').append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0xF])
        }
        i = end
    }
    return out.toString()
}

/** RFC3986 unreserved: ALPHA / DIGIT / '-' / '.' / '_' / '~' */
private fun isFormUnreserved(code: Int): Boolean =
    code in 0x30..0x39 || code in 0x41..0x5A || code in 0x61..0x7A ||
        code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()
