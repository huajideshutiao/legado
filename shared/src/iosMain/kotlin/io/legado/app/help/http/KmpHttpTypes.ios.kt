package io.legado.app.help.http

import io.legado.app.utils.Closeable
import io.legado.app.utils.InputStream
import io.legado.app.utils.toInputStream
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * OkHttp 跨平台抽象层 nativeMain Actual 实现 (基于 Ktor 3.1.0 CIO engine)。
 *
 * 由 iosMain / ohosMain 共用 (nativeMain 中间源集下沉, 原 iosMain/ohosMain actual 完全一致,
 * 仅 Ios/Ohos 类前缀差异, 统一改为 Native 前缀)。
 *
 * 详见 commonMain/kotlin/io/legado/app/help/http/KmpHttpTypes.kt expect 注释。
 *
 * ## 实现方式
 * iOS/鸿蒙 target 没有 OkHttp 5.3.2 变体 (OkHttp 仅发布 common + android + jvm),
 * 故所有 Kmp* 类型在 nativeMain 用真实 class/interface 包装 Ktor HttpClient 实现:
 *
 * - [KmpHttpClient] 内部持有 [HttpClient] (CIO engine, 纯 Kotlin 跨平台,
 *   iOS iosArm64/iosX64/iosSimulatorArm64 与鸿蒙 linuxArm64 变体均已发布),
 *   `newCall` 把 [KmpRequest] 转为 [HttpRequestBuilder] 并委托 Ktor 发起请求;
 * - [KmpRequest] / [KmpResponse] / [KmpResponseBody] 等是数据载体类, 不直接暴露 Ktor 类型;
 * - [KmpCall.enqueue] 用协程 [CoroutineScope] 包裹 Ktor 的 suspend 调用, 完成后回调 [KmpCallback];
 * - [KmpCall.execute] 用 [runBlocking] 把 suspend 转同步 (与 OkHttp 同步 API 对齐)。
 *
 * ## expect/actual constructor 规则
 * commonMain 中 expect class 多数未显式声明 constructor (隐式 public 无参),
 * 故 actual class 必须提供 public 无参 primary constructor (字段用 nullable + lazy 初始化);
 * 同时提供 internal secondary constructor 接收实际参数, 由 builder / 工厂方法调用。
 * commonMain 不直接 `KmpRequest()` 等构造, 无参 constructor 仅用于编译期匹配。
 *
 * ## 与 jvmAndAndroidMain 行为差异
 * - URL 规范化: Ktor 内部用 [io.ktor.http.Url] 解析, 不做 OkHttp `toHttpUrl()` 级别的默认端口移除/路径合并;
 *   功能等价, URL 字符串可能略不同
 * - 响应体: nativeMain 端 KmpResponseBody 在构造时一次性 `bodyAsBytes()` 缓存到内存,
 *   OkHttp 原生 ResponseBody 是流式; 大文件场景内存占用更高 (与 WebDav.native.kt 同样降级)
 * - 协议枚举: Ktor 不暴露 Protocol 概念, [KmpResponseBuilder.protocol] 入参被忽略,
 *   [KmpResponse.networkResponse] / [priorResponse] / [isRedirect] 等少数成员为占位
 *
 * ## 编译期作用
 * 让 commonMain 中的 OkHttpUtils/DecompressInterceptor/AnalyzeUrlCore/StrResponse 等
 * 在 iOS/鸿蒙 target 编译通过且**运行时可用** (替代 KP4 时期的 stub)。
 */

// region Interceptor / Chain —— nativeMain 端不实际使用 Interceptor (OkHttp 概念), 接口为编译占位
actual interface KmpInterceptor {
    actual fun intercept(chain: KmpInterceptorChain): KmpResponse
}

actual interface KmpInterceptorChain {
    actual fun request(): KmpRequest
    actual fun proceed(request: KmpRequest): KmpResponse
}
// endregion

// region HttpClient / Builder —— 用 Ktor HttpClient 包装
/**
 * nativeMain 端 [KmpHttpClient] 实现: 内部持有 Ktor [HttpClient] (CIO engine)。
 *
 * - [newCall] 创建 [NativeKmpCall], 持有 [KmpRequest] 与 [HttpClient] 引用;
 * - [newBuilder] 返回新 [KmpHttpClientBuilder], 复制现有配置 (timeout 等)。
 *
 * 注: [ktorClient] 是 nullable, 无参 constructor 创建的实例未初始化, 调用 [newCall] 会抛异常;
 * 实际使用通过 [KmpHttpClientBuilder.build] 创建已初始化的实例。
 */
actual class KmpHttpClient {
    internal var ktorClient: HttpClient? = null
        private set
    private var readTimeoutMillis: Long = 0L
    private var callTimeoutMillis: Long = 0L

    // 给 expect class 匹配的 public 无参 constructor (commonMain 不直接调用)
    actual constructor()

    internal constructor(
        ktorClient: HttpClient,
        readTimeoutMillis: Long,
        callTimeoutMillis: Long
    ) {
        this.ktorClient = ktorClient
        this.readTimeoutMillis = readTimeoutMillis
        this.callTimeoutMillis = callTimeoutMillis
    }

    actual fun newCall(request: KmpRequest): KmpCall {
        val client = ktorClient
            ?: throw IllegalStateException("KmpHttpClient not initialized (use KmpHttpClientBuilder.build())")
        return NativeKmpCall(client, request)
    }

    actual fun newBuilder(): KmpHttpClientBuilder {
        return KmpHttpClientBuilder().also {
            it.readTimeoutMillis = readTimeoutMillis
            it.callTimeoutMillis = callTimeoutMillis
        }
    }
}

/**
 * nativeMain 端 [KmpHttpClientBuilder] 实现: 累积 timeout 配置, [build] 时构造 Ktor [HttpClient]。
 *
 * Ktor CIO engine 通过 [io.ktor.client.engine.cio.CIOEngineConfig] 配置:
 * - `requestTimeout` 对应 OkHttp callTimeout (整个请求周期上限)
 * - `endpoint.connectTimeout` / `endpoint.requestTimeout` 等其他参数保持 Ktor 默认
 *
 * 注: readTimeout 在 Ktor 中无直接等价物 (Ktor 是协程模型, 读超时由 socket 层处理),
 * 这里只记录参数, 不实际生效 (与 jvmAndAndroidMain 行为基本一致, OkHttp readTimeout 影响单次 read 阻塞)。
 */
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

    actual fun build(): KmpHttpClient {
        // CIO engine 配置: requestTimeout 对应 OkHttp callTimeout; 0 表示无超时 (OkHttp 行为)
        val engineTimeout = if (callTimeoutMillis > 0) callTimeoutMillis else Long.MAX_VALUE
        val client = HttpClient(CIO) {
            engine {
                requestTimeout = engineTimeout
            }
        }
        return KmpHttpClient(client, readTimeoutMillis, callTimeoutMillis)
    }
}
// endregion

// region Request / Builder —— 数据载体, 持有 url/method/headers/body
/**
 * nativeMain 端 [KmpRequest] 实现: 数据载体, 持有请求 URL/method/headers/body。
 *
 * 由 [KmpRequestBuilder.build] 构造, 在 [NativeKmpCall] 内转为 Ktor [HttpRequestBuilder]。
 */
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

    actual fun header(name: String, value: String): KmpRequestBuilder {
        // OkHttp header() 替换同名 header; 这里移除已有同名, 再添加 (行为对齐)
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

// region Response / Builder / Body —— 用 Ktor HttpResponse 包装
/**
 * nativeMain 端 [KmpResponse] 实现: 持有 Ktor [HttpResponse] + 已缓存的 body bytes。
 *
 * - [code] / [message] / [headers] / [isSuccessful] 直接从 [HttpResponse] 读取
 * - [body] 是 [NativeKmpResponseBody], **构造时一次性 bodyAsBytes() 读到内存缓存**
 *   (Ktor 流只能读一次, 缓存后可多次访问 body, 与 OkHttp 行为对齐)
 * - [networkResponse] / [priorResponse] / [isRedirect] 是 OkHttp 重定向相关成员,
 *   nativeMain 端 Ktor 自动处理重定向, 不暴露这些信息, 占位返回 null/false
 * - [request] 返回原始 [KmpRequest] (Ktor HttpResponse.call 不直接暴露 OkHttp 风格 request)
 *
 * 注: 构造时即读取 body, 失败 bodyBytes 为 null (后续 body 返回空 ResponseBody);
 * 调用方应在协程上下文中构造 (runBlocking 内 bodyAsBytes)。
 */
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

    // 给 Ktor 实际请求构造: body 立即读到内存
    internal constructor(ktorResponse: HttpResponse, request: KmpRequest) {
        codeVal = ktorResponse.status.value
        messageVal = ktorResponse.status.description
        headersVal = ktorResponse.headers.entries().associate { it.name to it.value }
        contentTypeStr = ktorResponse.headers[HttpHeaders.ContentType]
        bodyBytes = runCatching { runBlocking { ktorResponse.bodyAsBytes() } }.getOrNull()
        requestVal = request
    }

    // 给 StrResponse 等手动构造场景 (用 KmpResponseBuilder)
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
        get() = NativeKmpResponseBody(bodyBytes ?: ByteArray(0), contentTypeStr)
    actual val isSuccessful: Boolean get() = codeVal in 200..299
    actual val request: KmpRequest get() = requestVal
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
        // body 已读入内存, 无底层流需关闭; Ktor HttpResponse 由 HttpClient 管理
    }
}

// KP4 修复: header 从 expect class 成员改为扩展函数 (避免 typealias 默认参数限制)
// nativeMain actual 实现: 从 headersVal 中查找首个匹配 (不区分大小写, 与 OkHttp 行为一致)
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

    actual fun protocol(protocol: KmpProtocol): KmpResponseBuilder {
        // Ktor 不暴露 Protocol 概念, 忽略入参 (与 stub 行为一致)
        return this
    }

    actual fun request(request: KmpRequest): KmpResponseBuilder {
        requestVal = request
        return this
    }

    actual fun removeHeader(name: String): KmpResponseBuilder {
        headersVal.keys.removeAll { it.equals(name, ignoreCase = true) }
        return this
    }

    actual fun body(body: KmpResponseBody): KmpResponseBuilder {
        // KmpResponseBody 在 nativeMain 是 NativeKmpResponseBody, 取其 bytes 缓存
        val nativeBody = body as? NativeKmpResponseBody
        bodyBytes = nativeBody?.bytesValue
        contentTypeStr = nativeBody?.contentTypeValue
        return this
    }

    actual fun build(): KmpResponse {
        return KmpResponse(codeVal, messageVal, headersVal, bodyBytes, contentTypeStr, requestVal)
    }
}

/**
 * nativeMain 端 [KmpResponseBody] 实现: 包装已缓存的字节数组。
 *
 * Ktor [HttpResponse.bodyAsBytes] 是 suspend 且流只能读一次;
 * 这里在 [KmpResponse] 构造时一次性读到 [bytesValue], 之后 [bytes]/[string]/[byteStream]
 * 均从内存缓存读取, 可多次访问 (与 OkHttp ResponseBody 行为对齐)。
 */
actual abstract class KmpResponseBody : Closeable {
    actual fun bytes(): ByteArray = (this as NativeKmpResponseBody).bytesValue
    actual fun byteStream(): InputStream = (this as NativeKmpResponseBody).bytesValue.toInputStream()
    actual abstract fun contentType(): KmpMediaType?
    actual fun string(): String = bytes().decodeToString()
    actual override fun close() {
        // 内存字节数组, 无需关闭
    }

    actual companion object {
        actual val EMPTY: KmpResponseBody
            get() = NativeKmpResponseBody(ByteArray(0), null)
    }
}

/**
 * nativeMain 端 [KmpResponseBody] 真实实现类 (内部用)。
 *
 * 与 jvmAndAndroidMain 经 typealias 等价 okhttp3.ResponseBody 不同,
 * nativeMain 用真实 class 继承 [KmpResponseBody], 持有字节数组缓存。
 */
internal class NativeKmpResponseBody(
    internal val bytesValue: ByteArray,
    private val contentTypeStr: String?
) : KmpResponseBody() {
    override fun contentType(): KmpMediaType? = contentTypeStr?.let { NativeKmpMediaType(it) }
}
// endregion

// region Call / Callback —— 用协程包裹 Ktor suspend 调用
/**
 * nativeMain 端 [KmpCall] 实现: 用协程 [CoroutineScope] 包裹 Ktor 请求。
 *
 * - [enqueue] 启动协程异步执行, 完成后回调 [KmpCallback] (在 [Dispatchers.Default] 上)
 * - [execute] 用 [runBlocking] 阻塞当前线程同步执行 (与 OkHttp execute 行为对齐)
 * - [cancel] 取消协程 Job (与 OkHttp Call.cancel 行为对齐)
 *
 * 注: [execute] 在 Kotlin/Native 主线程调用可能 deadlock (与 JVM runBlocking 行为不同),
 * 调用方需在后台线程使用 (与 WebDav.native.kt 中 readRange 同样模式)。
 */
internal class NativeKmpCall(
    private val ktorClient: HttpClient,
    private val request: KmpRequest
) : KmpCall {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun enqueue(responseCallback: KmpCallback) {
        job = scope.launch {
            try {
                val response = executeKtor()
                responseCallback.onResponse(this@NativeKmpCall, response)
            } catch (e: Exception) {
                // 转 okio.IOException (与 OkHttp Callback.onFailure 签名对齐)
                val ioException = if (e is okio.IOException) e else okio.IOException(e.message, e)
                responseCallback.onFailure(this@NativeKmpCall, ioException)
            }
        }
    }

    override fun cancel() {
        job?.cancel()
        scope.cancel()
    }

    override fun execute(): KmpResponse = runBlocking {
        executeKtor()
    }

    /**
     * 实际执行 Ktor 请求 (suspend): 把 [KmpRequest] 转为 [HttpRequestBuilder] 并发起请求。
     *
     * 返回的 [KmpResponse] 在构造时已读取 body 到内存缓存 (见 [KmpResponse] 构造函数)。
     */
    private suspend fun executeKtor(): KmpResponse {
        val ktorRequest = request.toKtorHttpRequestBuilder()
        val httpResponse = ktorClient.request(ktorRequest)
        return KmpResponse(httpResponse, request)
    }
}

actual interface KmpCall {
    actual fun enqueue(responseCallback: KmpCallback)
    actual fun cancel()
    actual fun execute(): KmpResponse
}

actual interface KmpCallback {
    actual fun onFailure(call: KmpCall, e: okio.IOException)
    actual fun onResponse(call: KmpCall, response: KmpResponse)
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
        // OkHttp addEncoded 期望已 URL 编码的入参, 这里直接保存 (与 jvmAndAndroidMain 行为一致)
        entries.add(name to value)
        return this
    }

    /**
     * 构造 form-urlencoded body (内部用)。
     *
     * 与 OkHttp FormBody 一致: key=value 用 & 分隔, key/value 均 URL 编码 (form 编码)。
     */
    internal fun buildFormBody(): KmpRequestBody {
        val sb = StringBuilder()
        entries.forEachIndexed { idx, (k, v) ->
            if (idx > 0) sb.append('&')
            sb.append(urlEncodeForm(k)).append('=').append(urlEncodeForm(v))
        }
        return NativeKmpRequestBody(
            sb.toString().encodeToByteArray(),
            NativeKmpMediaType("application/x-www-form-urlencoded")
        )
    }
}

actual fun KmpFormBodyBuilder.buildKmpRequestBody(): KmpRequestBody = this.buildFormBody()

/**
 * nativeMain 端 [KmpHttpUrl] 实现: 包装 URL 字符串。
 *
 * 不做 OkHttp `toHttpUrl()` 级别的规范化 (默认端口移除/路径合并等),
 * 仅保存原字符串, 由 Ktor 内部 [io.ktor.http.Url] 解析。
 */
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
        // 入参已 URL 编码, 这里直接保存 (与 OkHttp addEncodedQueryParameter 行为一致)
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

/**
 * nativeMain 端 [KmpRequestBody] 真实实现 (内部用): 持有字节数据 + MediaType。
 *
 * 与 jvmAndAndroidMain 经 typealias 等价 okhttp3.RequestBody 不同,
 * nativeMain 用真实 class 继承 [KmpRequestBody], 在 [NativeKmpCall] 中转为 Ktor [ByteArrayContent]。
 */
internal class NativeKmpRequestBody(
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

actual fun String.toKmpMediaType(): KmpMediaType = NativeKmpMediaType(this)

actual fun String.toKmpRequestBody(contentType: KmpMediaType?): KmpRequestBody =
    NativeKmpRequestBody(this.encodeToByteArray(), contentType)

actual fun ByteArray.toKmpRequestBody(contentType: KmpMediaType?): KmpRequestBody =
    NativeKmpRequestBody(this, contentType)

actual fun <T : Any> KmpRequestBuilder.tagKmp(tagClass: KClass<T>, tag: T?): KmpRequestBuilder {
    // OkHttp tag 用于拦截器中按类型取请求关联对象; nativeMain 端不用 Interceptor, 这里 no-op
    return this
}

actual fun Any?.asKmpResponseBody(contentType: KmpMediaType?, length: Long): KmpResponseBody {
    // commonMain 中 DecompressInterceptor 用此扩展包装 BufferedSource 为 ResponseBody;
    // nativeMain 端 DecompressPlatform.native.kt 是 stub (decompressBody 返回 null), 此函数实际不执行
    // 兜底返回空 body (与 stub 行为对齐, 不抛异常避免 DecompressInterceptor 编译失败)
    val bytes = (this as? ByteArray) ?: ByteArray(0)
    return NativeKmpResponseBody(bytes, contentType?.toString())
}
// endregion

// region 内部辅助函数 / 类
/**
 * [KmpMediaType] 真实构造类 (内部用)。
 */
internal class NativeKmpMediaType(value: String) : KmpMediaType(value)

/**
 * 把 [KmpRequest] 转换为 Ktor [HttpRequestBuilder]。
 *
 * - URL/method 直接传入
 * - headers 逐个加 (与 OkHttp Request.Builder.addHeader 行为一致)
 * - body 转为 [ByteArrayContent]
 */
private fun KmpRequest.toKtorHttpRequestBuilder(): HttpRequestBuilder {
    return HttpRequestBuilder().apply {
        url(this@toKtorHttpRequestBuilder.urlStr)
        method = when (this@toKtorHttpRequestBuilder.method) {
            "GET" -> HttpMethod.Get
            "POST" -> HttpMethod.Post
            "PUT" -> HttpMethod.Put
            "DELETE" -> HttpMethod.Delete
            "HEAD" -> HttpMethod.Head
            "PATCH" -> HttpMethod.Patch
            "OPTIONS" -> HttpMethod.Options
            else -> HttpMethod(this@toKtorHttpRequestBuilder.method)
        }
        this@toKtorHttpRequestBuilder.headers.forEach { (name, value) ->
            header(name, value)
        }
        this@toKtorHttpRequestBuilder.body?.let { rb ->
            val nativeRb = rb as? NativeKmpRequestBody ?: return@let
            val ctStr = nativeRb.contentType?.toString()
            if (ctStr != null) {
                contentType(ContentType.parse(ctStr))
            }
            setBody(ByteArrayContent(nativeRb.bytes))
        }
    }
}

/**
 * form-urlencoded 编码 (与 OkHttp FormBody 编码行为对齐)。
 *
 * 规则: 字母数字不编码; 空格 '+'; 其他字符 %XX (UTF-8)。
 */
private fun urlEncodeForm(s: String): String {
    val sb = StringBuilder(s.length)
    for (b in s.encodeToByteArray()) {
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

/**
 * query 参数编码 (与 OkHttp HttpUrl.Builder.addQueryParameter 行为对齐)。
 *
 * 规则: 字母数字 + 部分 reserved 字符不编码; 空格 %20 (form 编码用 '+', query 编码用 %20)。
 */
private fun urlEncodeQuery(s: String): String {
    val sb = StringBuilder(s.length)
    for (b in s.encodeToByteArray()) {
        val u = b.toInt() and 0xFF
        when {
            u in 'a'.code..'z'.code || u in 'A'.code..'Z'.code || u in '0'.code..'9'.code -> sb.append(u.toChar())
            u == '-'.code || u == '_'.code || u == '.'.code || u == '~'.code -> sb.append(u.toChar())
            else -> sb.append('%').append(u.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return sb.toString()
}

/**
 * String.ifNotEmpty 扩展 (Kotlin stdlib 没有这个, 自己定义)。
 */
private inline fun String.ifNotEmpty(block: (String) -> Unit) {
    if (isNotEmpty()) block(this)
}
// endregion
