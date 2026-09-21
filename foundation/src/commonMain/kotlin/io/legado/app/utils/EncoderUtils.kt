package io.legado.app.utils

import kotlin.io.encoding.Base64

/**
 * 编码工具 escape base64
 *
 * 原 jvmAndAndroidMain 实现走 java.util.Base64（已替代 android.util.Base64）。
 * 继续下沉 commonMain: 改用 kotlin.io.encoding.Base64 (KMP 标准库, Kotlin 2.0+ stable)。
 *
 * 行为对齐 java 实现: 标准/URL-safe 字母表、padding/换行(flags)语义逐位复刻,
 * 解码跳过字母表外字符(原 java getMimeDecoder / 现手动 filter 复刻)。
 *
 * 注: 不使用 Base64.withPadding(Padding.ABSENT_OPTIONAL) — Padding 嵌套枚举在
 * Kotlin 2.3 stdlib 中仍标记为 ExperimentalEncodingApi, 改用编码后 trimEnd('=') 等效复刻
 * java Base64.Encoder.withoutPadding() 语义(仅去掉输出末尾 '=', 不影响解码宽松性)。
 */
object EncoderUtils {

    // 与 android.util.Base64 常量同值
    private const val NO_PADDING = 1
    private const val NO_WRAP = 2
    private const val CRLF = 4
    private const val URL_SAFE = 8

    fun escape(src: String): String {
        val tmp = StringBuilder()
        for (char in src) {
            val charCode = char.code
            if (charCode in 48..57 || charCode in 65..90 || charCode in 97..122) {
                tmp.append(char)
                continue
            }

            val prefix = when {
                charCode < 16 -> "%0"
                charCode < 256 -> "%"
                else -> "%u"
            }
            tmp.append(prefix).append(charCode.toString(16))
        }
        return tmp.toString()
    }

    // 短参显式重载: 补回原 @JvmOverloads 生成的 JVM 签名 (commonMain 无该注解), 书源 JS 按 arity 匹配
    fun base64Decode(str: String): String = base64Decode(str, 0)

    fun base64Decode(str: String, flags: Int = 0): String {
        return base64DecodeToByteArray(str, flags).decodeToString()
    }

    fun base64Encode(str: String): String? = base64Encode(str, NO_WRAP)

    fun base64Encode(str: String, flags: Int = NO_WRAP): String? {
        return base64Encode(str.encodeToByteArray(), flags)
    }

    fun base64Encode(bytes: ByteArray): String = base64Encode(bytes, NO_WRAP)

    fun base64Encode(bytes: ByteArray, flags: Int = NO_WRAP): String {
        val encoder = if (flags and URL_SAFE != 0) Base64.UrlSafe else Base64.Default
        val encoded = encoder.encode(bytes)
        // NO_PADDING → 去掉末尾 '=', 等效 java withoutPadding(); 解码端 kotlin Base64 默认宽松, 不受影响
        val finalEncoded = if (flags and NO_PADDING != 0) encoded.trimEnd('=') else encoded
        // NO_WRAP/空输入直接返回; 否则复刻 android: 每 76 字符断行且行尾(含末行)追加分隔符
        if (flags and NO_WRAP != 0 || bytes.isEmpty()) return finalEncoded
        val sep = if (flags and CRLF != 0) "\r\n" else "\n"
        return finalEncoded.chunked(76).joinToString(sep, postfix = sep)
    }

    fun base64DecodeToByteArray(str: String): ByteArray = base64DecodeToByteArray(str, 0)

    fun base64DecodeToByteArray(str: String, flags: Int = 0): ByteArray {
        // android 解码跳过字母表外字符(含换行)且容忍缺失 padding (DEFAULT 宽松语义);
        // kotlin.io.encoding.Base64 对非字母表字符和缺 padding 均抛异常:
        // 先 filter 复刻 skip 语义, 再按 4 的余数补 '=' 复刻 android 自动补 padding
        val filtered = str.filter {
            if (flags and URL_SAFE != 0) {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '='
            } else {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '='
            }
        }
        val padded = filtered + "=".repeat((4 - filtered.length % 4) % 4)
        return if (flags and URL_SAFE != 0) {
            Base64.UrlSafe.decode(padded)
        } else {
            Base64.Default.decode(padded)
        }
    }

}
