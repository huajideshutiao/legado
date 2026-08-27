@file:OptIn(ExperimentalEncodingApi::class)

package io.legado.app.help.http

import io.legado.app.constant.AppConst
import io.legado.app.help.UserAgentProviders
import io.legado.app.utils.Closeable
import io.legado.app.utils.InputStream
import io.legado.app.utils.toInputStream
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.http
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.InterruptedIOException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.BodyProgress
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.reflect.KClass
import kotlin.time.Duration
import okio.Buffer
import okio.GzipSource
import okio.Inflater
import okio.InflaterSource
import okio.buffer
import okio.use

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
 * - 超时: [KmpHttpClientBuilder.build] 默认 connect/read/call 均 15s (对齐 Android HttpHelper
 *   connect/read/write/call 15s; CIO 无 writeTimeout 等价物), 规则显式 timeout 优先;
 * - 拦截器: [KmpRequest.prepareForSend] 在请求发出前等价执行 Android app 拦截器逻辑
 *   (UA 注入 / Keep-Alive / Cache-Control / Accept-Encoding / CookieJar 标记移除 + CookieJarBridge),
 *   发送失败重试一次 (对齐 retryOnConnectionFailure); gzip/deflate 响应经 okio 透明解压
 *   (对齐 DecompressInterceptor; TLS connectionSpecs 无法在 CIO 等价配置, 走系统信任库)
 * - 代理: 支持 http/https 代理 (含基础认证, 经 Proxy-Authorization 请求头, Ktor CIO CONNECT 会透传);
 *   Ktor CIO 不支持 SOCKS 代理 (引擎忽略), 回退主 client 直连
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
    internal var proxyHost: String? = null
        private set
    internal var proxyPort: Int = 0
        private set
    internal var proxyUsername: String? = null
        private set
    internal var proxyPassword: String? = null
        private set

    /** 派生值: "Basic " + base64(user:pass); 请求发出时附加 Proxy-Authorization 头 */
    internal var proxyAuthHeader: String? = null
        private set

    // 给 expect class 匹配的 public 无参 constructor (commonMain 不直接调用)
    constructor()

    internal constructor(
        ktorClient: HttpClient,
        readTimeoutMillis: Long,
        callTimeoutMillis: Long,
        proxyHost: String? = null,
        proxyPort: Int = 0,
        proxyUsername: String? = null,
        proxyPassword: String? = null,
        proxyAuthHeader: String? = null
    ) {
        this.ktorClient = ktorClient
        this.readTimeoutMillis = readTimeoutMillis
        this.callTimeoutMillis = callTimeoutMillis
        this.proxyHost = proxyHost
        this.proxyPort = proxyPort
        this.proxyUsername = proxyUsername
        this.proxyPassword = proxyPassword
        this.proxyAuthHeader = proxyAuthHeader
    }

    actual fun newCall(request: KmpRequest): KmpCall {
        ktorClient
            ?: throw IllegalStateException("KmpHttpClient not initialized (use KmpHttpClientBuilder.build())")
        return NativeKmpCall(this, request)
    }

    actual fun newBuilder(): KmpHttpClientBuilder {
        return KmpHttpClientBuilder().also {
            it.readTimeoutMillis = readTimeoutMillis
            it.callTimeoutMillis = callTimeoutMillis
            it.proxyHost = proxyHost
            it.proxyPort = proxyPort
            it.proxyUsername = proxyUsername
            it.proxyPassword = proxyPassword
        }
    }
}

/**
 * nativeMain 端 [KmpHttpClientBuilder] 实现: 累积 timeout 配置, [build] 时构造 Ktor [HttpClient]。
 *
 * 超时统一走 Ktor [HttpTimeout] 插件 (跨引擎标准 API, CIO 引擎会读取配置):
 * - `requestTimeoutMillis` 对应 OkHttp callTimeout (整个请求周期上限);
 * - `connectTimeoutMillis` 对应 OkHttp connectTimeout;
 * - `socketTimeoutMillis` 对应 OkHttp readTimeout (两次数据包之间最大间隔);
 * - writeTimeout 在 CIO 无等价物 (尽力而为, 已说明)。
 *
 * 默认值与 Android HttpHelper 对齐 (connect/read/write/call 均 15s):
 * 0 = 未显式配置 → 默认 15s; AnalyzeUrlCore 规则显式 timeout 时经 setter 覆盖优先。
 */
actual class KmpHttpClientBuilder actual constructor() {
    internal var readTimeoutMillis: Long = 0L
    internal var callTimeoutMillis: Long = 0L
    internal var proxyHost: String? = null
    internal var proxyPort: Int = 0
    internal var proxyUsername: String? = null
    internal var proxyPassword: String? = null

    actual fun readTimeout(duration: Duration): KmpHttpClientBuilder {
        readTimeoutMillis = duration.inWholeMilliseconds
        return this
    }

    actual fun callTimeout(duration: Duration): KmpHttpClientBuilder {
        callTimeoutMillis = duration.inWholeMilliseconds
        return this
    }

    /**
     * 配置 HTTP 代理 (仅 http/https; Ktor CIO 引擎不支持 SOCKS, 见 NativeHttpProvider)。
     *
     * 认证: CIO 无 CONNECT 级认证 API, 由 [KmpHttpClient.proxyAuthHeader] 在请求上携带
     * Proxy-Authorization 头 (Ktor CIO startTunnel 会把该头透传到 CONNECT 隧道)。
     */
    internal fun proxy(
        host: String,
        port: Int,
        username: String?,
        password: String?
    ): KmpHttpClientBuilder {
        proxyHost = host
        proxyPort = port
        proxyUsername = username
        proxyPassword = password
        return this
    }

    actual fun build(): KmpHttpClient {
        // 与 Android HttpHelper 对齐: connect/read/write/call 默认均 15s; 0 = 未显式配置 → 默认 15s
        // (AnalyzeUrlCore 规则显式 readTimeout/callTimeout 时经上面 setter 覆盖, 显式值优先)
        val effectiveReadTimeout =
            if (readTimeoutMillis > 0) readTimeoutMillis else DEFAULT_TIMEOUT_MS
        val effectiveCallTimeout =
            if (callTimeoutMillis > 0) callTimeoutMillis else DEFAULT_TIMEOUT_MS
        val client = HttpClient(CIO) {
            // 下载进度: BodyProgress 插件在响应通道上逐字节上报 (NativeKmpCall 经 onDownload 注册,
            // 漫画页下载进度/转圈环心消费, 对照 desktop okhttp ProgressResponseBody 拦截器)
            install(BodyProgress)
            // 超时走 HttpTimeout 插件 (CIO 引擎经 HttpTimeoutCapability 读取
            // connectTimeoutMillis/socketTimeoutMillis 并应用到连接/读写):
            // - requestTimeoutMillis 对应 OkHttp callTimeout (整个请求周期上限: 发请求到收响应)
            // - connectTimeoutMillis 对应 OkHttp connectTimeout (Android 默认 15s)
            // - socketTimeoutMillis 对应 OkHttp readTimeout (两次数据包之间最大间隔);
            //   CIO 无 writeTimeout 等价物 (尽力而为)
            install(HttpTimeout) {
                requestTimeoutMillis = effectiveCallTimeout
                connectTimeoutMillis = DEFAULT_TIMEOUT_MS
                socketTimeoutMillis = effectiveReadTimeout
            }
            engine {
                proxyHost?.let { host ->
                    proxy = ProxyBuilder.http("http://$host:$proxyPort")
                }
            }
        }
        // 代理基础认证: CIO CONNECT 请求会透传请求头的 Proxy-Authorization (见 Ktor CIO startTunnel);
        // 注意该头也会随请求到达目标站 (CIO 无法只对 CONNECT 附加, 尽力而为, 与 OkHttp
        // ProxyAuthenticator 407 挑战式认证行为不同)
        val authHeader = if (!proxyUsername.isNullOrEmpty() && !proxyPassword.isNullOrEmpty()) {
            "Basic " + Base64.encode("$proxyUsername:$proxyPassword".encodeToByteArray())
        } else null
        return KmpHttpClient(
            client,
            effectiveReadTimeout,
            effectiveCallTimeout,
            proxyHost,
            proxyPort,
            proxyUsername,
            proxyPassword,
            authHeader
        )
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

    constructor()

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

actual class KmpRequestBuilder actual constructor() {
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
    internal var priorResponseVal: KmpResponse? = null
        private set

    constructor()

    // 给 Ktor 实际请求构造: body 立即读到内存 (gzip/deflate 透明解压, 对齐 Android DecompressInterceptor)
    // finalUrl 为 Ktor 自动跟随重定向后的最终请求 URL (与原始请求不同 = 发生过重定向)
    internal constructor(
        ktorResponse: HttpResponse,
        request: KmpRequest,
        finalUrl: String? = null,
    ) {
        codeVal = ktorResponse.status.value
        messageVal = ktorResponse.status.description
        headersVal = ktorResponse.headers.entries().associate { it.key to it.value }
        contentTypeStr = ktorResponse.headers[HttpHeaders.ContentType]
        val rawBytes = runCatching { runBlocking { ktorResponse.bodyAsBytes() } }.getOrNull()
        bodyBytes = rawBytes?.let {
            decompressResponseBody(
                it,
                ktorResponse.headers[HttpHeaders.ContentEncoding]
            )
        }
        // OkHttp 语义: response.request = 实际发送的请求 (重定向后的最终请求)。Ktor 自动跟随
        // 重定向时最终请求 URL 经 finalUrl 传入, 否则 StrResponse.url() 会拿到重定向前的地址。
        // priorResponse 合成一个 302 占位 (Ktor 不暴露重定向链中间跳, 只标记发生过重定向,
        // 供 WebBook.checkRedirect 的调试日志判定)
        val finalRequest = if (finalUrl != null && finalUrl != request.url.toString()) {
            KmpRequest(finalUrl, request.method, request.headers, request.body)
        } else {
            request
        }
        requestVal = finalRequest
        priorResponseVal = if (finalUrl != null && finalUrl != request.url.toString()) {
            KmpResponse(
                code = 302,
                message = "redirect",
                headers = emptyMap(),
                body = null,
                contentType = null,
                request = request,
            )
        } else {
            null
        }
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
    actual val priorResponse: KmpResponse? get() = priorResponseVal
    actual val isRedirect: Boolean get() = codeVal in 300..399

    actual fun headers(): KmpHeaders = KmpHeaders(headersVal)

    actual fun headers(name: String): List<String> =
        headersVal.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?: emptyList()

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

actual class KmpResponseBuilder actual constructor() {
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
    internal val contentTypeValue: String?
) : KmpResponseBody() {
    override fun contentType(): KmpMediaType? = contentTypeValue?.let { NativeKmpMediaType(it) }
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
    private val client: KmpHttpClient,
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
     * 实际执行 Ktor 请求 (suspend): 应用层拦截器等价逻辑 + Ktor 请求 + 失败重试。
     *
     * 流程 (对照 Android HttpHelper.createOkHttpClient 的 app 拦截器):
     * 1. [KmpRequest.prepareForSend]: UA 注入 / Keep-Alive / Connection / Cache-Control /
     *    Accept-Encoding / "CookieJar" 标记头移除 + CookieJarBridge.loadRequest 注入 Cookie 头;
     * 2. 代理认证头 Proxy-Authorization (仅代理客户端配置账号密码时);
     * 3. 发送失败重试一次 (对齐 OkHttp retryOnConnectionFailure=true; 超时/取消不重试);
     * 4. 响应回写 CookieJarBridge.saveResponse (启用 cookieJar 时, 对齐 Android 拦截器)。
     *
     * 返回的 [KmpResponse] 在构造时已读取 body 到内存缓存 (见 [KmpResponse] 构造函数)。
     */
    private suspend fun executeKtor(): KmpResponse {
        val ktorClient = client.ktorClient
            ?: throw IllegalStateException("KmpHttpClient not initialized (use KmpHttpClientBuilder.build())")
        val enableCookieJar = request.header(cookieJarHeader) != null
        val prepared = request.prepareForSend()
        val ktorRequest = prepared.toKtorHttpRequestBuilder()
        client.proxyAuthHeader?.let { ktorRequest.header(HttpHeaders.ProxyAuthorization, it) }
        // 字节级下载进度: Ktor onDownload (BodyProgress 插件) 按 url 广播到注册表
        // (漫画页 Image 的 DisposableEffect 监听转圈环心; 无监听者时仅遍历空表)
        ktorRequest.onDownload { bytesSentTotal, contentLength ->
            DownloadProgressRegistry.notify(prepared.url.toString(), bytesSentTotal, contentLength)
        }
        val httpResponse = ktorClientRequest(ktorClient, ktorRequest)
        // Ktor 自动跟随重定向: 响应关联的最终请求 URL (与 prepared.url 不同即发生过重定向)
        val finalUrl = httpResponse.call.request.url.toString()
        val response = KmpResponse(httpResponse, prepared, finalUrl)
        if (enableCookieJar) {
            CookieJarBridgeHolder.get()?.saveResponse(response)
        }
        return response
    }

    /** retryOnConnectionFailure(true) 等价: 非超时类传输错误重试一次 (OkHttp 默认重试) */
    private suspend fun ktorClientRequest(
        ktorClient: HttpClient,
        ktorRequest: HttpRequestBuilder
    ): HttpResponse {
        return try {
            ktorClient.request(ktorRequest)
        } catch (e: IOException) {
            // Ktor 各引擎异常 (ConnectTimeoutException / SocketTimeoutException /
            // HttpRequestTimeoutException 等) 均继承 kotlinx.io.IOException
            if (!e.isRetryableTransportError()) throw e
            // body 是内存字节 (ByteArrayContent), 可安全重发
            ktorClient.request(ktorRequest)
        }
    }

    private fun Throwable.isRetryableTransportError(): Boolean {
        if (this is CancellationException) return false
        // 超时类异常不重试 (与 OkHttp 对 InterruptedIOException 不重试一致)
        if (this is InterruptedIOException) return false
        if (this is ConnectTimeoutException) return false
        if (this is HttpRequestTimeoutException) return false
        return true
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
actual class KmpFormBodyBuilder actual constructor() {
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

    constructor()

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

actual open class KmpMediaType {
    internal var value: String = ""
        private set

    constructor()

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

    constructor()

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
 * 应用层拦截器等价逻辑 (对照 Android HttpHelper.createOkHttpClient 的 app 拦截器):
 * - UA 注入: 无 UA 头时填 UserAgentProviders.get(); UA 值为 "null" 时移除 (Android 同款);
 * - Keep-Alive / Connection / Cache-Control 固定头 (Android 拦截器逐请求添加);
 * - Accept-Encoding: 无显式 Accept-Encoding 且无 Range 时加 "gzip, deflate"
 *   (对齐 Android DecompressInterceptor 的 transparentDecompress 条件; 响应侧 [decompressResponseBody] 解压);
 * - "CookieJar" 标记头移除 (Android 拦截器在启用 cookieJar 时移除, 标记绝不上行给服务器);
 * - cookieJar 启用时经 [CookieJarBridgeHolder.loadRequest] 注入 Cookie 头 (Android 拦截器同款)。
 */
private fun KmpRequest.prepareForSend(): KmpRequest {
    val builder = newBuilder()
    val ua = header(AppConst.UA_NAME)
    if (ua == null) {
        builder.addHeader(AppConst.UA_NAME, UserAgentProviders.get())
    } else if (ua == "null") {
        builder.removeHeader(AppConst.UA_NAME)
    }
    builder.addHeader("Keep-Alive", "300")
    builder.addHeader("Connection", "Keep-Alive")
    builder.addHeader("Cache-Control", "no-cache")
    if (header("Accept-Encoding") == null && header("Range") == null) {
        builder.addHeader("Accept-Encoding", "gzip, deflate")
    }

    if (header(cookieJarHeader) != null) {
        builder.removeHeader(cookieJarHeader)
        return CookieJarBridgeHolder.get()?.loadRequest(builder.build()) ?: builder.build()
    }
    return builder.build()
}

/**
 * 透明 gzip/deflate 解压 (对齐 Android DecompressInterceptor: 仅 "gzip"/"deflate")。
 *
 * okio 3.x 的 GzipSource / InflaterSource 在 Kotlin/Native 可用 (纯 Kotlin 实现);
 * deflate 用 nowrap=true (raw deflate, 与 Android java.util.zip.Inflater(true) 一致)。
 * 解压失败回退原字节 (与 OkHttp 解压失败抛异常不同, 尽力而为)。
 */
private fun decompressResponseBody(bytes: ByteArray, contentEncoding: String?): ByteArray {
    return when (contentEncoding?.lowercase()) {
        "gzip", "x-gzip" -> runCatching {
            GzipSource(Buffer().write(bytes)).buffer().use { it.readByteArray() }
        }.getOrDefault(bytes)

        "deflate" -> runCatching {
            InflaterSource(Buffer().write(bytes), Inflater(true)).buffer()
                .use { it.readByteArray() }
        }.getOrDefault(bytes)

        else -> bytes
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

/** 默认超时 (对齐 Android HttpHelper: connect/read/write/call 均 15s) */
private const val DEFAULT_TIMEOUT_MS = 15_000L
// endregion
