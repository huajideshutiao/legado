@file:Suppress("unused")
@file:OptIn(ExperimentalEncodingApi::class)

package io.legado.app.help.http

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.Closeable
import io.legado.app.utils.InputStream
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.toInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.experimental.ExperimentalEncodingApi
import kotlin.io.encoding.Base64
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * OkHttp 跨平台抽象层 ohosMain Actual 实现 (基于 OHOS @ohos.net.http napi 桥接)。
 *
 * Ktor 3.1.0 未发布 ohosArm64 变体, 改用鸿蒙原生 @ohos.net.http (ArkTS API)。
 * 通过 [OhosNativeBridge.invokeHttpSync] 把请求 dispatch 到 ArkTS 主线程执行,
 * ArkTS 侧 [HttpBridgeHandler] 调 `http.createHttp().request(url, options, callback)` 完成真实请求。
 *
 * 详见 commonMain/kotlin/io/legado/app/help/http/KmpHttpTypes.kt expect 注释。
 *
 * ## 请求/响应跨语言协议 (与 HttpBridgeHandler.ets 对齐)
 * - 请求 payload (Kotlin → ArkTS): [HttpRequestPayload], body 用 base64 编码
 * - 响应 payload (ArkTS → Kotlin): [HttpResponsePayload], body 用 base64 编码
 *
 * ## 与 nativeMain (iosMain) 的差异
 * - iosMain 用 Ktor HttpClient (CIO engine, 纯 Kotlin 协程发起请求);
 * - ohosMain 用 @ohos.net.http (ArkTS API, 经 napi tsfn 跨线程 dispatch)。
 */

// region JSON 协议数据类 (与 HttpBridgeHandler.ets 对齐)
@Serializable
internal data class HttpHeader(val name: String, val value: String)

@Serializable
internal data class HttpRequestPayload(
    val url: String,
    val method: String,
    val headers: List<HttpHeader>,
    val body: String? = null,
    val contentType: String? = null,
    // callTimeout 作为 ArkTS 端 connectTimeout (整体连接阶段上限)
    val timeoutMs: Long = 0L,
    // readTimeout 单独透传, 0 时 ArkTS 端回退用 timeoutMs (与 OkHttp readTimeout 语义对齐)
    val readTimeoutMs: Long = 0L
)

@Serializable
internal data class HttpResponsePayload(
    val ok: Boolean,
    val code: Int = 0,
    val message: String = "",
    val headers: List<HttpHeader> = emptyList(),
    val body: String? = null,
    val error: String? = null
)
// endregion

// region Interceptor / Chain —— ohos 不用 Interceptor (OkHttp 概念), 接口为编译占位
actual interface KmpInterceptor {
    actual fun intercept(chain: KmpInterceptorChain): KmpResponse
}

actual interface KmpInterceptorChain {
    actual fun request(): KmpRequest
    actual fun proceed(request: KmpRequest): KmpResponse
}
// endregion

// region HttpClient / Builder —— 持有 timeout 配置, newCall 创建 OhosKmpCall
actual class KmpHttpClient {
    internal var callTimeoutMillis: Long = 0L
        private set
    internal var readTimeoutMillis: Long = 0L
        private set

    actual constructor()

    internal constructor(callTimeoutMillis: Long, readTimeoutMillis: Long) {
        this.callTimeoutMillis = callTimeoutMillis
        this.readTimeoutMillis = readTimeoutMillis
    }

    actual fun newCall(request: KmpRequest): KmpCall = OhosKmpCall(this, request)

    actual fun newBuilder(): KmpHttpClientBuilder = KmpHttpClientBuilder().also {
        it.callTimeoutMillis = callTimeoutMillis
        it.readTimeoutMillis = readTimeoutMillis
    }
}

actual class KmpHttpClientBuilder {
    internal var readTimeoutMillis: Long = 0L
    internal var callTimeoutMillis: Long = 0L

    actual fun readTimeout(duration: Duration): KmpHttpClientBuilder {
        readTimeoutMillis = duration.inWholeMilliseconds
        return this
    }

    actual fun callTimeout(duration: Duration): KmpHttpClientBuilder {
        callTimeoutMillis = duration.inWholeMilliseconds
        return this
    }

    actual fun build(): KmpHttpClient = KmpHttpClient(callTimeoutMillis, readTimeoutMillis)
}
// endregion

// region Request / Builder —— 数据载体, 持有 url/method/headers/body
actual class KmpRequest {
    internal var urlStr: String = "http://localhost/"
        private set
    internal var method: String = "GET"
        private set
    internal var headers: List<Pair<String, String>> = emptyList()
        private set
    internal var body: KmpRequestBody? = null
        private set

    actual constructor()

    internal constructor(
        urlStr: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: KmpRequestBody?
    ) {
        this.urlStr = urlStr
        this.method = method
        this.headers = headers
        this.body = body
    }

    actual fun newBuilder(): KmpRequestBuilder {
        return KmpRequestBuilder().also { b ->
            b.urlStr = urlStr
            b.method = method
            b.headers.addAll(headers)
            b.body = body
        }
    }

    actual fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    actual val url: KmpHttpUrl
        get() = KmpHttpUrl(urlStr)
}

actual class KmpRequestBuilder() {
    internal var urlStr: String = "http://localhost/"
    internal var method: String = "GET"
    internal val headers: MutableList<Pair<String, String>> = mutableListOf()
    internal var body: KmpRequestBody? = null

    actual fun url(url: String): KmpRequestBuilder {
        urlStr = url
        return this
    }

    actual fun url(url: KmpHttpUrl): KmpRequestBuilder {
        urlStr = url.urlStr ?: "http://localhost/"
        return this
    }

    actual fun addHeader(name: String, value: String): KmpRequestBuilder {
        headers.add(name to value)
        return this
    }

    // OkHttp header() 替换同名 header; 移除已有同名再添加
    actual fun header(name: String, value: String): KmpRequestBuilder {
        headers.removeAll { it.first.equals(name, ignoreCase = true) }
        headers.add(name to value)
        return this
    }

    actual fun removeHeader(name: String): KmpRequestBuilder {
        headers.removeAll { it.first.equals(name, ignoreCase = true) }
        return this
    }

    actual fun get(): KmpRequestBuilder {
        method = "GET"
        body = null
        return this
    }

    actual fun post(body: KmpRequestBody): KmpRequestBuilder {
        method = "POST"
        this.body = body
        return this
    }

    actual fun method(method: String, body: KmpRequestBody?): KmpRequestBuilder {
        this.method = method.uppercase()
        this.body = body
        return this
    }

    actual fun build(): KmpRequest {
        return KmpRequest(urlStr, method, headers.toList(), body)
    }
}
// endregion

// region Response / Builder / Body —— 数据载体, 持有 code/message/headers/body bytes
actual class KmpResponse : Closeable {
    internal var codeVal: Int = 200
        private set
    internal var messageVal: String = "OK"
        private set
    internal var headersVal: Map<String, List<String>> = emptyMap()
        private set
    internal var bodyBytes: ByteArray? = null
        private set
    internal var contentTypeStr: String? = null
        private set
    internal var requestVal: KmpRequest = KmpRequest()
        private set

    actual constructor()

    internal constructor(
        code: Int,
        message: String,
        headers: Map<String, List<String>>,
        body: ByteArray?,
        contentType: String?,
        request: KmpRequest
    ) {
        this.codeVal = code
        this.messageVal = message
        this.headersVal = headers
        this.bodyBytes = body
        this.contentTypeStr = contentType
        this.requestVal = request
    }

    actual val code: Int get() = codeVal
    actual val message: String get() = messageVal
    actual val body: KmpResponseBody
        get() = OhosKmpResponseBody(bodyBytes ?: ByteArray(0), contentTypeStr)
    actual val isSuccessful: Boolean get() = codeVal in 200..299
    actual val request: KmpRequest get() = requestVal
    // @ohos.net.http 自动处理重定向, 不暴露这些信息, 占位返回 null/false
    actual val networkResponse: KmpResponse? get() = null
    actual val priorResponse: KmpResponse? get() = null
    actual val isRedirect: Boolean get() = codeVal in 300..399

    actual fun headers(): KmpHeaders = KmpHeaders(headersVal)

    actual fun newBuilder(): KmpResponseBuilder {
        return KmpResponseBuilder().also { b ->
            b.codeVal = codeVal
            b.messageVal = messageVal
            b.headersVal.putAll(headersVal)
            b.bodyBytes = bodyBytes
            b.contentTypeStr = contentTypeStr
            b.requestVal = requestVal
        }
    }

    actual override fun close() {
        // body 已读入内存, 无底层流需关闭
    }
}

// header 扩展函数 actual: 从 headersVal 查找首个匹配 (不区分大小写, 与 OkHttp 行为一致)
actual fun KmpResponse.header(name: String, defaultValue: String?): String? {
    return headersVal.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        ?: defaultValue
}

actual class KmpResponseBuilder() {
    internal var codeVal: Int = 200
    internal var messageVal: String = "OK"
    internal val headersVal: MutableMap<String, List<String>> = LinkedHashMap()
    internal var bodyBytes: ByteArray? = null
    internal var contentTypeStr: String? = null
    internal var requestVal: KmpRequest = KmpRequest()

    actual fun code(code: Int): KmpResponseBuilder {
        codeVal = code
        return this
    }

    actual fun message(message: String): KmpResponseBuilder {
        messageVal = message
        return this
    }

    // @ohos.net.http 不暴露 Protocol 概念, 忽略入参
    actual fun protocol(protocol: KmpProtocol): KmpResponseBuilder = this

    actual fun request(request: KmpRequest): KmpResponseBuilder {
        requestVal = request
        return this
    }

    actual fun removeHeader(name: String): KmpResponseBuilder {
        headersVal.keys.removeAll { it.equals(name, ignoreCase = true) }
        return this
    }

    actual fun body(body: KmpResponseBody): KmpResponseBuilder {
        val ohosBody = body as? OhosKmpResponseBody
        bodyBytes = ohosBody?.bytesValue
        contentTypeStr = ohosBody?.contentTypeValue
        return this
    }

    actual fun build(): KmpResponse {
        return KmpResponse(codeVal, messageVal, headersVal, bodyBytes, contentTypeStr, requestVal)
    }
}

// 包装已缓存的字节数组 (ArkTS 侧响应 body 经 base64 解码后一次性读到内存)
actual abstract class KmpResponseBody : Closeable {
    actual fun bytes(): ByteArray = (this as OhosKmpResponseBody).bytesValue
    actual fun byteStream(): InputStream = (this as OhosKmpResponseBody).bytesValue.toInputStream()
    actual abstract fun contentType(): KmpMediaType?
    actual fun string(): String = bytes().toString(Charsets.UTF_8)
    actual override fun close() {
        // 内存字节数组, 无需关闭
    }

    actual companion object {
        actual val EMPTY: KmpResponseBody
            get() = OhosKmpResponseBody(ByteArray(0), null)
    }
}

internal class OhosKmpResponseBody(
    internal val bytesValue: ByteArray,
    private val contentTypeStr: String?
) : KmpResponseBody() {
    override fun contentType(): KmpMediaType? = contentTypeStr?.let { OhosKmpMediaType(it) }
}
// endregion

// region Call / Callback —— enqueue 用协程包裹 execute, execute 用 invokeHttpSync 同步等待
actual interface KmpCall {
    actual fun enqueue(responseCallback: KmpCallback)
    actual fun cancel()
    actual fun execute(): KmpResponse
}

actual interface KmpCallback {
    actual fun onFailure(call: KmpCall, e: okio.IOException)
    actual fun onResponse(call: KmpCall, response: KmpResponse)
}

/**
 * ohosMain 端 [KmpCall] 实现: execute 通过 [OhosNativeBridge.invokeHttpSync] 调用 ArkTS @ohos.net.http。
 *
 * - [enqueue] 启动协程异步执行, 完成后回调 [KmpCallback]
 * - [execute] 同步阻塞当前线程 (invokeHttpSync 内部 runBlocking 等待 ArkTS 回调)
 * - [cancel] 取消协程 Job (enqueue 场景); execute 场景靠 timeoutMs 兜底
 */
internal class OhosKmpCall(
    private val client: KmpHttpClient,
    private val request: KmpRequest
) : KmpCall {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun enqueue(responseCallback: KmpCallback) {
        job = scope.launch {
            try {
                val response = execute()
                responseCallback.onResponse(this@OhosKmpCall, response)
            } catch (e: Exception) {
                // 转 okio.IOException (与 OkHttp Callback.onFailure 签名对齐)
                val ioException = if (e is okio.IOException) e else okio.IOException(e.message, e)
                responseCallback.onFailure(this@OhosKmpCall, ioException)
            }
        }
    }

    override fun cancel() {
        job?.cancel()
        scope.cancel()
    }

    override fun execute(): KmpResponse {
        // 构造请求 payload (body base64 编码)
        val bodyBytes = (request.body as? OhosKmpRequestBody)?.bytes
        val payload = HttpRequestPayload(
            url = request.urlStr,
            method = request.method,
            headers = request.headers.map { HttpHeader(it.first, it.second) },
            body = bodyBytes?.let { Base64.encode(it) },
            contentType = (request.body as? OhosKmpRequestBody)?.contentType?.toString(),
            timeoutMs = client.callTimeoutMillis,
            readTimeoutMs = client.readTimeoutMillis
        )
        val payloadJson = KS_JSON.encodeToString(payload)
        // 桥接超时: 取用户配置的 call/read timeout 较大者 + 10s 缓冲; 都为 0 (未配置) 用 5min 默认允许大文件下载
        val effectiveTimeout = maxOf(client.callTimeoutMillis, client.readTimeoutMillis)
        val bridgeTimeoutMs = if (effectiveTimeout > 0) effectiveTimeout + 10_000L else 300_000L
        // 同步等待 ArkTS 返回 (invokeHttpSync 内部 runBlocking)
        val resultJson = OhosNativeBridge.invokeHttpSync("execute", payloadJson, bridgeTimeoutMs)
            ?: throw okio.IOException("HTTP bridge not ready or timeout")
        val resp = KS_JSON.decodeFromString<HttpResponsePayload>(resultJson)
        if (!resp.ok) {
            throw okio.IOException(resp.error ?: "HTTP request failed")
        }
        // 解析响应 (body base64 解码)
        val respBody = resp.body?.let { Base64.decode(it) } ?: ByteArray(0)
        val contentType = resp.headers.firstOrNull { it.name.equals("Content-Type", true) }?.value
        val headersMap = resp.headers.groupBy({ it.name }, { it.value })
        return KmpResponse(resp.code, resp.message, headersMap, respBody, contentType, request)
    }
}
// endregion

// region FormBody / HttpUrl / MediaType / RequestBody / Headers / Protocol
actual class KmpFormBodyBuilder() {
    private val entries: MutableList<Pair<String, String>> = mutableListOf()

    actual fun add(name: String, value: String): KmpFormBodyBuilder {
        entries.add(name to value)
        return this
    }

    actual fun addEncoded(name: String, value: String): KmpFormBodyBuilder {
        // 入参已 URL 编码, 直接保存 (与 OkHttp addEncoded 行为一致)
        entries.add(name to value)
        return this
    }

    // 构造 form-urlencoded body (与 OkHttp FormBody 编码对齐)
    internal fun buildFormBody(): KmpRequestBody {
        val sb = StringBuilder()
        entries.forEachIndexed { idx, (k, v) ->
            if (idx > 0) sb.append('&')
            sb.append(urlEncodeForm(k)).append('=').append(urlEncodeForm(v))
        }
        return OhosKmpRequestBody(
            sb.toString().toByteArray(Charsets.UTF_8),
            OhosKmpMediaType("application/x-www-form-urlencoded")
        )
    }
}

actual fun KmpFormBodyBuilder.buildKmpRequestBody(): KmpRequestBody = this.buildFormBody()

// 包装 URL 字符串, 不做 OkHttp toHttpUrl() 级别规范化 (由 @ohos.net.http 内部解析)
actual class KmpHttpUrl {
    internal var urlStr: String? = null
        private set

    actual constructor()

    internal constructor(urlStr: String) {
        this.urlStr = urlStr
    }

    actual fun newBuilder(): KmpHttpUrlBuilder {
        return KmpHttpUrlBuilder(urlStr ?: "http://localhost/")
    }

    override fun toString(): String = urlStr ?: ""
}

actual class KmpHttpUrlBuilder {
    private val baseUrl: String
    private var encodedQueryStr: String? = null
    private val queryParams: MutableList<Pair<String, String>> = mutableListOf()

    constructor(urlStr: String = "http://localhost/") {
        baseUrl = urlStr.substringBefore('?')
        urlStr.substringAfter('?', "").ifNotEmpty { s ->
            s.split('&').forEach { pair ->
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                queryParams.add(k to v)
            }
        }
    }

    actual fun encodedQuery(encodedQuery: String?): KmpHttpUrlBuilder {
        encodedQueryStr = encodedQuery
        return this
    }

    actual fun addQueryParameter(name: String, value: String?): KmpHttpUrlBuilder {
        queryParams.add(name to (value ?: ""))
        return this
    }

    actual fun addEncodedQueryParameter(encodedName: String, encodedValue: String?): KmpHttpUrlBuilder {
        queryParams.add(encodedName to (encodedValue ?: ""))
        return this
    }

    actual fun build(): KmpHttpUrl {
        val sb = StringBuilder(baseUrl)
        if (encodedQueryStr != null) {
            sb.append('?').append(encodedQueryStr)
        } else if (queryParams.isNotEmpty()) {
            sb.append('?')
            queryParams.forEachIndexed { idx, (k, v) ->
                if (idx > 0) sb.append('&')
                sb.append(urlEncodeQuery(k)).append('=').append(urlEncodeQuery(v))
            }
        }
        return KmpHttpUrl(sb.toString())
    }
}

actual class KmpMediaType {
    internal var value: String = ""
        private set

    actual constructor()

    internal constructor(value: String) {
        this.value = value
    }

    override fun toString(): String = value
}

actual abstract class KmpRequestBody

// ohosMain 端 KmpRequestBody 真实实现: 持有字节数据 + MediaType (在 OhosKmpCall 中 base64 编码传给 ArkTS)
internal class OhosKmpRequestBody(
    internal val bytes: ByteArray,
    internal val contentType: KmpMediaType?
) : KmpRequestBody()

actual class KmpHeaders {
    private var map: Map<String, List<String>> = emptyMap()

    actual constructor()

    constructor(map: Map<String, List<String>>) {
        this.map = map
    }

    actual fun toMultimap(): Map<String, List<String>> = map
}

actual enum class KmpProtocol {
    HTTP_1_0, HTTP_1_1, SPDY_3, HTTP_2, H2_PRIOR_KNOWLEDGE, QUIC
}
// endregion

// region 扩展函数 actual 实现
actual fun String.toKmpHttpUrl(): KmpHttpUrl = KmpHttpUrl(this)

actual fun String.toKmpMediaType(): KmpMediaType = OhosKmpMediaType(this)

actual fun String.toKmpRequestBody(contentType: KmpMediaType?): KmpRequestBody =
    OhosKmpRequestBody(this.toByteArray(Charsets.UTF_8), contentType)

actual fun ByteArray.toKmpRequestBody(contentType: KmpMediaType?): KmpRequestBody =
    OhosKmpRequestBody(this, contentType)

// OkHttp tag 用于拦截器中按类型取请求关联对象; ohos 不用 Interceptor, no-op
actual fun <T : Any> KmpRequestBuilder.tagKmp(tagClass: KClass<T>, tag: T?): KmpRequestBuilder = this

// commonMain DecompressInterceptor 用此扩展包装 BufferedSource; ohos 端 DecompressPlatform 是 stub, 兜底返回空 body
actual fun Any?.asKmpResponseBody(contentType: KmpMediaType?, length: Long): KmpResponseBody {
    val bytes = (this as? ByteArray) ?: ByteArray(0)
    return OhosKmpResponseBody(bytes, contentType?.toString())
}
// endregion

// region 内部辅助
internal class OhosKmpMediaType(value: String) : KmpMediaType(value)

// form-urlencoded 编码 (与 OkHttp FormBody 编码对齐): 空格 '+', 其他 %XX
private fun urlEncodeForm(s: String): String {
    val sb = StringBuilder(s.length)
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val u = b.toInt() and 0xFF
        when {
            u in 'a'.code..'z'.code || u in 'A'.code..'Z'.code || u in '0'.code..'9'.code -> sb.append(u.toChar())
            u == ' '.code -> sb.append('+')
            u == '-'.code || u == '_'.code || u == '.'.code || u == '*'.code -> sb.append(u.toChar())
            else -> sb.append('%').append("%02X".format(u))
        }
    }
    return sb.toString()
}

// query 参数编码 (与 OkHttp HttpUrl.Builder.addQueryParameter 对齐): 空格 %20
private fun urlEncodeQuery(s: String): String {
    val sb = StringBuilder(s.length)
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val u = b.toInt() and 0xFF
        when {
            u in 'a'.code..'z'.code || u in 'A'.code..'Z'.code || u in '0'.code..'9'.code -> sb.append(u.toChar())
            u == '-'.code || u == '_'.code || u == '.'.code || u == '~'.code -> sb.append(u.toChar())
            else -> sb.append('%').append("%02X".format(u))
        }
    }
    return sb.toString()
}

private inline fun String.ifNotEmpty(block: (String) -> Unit) {
    if (isNotEmpty()) block(this)
}
// endregion
