package io.legado.app.model.analyzeRule

import io.legado.app.help.http.NativeUrlEntryEncoding
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.TextCharsetCodec
import io.legado.app.utils.textCharsetCodec

/**
 * AnalyzeUrlCore 的 URL 参数编码 actual 实现 (iOS / 鸿蒙)。
 *
 * 详见 commonMain/AnalyzeUrlCore.kt 中 [encodeUrlParams] expect 注释。
 *
 * - charset 空 → UTF-8 编码;
 * - charset == "escape" → EncoderUtils.escape (不 URL 编码);
 * - 其他 → [textCharsetCodec] 按名取编解码器 (UTF-16 系/Latin1/ASCII 进程内实现,
 *   GBK/GB2312/GB18030/Big5 走平台转换; 未知名抛异常, 与 JVM Charset.forName 语义一致,
 *   不静默退化 UTF-8), 书源指定字符集真实参与 query/表单字节生成;
 * - isQuery=true 且非 escape: 整段用 urlQueryEncoder 编码 (若已 encoded 则原样返回);
 * - isQuery=false: 按 '&' 分段, 每段按 '=' 分 key/value, 按 java.net.URLEncoder 语义
 *   编码 (safe 字符 alnum 与 ` .-_ *`, 空格转 `+`, 连续不安全字符一次编码)。
 */
internal actual fun encodeUrlParams(params: String, charset: String?, isQuery: Boolean): String {
    val checkEncoded = charset.isNullOrEmpty()
    val codec: TextCharsetCodec? = when {
        charset.isNullOrEmpty() -> textCharsetCodec("UTF-8")
        charset == "escape" -> null
        else -> textCharsetCodec(charset)
    }
    if (isQuery && codec != null) {
        return if (NetworkUtils.encodedQuery(params)) params
        else NativeUrlEntryEncoding.encodeQueryParams(params, codec)
    }
    // 与旧实现保持一致: 吃掉单个结尾 '&' 和所有开头 '&', 中间空段保留为 '&&'
    return params.removeSuffix("&")
        .split('&')
        .dropWhile { it.isEmpty() }
        .joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq == -1) {
                encodeOne(pair, checkEncoded, codec)
            } else {
                encodeOne(pair.substring(0, eq), checkEncoded, codec) +
                    "=" + encodeOne(pair.substring(eq + 1), checkEncoded, codec)
            }
        }
}

private fun encodeOne(value: String, checkEncoded: Boolean, codec: TextCharsetCodec?): String =
    when {
        checkEncoded && NetworkUtils.encodedForm(value) -> value
        codec == null -> EncoderUtils.escape(value)
        else -> NativeUrlEntryEncoding.urlEncoderEncode(value, codec::encode)
    }
