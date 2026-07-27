package io.legado.app.utils

/**
 * JVM 专属编码 API 的 commonMain expect 门面。
 *
 * AnalyzeUrlCore 下沉 commonMain 后, getByteArrayIfDataUri 使用
 * java.util.Base64.getMimeDecoder().decode(...), JVM-only, 需 expect/actual 包装。
 *
 * - [MimeBase4Decoder.decode]: 对齐 java.util.Base64.getMimeDecoder().decode(String), 行为不变
 *   (MIME decoder 宽松处理非法 base64 字符, 与 AnalyzeUrlCore.getByteArrayIfDataUri 原语义一致)
 *
 * 注: java.net.URLEncoder.encode(value, charset) 由 AnalyzeUrlCore 的 encodeUrlParams
 * expect/actual 下沉到 jvmAndAndroidMain 端直接调用 (commonMain 无 kotlin.text.Charset),
 * 不在此处包装。
 */
expect object MimeBase64Decoder {
    fun decode(input: String): ByteArray
}
