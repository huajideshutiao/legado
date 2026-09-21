package io.legado.app.utils

/**
 * JVM 专属编码 API 的 jvmAndAndroidMain actual 实现。
 *
 * 详见 commonMain/utils/PlatformEncoding.kt expect 注释。
 * - [MimeBase64Decoder.decode]: 委托 [Base64Lenient.decode]。宽松度高于
 *   java.util.Base64.getMimeDecoder: URL-safe 字母表 ('-'/'_') 按数据位解码而非忽略, 中途 '='
 *   与末尾残位忽略而不抛 IllegalArgumentException; 标准字母表输入两者输出一致。
 *   调用点 (data URI) 只含标准字母表, 不受差异影响。
 */
actual object MimeBase64Decoder {
    actual fun decode(input: String): ByteArray = Base64Lenient.decode(input)
}
