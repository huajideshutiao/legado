package org.jsoup.internal

import io.legado.app.help.http.KmpHttpClient
import io.legado.app.help.http.KmpRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink
import okio.IOException
import okio.source
import org.jsoup.Connection
import java.io.UncheckedIOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.io.inputStream
import kotlin.io.readBytes
import kotlin.io.use

/**
 * jsoup 兼容层平台门面 jvmAndAndroid actual。
 *
 * 全部委托原 JDK/OkHttp 实现, 与下沉前 [HttpConnection]/[HttpResponse] 内联实现逐行等价
 * (URLEncoder / GZIPInputStream / OkHttpClient.Builder / MultipartBody / UncheckedIOException)。
 */
internal actual fun urlEncodeForm(value: String, charset: String): String =
    URLEncoder.encode(value, charset)

internal actual fun decompressBody(bytes: ByteArray, contentEncoding: String?): ByteArray =
    when (contentEncoding) {
        "gzip" -> GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        "deflate" -> InflaterInputStream(bytes.inputStream(), Inflater(true)).use { it.readBytes() }
        else -> bytes
    }

internal actual fun buildPlatformClient(
    request: HttpConnectionRequest,
    shared: KmpHttpClient?,
): KmpHttpClient {
    // 宿主注入了共享 client 则 newBuilder 派生(继承拦截器/连接池),否则裸建
    val builder = (shared?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(request.timeout().toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(request.timeout().toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(request.followRedirects())
        .followSslRedirects(request.followRedirects())

    if (shared != null) {
        // 共享 client 带 callTimeout(15s),会盖过上面 per-request 的 connect/read 超时,清掉
        builder.callTimeout(0, TimeUnit.MILLISECONDS)
    } else {
        builder.hostnameVerifier(AllHostHostnameVerifier)
    }

    (request.sslSocketFactory() as? SSLSocketFactory)?.let { factory ->
        builder.sslSocketFactory(factory, UnsafeTrustManager)
    } ?: (request.sslContext() as? SSLContext)?.let { ctx ->
        builder.sslSocketFactory(ctx.socketFactory, UnsafeTrustManager)
    }

    return builder.build()
}

internal actual fun buildMultipartBody(
    data: Collection<Connection.KeyVal>,
    boundary: String
): KmpRequestBody {
    val body = okhttp3.MultipartBody.Builder(boundary)
        .setType(okhttp3.MultipartBody.FORM)

    for (kv in data) {
        val stream = kv.inputStream()
        if (stream != null) {
            val contentType = kv.contentType() ?: "application/octet-stream"
            val fileBody = object : RequestBody() {
                override fun contentType() = contentType.toMediaTypeOrNull()
                override fun contentLength() = -1L

                // 流只能消费一次,禁止 OkHttp 在重试/重定向时二次 writeTo
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) {
                    stream.source().use { sink.writeAll(it) }
                }
            }
            // jsoup 语义:文件字段的 value() 即 filename
            body.addFormDataPart(kv.key(), kv.value(), fileBody)
        } else {
            body.addFormDataPart(kv.key(), kv.value())
        }
    }

    return body.build()
}

internal actual fun defaultSharedHttpClient(): KmpHttpClient? = null

internal actual fun uncheckedIoException(e: IOException): Throwable = UncheckedIOException(e)

/** 兼容所有 host 的 HostnameVerifier,与 jsoup 默认行为对齐 */
private object AllHostHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String, session: javax.net.ssl.SSLSession): Boolean = true
}

/**
 * 信任所有证书的 TrustManager,仅在调用方主动传入 [SSLContext] / [SSLSocketFactory] 时使用
 * (JsExtensions 传入的 SSLHelper.unsafeSslContext 即期望信任全部)
 */
private object UnsafeTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

/** 私有可用作 SSLContext 默认初始化的工厂 (内部使用) */
internal val UnsafeSslContext: SSLContext by lazy {
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(UnsafeTrustManager), SecureRandom())
    }
}
