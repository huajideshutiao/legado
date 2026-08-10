package org.jsoup.internal

import okio.Buffer
import okio.GzipSource
import okio.Inflater
import okio.InflaterSource
import okio.buffer
import okio.use

/**
 * jsoup 兼容层平台门面 ohosMain actual。
 *
 * 与 jvm/ios 的差异: @ohos.net.http 不透明解压 (见 KmpHttpTypes.ohos.kt 注释), 响应 body 是
 * 原始字节, 需按 Content-Encoding 手动解压 (okio 纯 Kotlin 实现, 与 ios KmpHttpTypes 同款算法;
 * 解压失败回退原字节, 与 ios Ktor 层同策略)。
 */
internal actual fun decompressBody(bytes: ByteArray, contentEncoding: String?): ByteArray =
    when (contentEncoding) {
        "gzip" -> runCatching {
            GzipSource(Buffer().write(bytes)).buffer().use { it.readByteArray() }
        }.getOrDefault(bytes)

        "deflate" -> runCatching {
            InflaterSource(Buffer().write(bytes), Inflater(true)).buffer()
                .use { it.readByteArray() }
        }.getOrDefault(bytes)

        else -> bytes
    }
