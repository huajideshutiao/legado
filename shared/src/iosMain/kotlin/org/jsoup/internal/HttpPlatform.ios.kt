package org.jsoup.internal

/**
 * jsoup 兼容层平台门面 iosMain actual。
 *
 * 与 jvm 的差异: [decompressBody] 原样返回 —— Ktor CIO 层的 [KmpResponse] 在构造时已按
 * Content-Encoding 透明解压 (见 KmpHttpTypes.ios.kt decompressResponseBody) 且响应头未剥离,
 * 这里再解压会二次解压。
 */
internal actual fun decompressBody(bytes: ByteArray, contentEncoding: String?): ByteArray = bytes
