package org.jsoup.internal

import io.legado.app.help.http.KmpHttpClient
import io.legado.app.help.http.KmpHttpClientBuilder
import io.legado.app.help.http.KmpRequestBody
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.toKmpMediaType
import io.legado.app.help.http.toKmpRequestBody
import io.legado.app.utils.InputStream
import io.legado.app.utils.textCharsetCodec
import okio.IOException
import org.jsoup.Connection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * jsoup 兼容层平台门面 nativeMain actual (iOS/鸿蒙共用, 纯 Kotlin)。
 *
 * 与 jvm 的差异 (仅平台 API 替换):
 * - [urlEncodeForm]: form 编码规则同 URLEncoder (空格 '+', 其余 %XX), 按 charset 取字节;
 * - [buildPlatformClient]: 无 connectTimeout 等价配置 (builder 默认 15s), 重定向默认不跟随
 *   (Ktor CIO / @ohos.net.http 均不自动重定向; jsoup 链上显式 followRedirects 由调用方控制,
 *   get/head/post 恒传 false, 与 native 默认一致), SSL 走系统信任库 (无 unsafe 模式,
 *   与整个 native HTTP 栈一致), 每次请求派生新 client (Ktor/ohos client 较重, 已知取舍);
 * - [buildMultipartBody]: 手动拼字节 (与 okhttp3.MultipartBody 结构对齐), 流字段全量读入内存;
 * - [decompressBody] 分端: iosMain 原样返回 (KmpResponse 构造时已解压), ohosMain okio 解压。
 */
internal actual fun urlEncodeForm(value: String, charset: String): String {
    val bytes = textCharsetCodec(charset).encode(value)
    val sb = StringBuilder(bytes.size)
    for (b in bytes) {
        val u = b.toInt() and 0xFF
        when {
            u in 'a'.code..'z'.code || u in 'A'.code..'Z'.code || u in '0'.code..'9'.code -> sb.append(u.toChar())
            u == ' '.code -> sb.append('+')
            u == '-'.code || u == '_'.code || u == '.'.code || u == '*'.code -> sb.append(u.toChar())
            else -> sb.append('%').append(u.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return sb.toString()
}

internal actual fun buildPlatformClient(
    request: HttpConnectionRequest,
    shared: KmpHttpClient?,
): KmpHttpClient {
    val builder = (shared?.newBuilder() ?: KmpHttpClientBuilder())
        .readTimeout(request.timeout().toLong().milliseconds)
    if (shared != null) {
        // jvm 端清掉共享 client 的 callTimeout(15s); native 端 0 = 未配置 → 默认 15s (尽力对齐)
        builder.callTimeout(Duration.ZERO)
    }
    return builder.build()
}

internal actual fun buildMultipartBody(
    data: Collection<Connection.KeyVal>,
    boundary: String
): KmpRequestBody {
    val CRLF = "\r\n"
    val parts = mutableListOf<ByteArray>()
    for (kv in data) {
        val stream = kv.inputStream()
        val sb = StringBuilder()
        sb.append("--").append(boundary).append(CRLF)
        if (stream != null) {
            val contentType = kv.contentType() ?: "application/octet-stream"
            // jsoup 语义: 文件字段的 value() 即 filename
            sb.append("Content-Disposition: form-data; name=\"").append(kv.key())
                .append("\"; filename=\"").append(kv.value()).append("\"").append(CRLF)
            sb.append("Content-Type: ").append(contentType).append(CRLF).append(CRLF)
            val headerBytes = sb.toString().encodeToByteArray()
            sb.setLength(0)
            val contentBytes = readFully(stream)
            parts.add(headerBytes + contentBytes + CRLF.encodeToByteArray())
        } else {
            sb.append("Content-Disposition: form-data; name=\"").append(kv.key()).append("\"").append(CRLF)
            sb.append(CRLF)
            sb.append(kv.value()).append(CRLF)
            parts.add(sb.toString().encodeToByteArray())
        }
    }
    val endBytes = ("--$boundary--" + CRLF).encodeToByteArray()
    val totalLength = parts.sumOf { it.size } + endBytes.size
    val bodyBytes = ByteArray(totalLength)
    var offset = 0
    for (part in parts) {
        part.copyInto(bodyBytes, destinationOffset = offset)
        offset += part.size
    }
    endBytes.copyInto(bodyBytes, destinationOffset = offset)
    return bodyBytes.toKmpRequestBody(("multipart/form-data; boundary=$boundary").toKmpMediaType())
}

/** 全量读取 [InputStream] (native InputStream 为内存/平台流, read 返回 -1 结束) */
private fun readFully(input: InputStream): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val r = input.read(buf)
        if (r < 0) break
        chunks.add(buf.copyOf(r))
        total += r
    }
    val result = ByteArray(total)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(result, destinationOffset = offset)
        offset += chunk.size
    }
    return result
}

internal actual fun defaultSharedHttpClient(): KmpHttpClient? =
    runCatching { OkHttpClientProviders.get().okHttpClient }.getOrNull()

internal actual fun uncheckedIoException(e: IOException): Throwable = e
