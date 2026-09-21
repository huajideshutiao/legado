package io.legado.app.utils

/**
 * JVM 专属编码 API 的 commonMain expect 门面。
 *
 * AnalyzeUrlCore 下沉 commonMain 后, getByteArrayIfDataUri 需要 MIME 风格的宽松 base64 解码
 * (字母表外字符跳过而不抛错), java.util.Base64.getMimeDecoder 是 JVM-only API, 需 expect/actual
 * 包装。
 *
 * - [MimeBase64Decoder.decode]: MIME 宽松语义 —— 非法 base64 字符忽略而非抛
 *   IllegalArgumentException; actual 经 [Base64Lenient] 实现, 额外接受 URL-safe 字母表
 *   ('-'/'_' 按数据位解码)
 *
 * 注: java.net.URLEncoder.encode(value, charset) 由 AnalyzeUrlCore 的 encodeUrlParams
 * expect/actual 下沉到 jvmAndAndroidMain 端直接调用 (commonMain 无 kotlin.text.Charset),
 * 不在此处包装。
 */
expect object MimeBase64Decoder {
    fun decode(input: String): ByteArray
}
