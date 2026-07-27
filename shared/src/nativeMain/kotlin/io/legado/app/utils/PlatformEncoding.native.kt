package io.legado.app.utils

/**
 * JVM 专属编码 API 的 iOS/鸿蒙 actual 实现。
 *
 * 详见 commonMain/utils/PlatformEncoding.kt expect 注释。
 * - [MimeBase64Decoder.decode]: 委托 [Base64Lenient.decode] (commonMain 已实现, 与 java.util.Base64.getMimeDecoder
 *   字节级一致 - 宽松处理非法 base64 字符, 跳过字母表外字符)。
 */
actual object MimeBase64Decoder {
    actual fun decode(input: String): ByteArray = Base64Lenient.decode(input)
}
