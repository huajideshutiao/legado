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
 * - isQuery=false: 按 '&' 分段, 每段按 '=' 分 key/value, 分别 encodeOne
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
        else -> urlQueryEncoder.encode(value) { it.encodeToByteArray() }
    }
