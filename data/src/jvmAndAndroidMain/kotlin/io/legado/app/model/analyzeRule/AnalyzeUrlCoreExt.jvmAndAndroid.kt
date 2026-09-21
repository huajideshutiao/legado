package io.legado.app.model.analyzeRule

import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.encode
import java.nio.charset.Charset

/**
 * AnalyzeUrlCore 的 URL 参数编码 actual 实现。
 *
 * 详见 commonMain/AnalyzeUrlCore.kt 中 [encodeUrlParams] expect 注释。
 * 本文件把原 AnalyzeUrlCore 的 private fun encodeParams + encodeOne 逻辑下沉,
 * 直接调用 java.net.URLEncoder.encode + java.nio.charset.Charset (commonMain 不可用)。
 */
internal actual fun encodeUrlParams(params: String, charset: String?, isQuery: Boolean): String {
    val checkEncoded = charset.isNullOrEmpty()
    val cs: Charset? = when {
        charset.isNullOrEmpty() -> Charsets.UTF_8
        charset == "escape" -> null
        else -> Charset.forName(charset)
    }
    if (isQuery && cs != null) {
        return if (NetworkUtils.encodedQuery(params)) params
        else urlQueryEncoder.encode(params, cs)
    }
    // 与旧实现保持一致：吃掉单个结尾 '&' 和所有开头 '&'，中间空段保留为 '&&'
    return params.removeSuffix("&")
        .split('&')
        .dropWhile { it.isEmpty() }
        .joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq == -1) {
                encodeOne(pair, checkEncoded, cs)
            } else {
                encodeOne(pair.substring(0, eq), checkEncoded, cs) +
                    "=" + encodeOne(pair.substring(eq + 1), checkEncoded, cs)
            }
        }
}

private fun encodeOne(value: String, checkEncoded: Boolean, charset: Charset?): String =
    when {
        checkEncoded && NetworkUtils.encodedForm(value) -> value
        charset == null -> EncoderUtils.escape(value)
        else -> java.net.URLEncoder.encode(value, charset)
    }
