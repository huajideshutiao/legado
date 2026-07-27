package io.legado.app.help.http

import kotlin.concurrent.Volatile

/**
 * 跨平台 HTTP 客户端抽象层（commonMain）。
 *
 * ## 背景
 * 现有 shared commonMain 代码（OkHttpUtils / AnalyzeUrlCore / StrResponse 等）
 * 直接引用 `okhttp3.*` 类型，JVM/Android 平台可编译，但 iOS / HarmonyOS 无法使用 OkHttp。
 * 本文件提供平台无关的 HTTP 调用接口，让 commonMain 新代码可以跨平台，
 * 不影响现有 OkHttp 类型代码（保持向后兼容，未来逐步迁移）。
 *
 * ## 与现有抽象的关系
 * - [OkHttpClientProvider]：暴露 `okhttp3.OkHttpClient` 实例，仅供 JVM/Android 代码使用。
 * - [HttpClient]：平台无关接口，供 commonMain 新代码使用。
 *
 * ## 文件组织
 * 本文件集中声明跨平台 HTTP 模型，避免散落多文件：
 * - [HttpMethod] / [HttpRequestBody] / [HttpRequest]
 * - [HttpResponseBody] / [HttpResponse]
 * - [HttpClient] / [HttpClients]
 *
 * JVM/Android actual 实现见 `OkHttpHttpClient.kt`；
 * iOS/HarmonyOS 未来用 Ktor 实现同名接口。
 */

/**
 * 跨平台 HTTP 方法枚举。
 *
 * 与现有 [RequestMethod]（仅 GET/POST）区别：
 * - 本接口面向 [HttpClient] 抽象层，覆盖更广的方法集；
 * - [RequestMethod] 保留供现有 AnalyzeUrl 代码使用，不修改。
 */
enum class HttpMethod {
    GET, POST, PUT, DELETE, HEAD, OPTIONS, PATCH
}

/**
 * 跨平台 HTTP 请求体抽象。
 *
 * sealed class 限定三种常见形式：
 * - [Text]：纯文本 / JSON / XML 等；
 * - [Bytes]：字节流（文件上传、二进制 payload）；
 * - [Form]：表单字段（application/x-www-form-urlencoded）。
 *
 * 多部分表单（multipart/form-data）暂未抽象，现有代码用
 * `Request.Builder.postMultipart`（OkHttp 扩展），按需再扩展。
 */
sealed class HttpRequestBody {
    /** Content-Type（可含 charset），如 `application/json; charset=utf-8`。null 表示由 actual 决定默认值。 */
    abstract val contentType: String?

    /** 纯文本请求体。 */
    data class Text(
        val text: String,
        override val contentType: String? = null
    ) : HttpRequestBody()

    /** 字节请求体（用于上传二进制）。 */
    data class Bytes(
        val bytes: ByteArray,
        override val contentType: String? = null
    ) : HttpRequestBody() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return bytes.contentEquals(other.bytes) && contentType == other.contentType
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + (contentType?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * 表单字段请求体。
     *
     * @param encoded `true` 表示字段值已经过 URL encoded，actual 实现需跳过二次编码。
     */
    data class Form(
        val fields: Map<String, List<String>>,
        val encoded: Boolean = false,
        override val contentType: String? = "application/x-www-form-urlencoded"
    ) : HttpRequestBody()
}

/**
 * 跨平台 HTTP 请求模型。
 *
 * 仅承载调用 [HttpClient.execute] 所需的最小信息：
 * URL / 方法 / 头部 / 请求体 / 查询参数 / encodedQuery。
 *
 * 不包含：重试、超时、代理、SSL、Cookie——这些由具体 actual 实现
 * （OkHttp/Ktor）通过配置底层 client 实例控制。
 *
 * @param url 完整 URL（不含 query 部分）；query 由 [queryParameters] / [encodedQuery] 提供。
 * @param queryParameters 未编码的 query 参数；同一 key 多次添加会生成多组键值对。
 * @param encodedQuery 已编码的完整 query 字符串（如 `a=1&b=2`），优先级高于 [queryParameters]。
 */
data class HttpRequest(
    val url: String,
    val method: HttpMethod = HttpMethod.GET,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: HttpRequestBody? = null,
    val queryParameters: Map<String, List<String>> = emptyMap(),
    val encodedQuery: String? = null
)

/**
 * 跨平台 HTTP 响应体抽象。
 *
 * 实现：jvmAndAndroidMain 用 `okhttp3.ResponseBody` 包装；
 * iOS/HarmonyOS 用 Ktor 响应包装。
 *
 * 注意：commonMain 不使用 `java.io.InputStream`，统一以 [bytes] / [string] 暴露内容。
 */
interface HttpResponseBody {
    /** 默认字符集解码后的字符串。 */
    fun string(): String

    /** 完整字节内容。 */
    fun bytes(): ByteArray

    /** Content-Type（含 charset），无则 null。 */
    val contentType: String?

    /** Content-Length；未知为 -1。 */
    val contentLength: Long

    /** 关闭底层资源；多次调用幂等。 */
    fun close()
}

/**
 * 跨平台 HTTP 响应模型。
 *
 * 与现有 [StrResponse] 区别：
 * - [StrResponse] 持有 `okhttp3.Response` raw 对象，只能在 JVM/Android 使用；
 * - [HttpResponse] 仅持有跨平台类型，可由 commonMain 直接消费。
 *
 * 设计为 interface 而非 data class，便于 actual 实现复用底层对象
 * （如 `okhttp3.Response`、Ktor `HttpResponse`）而不是立即拷贝。
 */
interface HttpResponse {
    val code: Int
    val message: String
    val headers: Map<String, List<String>>
    val body: HttpResponseBody

    /** 实际请求 URL（重定向后）。 */
    val requestUrl: String

    /** 2xx 视为成功。 */
    val isSuccessful: Boolean
        get() = code in 200..299
}

/**
 * 跨平台 HTTP 客户端抽象接口。
 *
 * JVM/Android 端通过 `OkHttpHttpClient`（jvmAndAndroidMain）委托给 `OkHttpClient`；
 * iOS/HarmonyOS 端未来通过 KtorHttpClient 实现。
 *
 * 现有 `OkHttpUtils.kt` / `AnalyzeUrlCore.kt` 暂不迁移，本接口仅供新代码使用。
 */
interface HttpClient {
    /** 执行请求并返回响应。调用方负责 [HttpResponse.body] 的 [HttpResponseBody.close]。 */
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * HttpClient 全局容器（provider 注入模式）。
 *
 * 设计参考 [OkHttpClientProviders]：app 端启动时注册 actual 实现，
 * commonMain 代码通过 [get] / [execute] 调用，无需直接依赖 OkHttp。
 *
 * 例如 app 端：
 * ```
 * HttpClients.register(OkHttpHttpClient(OkHttpClientProviders.get().okHttpClient))
 * ```
 *
 * 未注册时调用 [get] 抛 [IllegalStateException]，帮助早期发现初始化遗漏。
 */
object HttpClients {
    @Volatile
    private var impl: HttpClient? = null

    /** 宿主启动早期注册一次（任何 commonMain 调用之前）。 */
    fun register(impl: HttpClient) {
        this.impl = impl
    }

    /** 取已注册实现；未注册抛出 IllegalStateException 帮助早期发现初始化遗漏。 */
    fun get(): HttpClient =
        impl ?: error("HttpClients.impl not registered; call HttpClients.register(...) in App.onCreate first")

    /** 便捷入口：等价于 `get().execute(request)`。 */
    suspend fun execute(request: HttpRequest): HttpResponse = get().execute(request)
}
