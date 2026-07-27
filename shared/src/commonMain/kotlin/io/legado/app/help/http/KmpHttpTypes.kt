package io.legado.app.help.http

import io.legado.app.utils.Closeable
import io.legado.app.utils.InputStream
import kotlin.time.Duration

/**
 * OkHttp 跨平台抽象层 (expect/actual)。
 *
 * ## 背景
 * OkHttp 5.3.2 不是全 KMP 库 (仅 common + android + jvm 变体发布),
 * iOS/鸿蒙 target 没有对应变体, commonMain 直接 `import okhttp3.*` 会让这些 target 编译失败。
 *
 * 本文件把 commonMain 中用到的 okhttp3.* 类型抽象为 Kmp* 命名:
 * - jvmAndAndroidMain: 用 `actual typealias Kmp* = okhttp3.*` (行为零 diff, 类型完全等价);
 * - iOS/鸿蒙: stub 实现 (运行时抛 UnsupportedOperationException, 与 [OkHttpUtilsExt] iOS/鸿蒙
 *   actual 处理方式一致; AnalyzeUrlCore 在 iOS/鸿蒙端的实际运行需后续重构为 [HttpClient] 抽象,
 *   该抽象已有 Ktor 实现, 不在本次 KP4 修复范围内)。
 *
 * ## 命名约定
 * - 接口类: `KmpInterceptor` / `KmpInterceptorChain` / `KmpCallback` (OkHttp 对应 interface);
 * - 普通类: `KmpHttpClient` / `KmpRequest` / `KmpResponse` 等 (OkHttp 对应 final/open class);
 * - 枚举: `KmpProtocol` (OkHttp 对应 enum class);
 * - 扩展函数: `String.toKmpHttpUrl()` / `String.toKmpMediaType()` / `String.toKmpRequestBody()`
 *   / `ByteArray.toKmpRequestBody()` / `KmpRequestBuilder.tagKmp()` (OkHttp 5.x Kotlin 扩展);
 * - 辅助函数: `Any?.asKmpResponseBody()` (包装 `BufferedSource.asResponseBody`, 避免在 commonMain 引用 okio.BufferedSource)。
 *
 * ## 使用约束
 * commonMain 中所有原 `okhttp3.*` 引用应替换为 `Kmp*` 命名, 由本文件统一提供。
 * jvm/android 端代码 (含 app 模块) 可继续直接使用 `okhttp3.*` 类型,
 * 因为 Kmp* 在 jvmAndAndroidMain 经 typealias 等价于 okhttp3.*。
 */

// region Interceptor / Chain —— okhttp3.Interceptor / okhttp3.Interceptor.Chain
/**
 * 跨平台 Interceptor 接口 (对应 `okhttp3.Interceptor`)。
 *
 * jvmAndAndroidMain: `actual typealias KmpInterceptor = okhttp3.Interceptor`;
 * iOS/鸿蒙: 普通 interface (供 commonMain 中 object 实现, 运行时 stub)。
 */
expect interface KmpInterceptor {
    fun intercept(chain: KmpInterceptorChain): KmpResponse
}

/**
 * 跨平台 Interceptor.Chain 接口 (对应 `okhttp3.Interceptor.Chain`)。
 */
expect interface KmpInterceptorChain {
    fun request(): KmpRequest
    fun proceed(request: KmpRequest): KmpResponse
}
// endregion

// region HttpClient / Builder —— okhttp3.OkHttpClient / okhttp3.OkHttpClient.Builder
/**
 * 跨平台 HTTP 客户端 (对应 `okhttp3.OkHttpClient`)。
 *
 * 仅声明 commonMain 中实际调用的成员 (newCall/newBuilder)。
 * jvmAndAndroidMain: `actual typealias KmpHttpClient = okhttp3.OkHttpClient` (其他成员仍可用)。
 */
expect class KmpHttpClient {
    fun newCall(request: KmpRequest): KmpCall
    fun newBuilder(): KmpHttpClientBuilder
}

/**
 * 跨平台 OkHttpClient.Builder (对应 `okhttp3.OkHttpClient.Builder`)。
 *
 * 仅声明 commonMain 中实际调用的成员 (readTimeout/callTimeout/build, 使用 OkHttp 5.x KotlinDuration 重载)。
 */
expect class KmpHttpClientBuilder {
    fun readTimeout(duration: Duration): KmpHttpClientBuilder
    fun callTimeout(duration: Duration): KmpHttpClientBuilder
    fun build(): KmpHttpClient
}
// endregion

// region Request / Builder —— okhttp3.Request / okhttp3.Request.Builder
/**
 * 跨平台 HTTP 请求 (对应 `okhttp3.Request`)。
 *
 * 仅声明 commonMain 中实际调用的成员 (newBuilder/header/url)。
 */
expect class KmpRequest {
    fun newBuilder(): KmpRequestBuilder
    fun header(name: String): String?
    val url: KmpHttpUrl
}

/**
 * 跨平台 Request.Builder (对应 `okhttp3.Request.Builder`)。
 *
 * KP4 修复:
 * - 必须显式声明 `constructor()` (OkHttp 5.x Request.Builder 有无参构造 `Request.Builder()`),
 *   否则 commonMain 中 `KmpRequestBuilder()` 调用无法解析 (expect class 默认无默认构造函数)。
 * - `url` 拆为两个重载 (String + KmpHttpUrl), 与 OkHttp 5.x Request.Builder.url 一致
 *   (OkHttp 5.x 有 `url(String)` 和 `url(HttpUrl)` 两个重载)。
 * - tag(KClass, T) 是 OkHttp 5.x Kotlin 扩展函数 (非成员方法), 通过 [tagKmp] expect fun 包装。
 */
expect class KmpRequestBuilder() {
    fun url(url: String): KmpRequestBuilder
    fun url(url: KmpHttpUrl): KmpRequestBuilder
    fun addHeader(name: String, value: String): KmpRequestBuilder
    fun header(name: String, value: String): KmpRequestBuilder
    fun removeHeader(name: String): KmpRequestBuilder
    fun get(): KmpRequestBuilder
    fun post(body: KmpRequestBody): KmpRequestBuilder
    fun method(method: String, body: KmpRequestBody?): KmpRequestBuilder
    fun build(): KmpRequest
}
// endregion

// region Response / Builder / Body —— okhttp3.Response / okhttp3.Response.Builder / okhttp3.ResponseBody
/**
 * 跨平台 HTTP 响应 (对应 `okhttp3.Response`)。
 *
 * 实现 [Closeable] (jvmAndAndroidMain 经 typealias 等价于 `okhttp3.Response`,
 * 后者已实现 java.io.Closeable; commonMain 的 Closeable 经 expect/actual 等价)。
 *
 * 仅声明 commonMain 中实际调用的成员。
 *
 * KP4 修复: 与 OkHttp 5.3.2 Response 签名对齐:
 * - `header` 不在 expect class 中声明 (OkHttp 5.3.2 Response.header 有 `defaultValue: String? = null` 默认参数,
 *   typealias actual 不允许 expect 有默认参数值, 形成死结), 改为扩展函数 [header] (扩展函数 actual
 *   不是 typealias, 不受此限制, 可在 expect 中声明默认参数值);
 * - 新增 `priorResponse` / `isRedirect` 成员 (WebBook.checkRedirect 在 commonMain 调用)。
 */
expect class KmpResponse : Closeable {
    val code: Int
    val message: String
    val body: KmpResponseBody
    val isSuccessful: Boolean
    val request: KmpRequest
    val networkResponse: KmpResponse?
    val priorResponse: KmpResponse?
    val isRedirect: Boolean
    fun headers(): KmpHeaders
    fun newBuilder(): KmpResponseBuilder
    override fun close()
}

/**
 * 获取响应头 (对应 `okhttp3.Response.header`)。
 *
 * KP4 修复: 从 expect class 成员改为扩展函数。
 * 原因: OkHttp 5.3.2 Response.header 有 `defaultValue: String? = null` 默认参数,
 * typealias actual 不允许 expect 成员声明默认参数值 (KMP 限制),
 * 改为扩展函数后 actual 不是 typealias, 可在 expect 中声明默认参数值。
 *
 * jvmAndAndroidMain actual: 委托 `this.header(name, defaultValue)` (Kotlin 成员方法优先,
 * 调用 OkHttp Response.header 成员方法, 不会递归扩展函数)。
 */
expect fun KmpResponse.header(name: String, defaultValue: String? = null): String?

/**
 * 跨平台 Response.Builder (对应 `okhttp3.Response.Builder`)。
 *
 * KP4 修复: 必须显式声明 `constructor()` (OkHttp 5.x Response.Builder 有无参构造 `Response.Builder()`)。
 * 仅声明 commonMain 中实际调用的成员 (code/message/protocol/request/removeHeader/body/build)。
 * 注: priorResponse 等仅 app 端使用的成员不在此声明, app 端可直接通过 okhttp3.Response.Builder 调用。
 */
expect class KmpResponseBuilder() {
    fun code(code: Int): KmpResponseBuilder
    fun message(message: String): KmpResponseBuilder
    fun protocol(protocol: KmpProtocol): KmpResponseBuilder
    fun request(request: KmpRequest): KmpResponseBuilder
    fun removeHeader(name: String): KmpResponseBuilder
    fun body(body: KmpResponseBody): KmpResponseBuilder
    fun build(): KmpResponse
}

/**
 * 跨平台 ResponseBody (对应 `okhttp3.ResponseBody`, abstract class)。
 *
 * 实现 [Closeable] (与 KmpResponse 同理)。
 * 注: byteStream() 返回 commonMain 的 [InputStream] (expect abstract class, jvmAndAndroidMain 等价 java.io.InputStream)。
 *
 * KP4 修复: 改为 `expect abstract class` (OkHttp 5.3.2 ResponseBody 是 abstract class,
 * expect class 默认 final 会与 actual typealias 冲突, modality 不同)。
 * 注: `contentType()` 在 OkHttp ResponseBody 中是 abstract 方法, expect 也必须声明为 abstract
 * (否则 expect final 方法与 actual abstract 方法 modality 不匹配)。
 *
 * companion object 的 EMPTY 常量对应 `okhttp3.ResponseBody.EMPTY`。
 */
expect abstract class KmpResponseBody : Closeable {
    fun bytes(): ByteArray
    fun byteStream(): InputStream
    abstract fun contentType(): KmpMediaType?
    fun string(): String
    override fun close()

    companion object {
        val EMPTY: KmpResponseBody
    }
}
// endregion

// region Call / Callback —— okhttp3.Call / okhttp3.Callback
/**
 * 跨平台 Call (对应 `okhttp3.Call`, interface)。
 *
 * KP4 修复: `enqueue` 参数名必须与 OkHttp 5.3.2 Call.enqueue 一致 (`responseCallback`),
 * typealias actual 严格要求参数名匹配。
 */
expect interface KmpCall {
    fun enqueue(responseCallback: KmpCallback)
    fun cancel()
    fun execute(): KmpResponse
}

/**
 * 跨平台 Callback (对应 `okhttp3.Callback`)。
 *
 * onFailure 的异常类型为 `okio.IOException` (okio 是 KMP 库, commonMain 可见,
 * 经 Ktor 间接传递到 iOS/鸿蒙 target)。
 */
expect interface KmpCallback {
    fun onFailure(call: KmpCall, e: okio.IOException)
    fun onResponse(call: KmpCall, response: KmpResponse)
}
// endregion

// region FormBody / HttpUrl / MediaType / RequestBody / Headers / Protocol
/**
 * 跨平台 FormBody.Builder (对应 `okhttp3.FormBody.Builder`)。
 *
 * KP4 修复: `build()` 不在 expect class 中声明, 改为扩展函数 [buildKmpRequestBody]。
 * 原因: OkHttp 5.3.2 `FormBody.Builder.build()` 返回 `FormBody` (RequestBody 子类),
 * Kotlin expect/actual 不支持协变返回类型, 无法 typealias 后与 `build(): KmpRequestBody` 兼容。
 * 改用扩展函数 (名为 `buildKmpRequestBody` 避免与成员函数 `build()` 重名遮蔽),
 * jvmAndAndroidMain actual 内部委托 `FormBody.Builder.build()`, 行为与原直接调用一致 (零 diff)。
 */
expect class KmpFormBodyBuilder() {
    fun add(name: String, value: String): KmpFormBodyBuilder
    fun addEncoded(name: String, value: String): KmpFormBodyBuilder
}

/**
 * [KmpFormBodyBuilder] 构造 [KmpRequestBody] 的扩展函数 (替代原 `build()` 成员)。
 *
 * jvmAndAndroidMain: 委托 `FormBody.Builder.build()` (返回 FormBody, 多态赋给 RequestBody);
 * iOS/鸿蒙: 抛 UnsupportedOperationException (与 KmpFormBodyBuilder 其他成员一致)。
 */
expect fun KmpFormBodyBuilder.buildKmpRequestBody(): KmpRequestBody

/**
 * 跨平台 HttpUrl (对应 `okhttp3.HttpUrl`)。
 *
 * 仅声明 commonMain 中实际调用的成员 (newBuilder)。
 */
expect class KmpHttpUrl {
    fun newBuilder(): KmpHttpUrlBuilder
}

/**
 * 跨平台 HttpUrl.Builder (对应 `okhttp3.HttpUrl.Builder`)。
 *
 * KP4 修复:
 * - `addQueryParameter` / `addEncodedQueryParameter` 的 value 参数改为 `String?` (nullable),
 *   与 OkHttp 5.3.2 实际签名一致 (OkHttp 允许 query 参数值为 null);
 * - `encodedQuery` / `addEncodedQueryParameter` 参数名必须与 OkHttp 5.3.2 实际参数名一致
 *   (`encodedQuery` / `encodedName` / `encodedValue`), typealias actual 严格要求参数名匹配。
 */
expect class KmpHttpUrlBuilder {
    fun encodedQuery(encodedQuery: String?): KmpHttpUrlBuilder
    fun addQueryParameter(name: String, value: String?): KmpHttpUrlBuilder
    fun addEncodedQueryParameter(encodedName: String, encodedValue: String?): KmpHttpUrlBuilder
    fun build(): KmpHttpUrl
}

/**
 * 跨平台 MediaType (对应 `okhttp3.MediaType`)。
 *
 * 不暴露成员方法 (commonMain 仅作为类型签名传递), 通过 [toKmpMediaType] 扩展函数构造。
 */
expect class KmpMediaType

/**
 * 跨平台 RequestBody (对应 `okhttp3.RequestBody`, abstract class)。
 *
 * 不暴露成员方法 (commonMain 仅作为类型签名传递), 通过 [toKmpRequestBody] / [buildKmpRequestBody] 构造。
 *
 * KP4 修复: 改为 `expect abstract class` (okhttp3.RequestBody 是 abstract class,
 * expect class 默认 final 会与 actual typealias 冲突; 参考 [InputStream] 处理方式)。
 */
expect abstract class KmpRequestBody

/**
 * 跨平台 Headers (对应 `okhttp3.Headers`)。
 *
 * 仅声明 commonMain 中实际调用的成员 (toMultimap)。
 */
expect class KmpHeaders {
    fun toMultimap(): Map<String, List<String>>
}

/**
 * 跨平台 Protocol 枚举 (对应 `okhttp3.Protocol`)。
 *
 * 枚举值与 `okhttp3.Protocol` 完全一致 (顺序/名称), jvmAndAndroidMain 经 typealias 等价。
 */
expect enum class KmpProtocol {
    HTTP_1_0, HTTP_1_1, SPDY_3, HTTP_2, H2_PRIOR_KNOWLEDGE, QUIC
}
// endregion

// region 扩展函数 —— OkHttp 5.x Kotlin 扩展包装
/**
 * String → KmpHttpUrl 转换 (对应 `okhttp3.HttpUrl.Companion.toHttpUrl` String 扩展)。
 */
expect fun String.toKmpHttpUrl(): KmpHttpUrl

/**
 * String → KmpMediaType 转换 (对应 `okhttp3.MediaType.Companion.toMediaType` String 扩展)。
 */
expect fun String.toKmpMediaType(): KmpMediaType

/**
 * String → KmpRequestBody 转换 (对应 `okhttp3.RequestBody.Companion.toRequestBody` String 扩展)。
 */
expect fun String.toKmpRequestBody(contentType: KmpMediaType?): KmpRequestBody

/**
 * ByteArray → KmpRequestBody 转换 (对应 `okhttp3.RequestBody.Companion.toRequestBody` ByteArray 扩展)。
 */
expect fun ByteArray.toKmpRequestBody(contentType: KmpMediaType?): KmpRequestBody

/**
 * Request.Builder.tag(KClass, T) 扩展 (对应 OkHttp 5.x Kotlin 扩展 `tag(tagClass: KClass<T>, tag: T)`)。
 *
 * OkHttp 5.x 的 tag(KClass, T) 是 inline reified 扩展函数 (非成员方法),
 * 无法通过 expect class 成员声明, 故包装为 expect fun。
 */
expect fun <T : Any> KmpRequestBuilder.tagKmp(tagClass: kotlin.reflect.KClass<T>, tag: T?): KmpRequestBuilder

/**
 * BufferedSource → KmpResponseBody 转换 (对应 `okhttp3.ResponseBody.Companion.asResponseBody` 扩展)。
 *
 * 入参 `source` 在 jvmAndAndroidMain 是 `okio.BufferedSource`, commonMain 不引用 okio.BufferedSource
 * (避免 iOS/鸿蒙 target 对 okio 类型的可见性依赖), 用 [Any]? 接收, actual 内部 cast。
 */
expect fun Any?.asKmpResponseBody(contentType: KmpMediaType?, length: Long): KmpResponseBody
// endregion
