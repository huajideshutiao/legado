package io.legado.app.help.http

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * [HttpClient] 的 OkHttp 实现（jvmAndAndroid 平台）。
 *
 * - 将 [HttpRequest] 转换为 `okhttp3.Request`；
 * - 将 `okhttp3.Response` 包装为 [HttpResponse]；
 * - 通过构造时注入的 [OkHttpClient] 执行；
 * - 协程取消通过 commonMain 中已有的 `Call.await()` 扩展支持。
 *
 * 与 [OkHttpClientProvider] 协同：app 端启动时构造并注册：
 * ```
 * HttpClients.register(OkHttpHttpClient(OkHttpClientProviders.get().okHttpClient))
 * ```
 *
 * iOS/HarmonyOS 端写 `KtorHttpClient` 时，HTTP 语义应与本实现保持一致。
 */
class OkHttpHttpClient(
    private val client: OkHttpClient
) : HttpClient {

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val okRequest = request.toOkRequest()
        // 复用 commonMain 中 OkHttpUtils.kt 声明的 Call.await() 扩展，获得协程取消支持
        val okResponse = client.newCall(okRequest).await()
        return okResponse.toHttpResponse()
    }

    /** [HttpRequest] -> `okhttp3.Request` */
    private fun HttpRequest.toOkRequest(): Request {
        val builder = Request.Builder()

        // url + query
        val httpUrlBuilder = url.toHttpUrl().newBuilder()
        if (encodedQuery != null) {
            httpUrlBuilder.encodedQuery(encodedQuery)
        } else {
            queryParameters.forEach { (key, values) ->
                values.forEach { value ->
                    httpUrlBuilder.addQueryParameter(key, value)
                }
            }
        }
        builder.url(httpUrlBuilder.build())

        // headers（同名多值用 addHeader 保留全部）
        headers.forEach { (key, values) ->
            values.forEach { value ->
                builder.addHeader(key, value)
            }
        }

        // method + body
        when (method) {
            HttpMethod.GET -> builder.get()
            HttpMethod.HEAD -> builder.head()
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.DELETE,
            HttpMethod.PATCH,
            HttpMethod.OPTIONS -> {
                val okBody = body?.toOkBody()
                // POST/PUT/PATCH 通常需要 body；若为 null OkHttp 会写成空 body
                builder.method(method.name, okBody)
            }
        }

        return builder.build()
    }

    /** [HttpRequestBody] -> `okhttp3.RequestBody` */
    private fun HttpRequestBody.toOkBody(): okhttp3.RequestBody {
        val mediaType = contentType?.toMediaType()
        return when (this) {
            is HttpRequestBody.Text -> text.toRequestBody(mediaType)
            is HttpRequestBody.Bytes -> bytes.toRequestBody(mediaType)
            is HttpRequestBody.Form -> {
                val formBody = FormBody.Builder()
                fields.forEach { (key, values) ->
                    values.forEach { value ->
                        if (encoded) {
                            formBody.addEncoded(key, value)
                        } else {
                            formBody.add(key, value)
                        }
                    }
                }
                formBody.build()
            }
        }
    }

    /** `okhttp3.Response` -> [HttpResponse] */
    private fun Response.toHttpResponse(): HttpResponse {
        val raw = this
        return object : HttpResponse {
            override val code: Int = raw.code
            override val message: String = raw.message
            override val headers: Map<String, List<String>> = raw.headers.toMultimap()
            override val body: HttpResponseBody = OkHttpResponseBody(raw.body)
            override val requestUrl: String = raw.request.url.toString()
        }
    }
}

/**
 * [HttpResponseBody] 的 OkHttp 实现。
 *
 * 包装 `okhttp3.ResponseBody`，遵守 OkHttp "body 只能消费一次" 的语义：
 * 调用 [string] 或 [bytes] 后再次读取会抛异常，由调用方自行控制。
 */
private class OkHttpResponseBody(
    private val okBody: ResponseBody?
) : HttpResponseBody {
    override val contentType: String?
        get() = okBody?.contentType()?.toString()

    override val contentLength: Long
        get() = okBody?.contentLength() ?: -1L

    override fun string(): String = okBody?.string() ?: ""

    override fun bytes(): ByteArray = okBody?.bytes() ?: ByteArray(0)

    override fun close() {
        okBody?.close()
    }
}
