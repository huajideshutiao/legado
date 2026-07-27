package io.legado.app.utils

/**
 * JVM 专属编码 API 的 jvmAndAndroidMain actual 实现。
 *
 * 详见 commonMain/utils/PlatformEncoding.kt expect 注释。
 * - [MimeBase64Decoder.decode]: 委托 java.util.Base64.getMimeDecoder().decode(String),
 *   行为不变。
 */
actual object MimeBase64Decoder {
    actual fun decode(input: String): ByteArray =
        java.util.Base64.getMimeDecoder().decode(input)
}
