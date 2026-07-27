package io.legado.app.help.http

import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.BufferedSource
import kotlin.reflect.KClass

/**
 * OkHttp 跨平台抽象层 jvmAndAndroid actual 实现。
 *
 * 详见 commonMain/kotlin/io/legado/app/help/http/KmpHttpTypes.kt expect 注释。
 *
 * ## 实现方式
 * 全部使用 `actual typealias Kmp* = okhttp3.*`, 类型完全等价 (零 diff):
 * - commonMain 中 Kmp* 类型经 typealias 后就是 okhttp3.* 本身;
 * - commonMain 中 expect class 声明的成员方法签名与 okhttp3.* 类型成员方法签名一致
 *   (typealias 后等价), 编译器接受;
 * - 扩展函数 (toKmpHttpUrl/toKmpMediaType/toKmpRequestBody/tagKmp/asKmpResponseBody)
 *   委托 OkHttp 5.x Kotlin 扩展 (toHttpUrl/toMediaType/toRequestBody/tag/asResponseBody)。
 *
 * jvm/android 端 (含 app 模块) 行为与原直接使用 okhttp3.* 完全一致。
 */

// region Interceptor / Chain
actual typealias KmpInterceptor = Interceptor

actual typealias KmpInterceptorChain = Interceptor.Chain
// endregion

// region HttpClient / Builder
actual typealias KmpHttpClient = OkHttpClient

actual typealias KmpHttpClientBuilder = OkHttpClient.Builder
// endregion

// region Request / Builder
actual typealias KmpRequest = Request

actual typealias KmpRequestBuilder = Request.Builder
// endregion

// region Response / Builder / Body
actual typealias KmpResponse = Response

// KP4 修复: header 从 expect class 成员改为扩展函数
// (OkHttp Response.header 有默认参数, typealias actual 不允许 expect 成员有默认值)
// actual 扩展函数不写默认值 (默认值在 expect 中声明);
// this.header(name, defaultValue) 调用 OkHttp Response.header 成员方法 (Kotlin 成员方法优先, 不递归)
actual fun KmpResponse.header(name: String, defaultValue: String?): String? = this.header(name, defaultValue)

actual typealias KmpResponseBuilder = Response.Builder

actual typealias KmpResponseBody = ResponseBody
// endregion

// region Call / Callback
actual typealias KmpCall = Call

actual typealias KmpCallback = Callback
// endregion

// region FormBody / HttpUrl / MediaType / RequestBody / Headers / Protocol
/**
 * KP4 修复: KmpFormBodyBuilder 改为 wrapper class (不再用 typealias)。
 *
 * 原因: OkHttp 5.3.2 `FormBody.Builder` 构造函数签名是 `constructor(charset: Charset? = null)`,
 * 带默认参数; typealias actual 不允许 expect 声明默认参数值,
 * 且 expect class 无参 constructor() 与 actual 单参数 (即使有默认值) 构造函数参数个数不匹配。
 *
 * 改为 wrapper class 后, commonMain 调用 `KmpFormBodyBuilder()` 零 diff,
 * 内部委托 `FormBody.Builder()` (charset=null, 与 OkHttp 默认行为一致)。
 *
 * 注意: 不再用 `actual typealias KmpFormBodyBuilder = FormBody.Builder`,
 * KmpFormBodyBuilder 不再是 FormBody.Builder 的别名, 而是独立类型;
 * 调用方需通过 [buildKmpRequestBody] 扩展函数获取 KmpRequestBody。
 */
actual class KmpFormBodyBuilder {
    private val delegate: FormBody.Builder = FormBody.Builder()

    actual fun add(name: String, value: String): KmpFormBodyBuilder {
        delegate.add(name, value)
        return this
    }

    actual fun addEncoded(name: String, value: String): KmpFormBodyBuilder {
        delegate.addEncoded(name, value)
        return this
    }

    /**
     * 构造 FormBody (KmpRequestBody 子类), 供 [buildKmpRequestBody] 扩展函数调用。
     * 不声明为 actual (commonMain expect class 中已移除 build 成员声明)。
     */
    internal fun buildFormBody(): FormBody = delegate.build()
}

actual typealias KmpHttpUrl = HttpUrl

actual typealias KmpHttpUrlBuilder = HttpUrl.Builder

actual typealias KmpMediaType = MediaType

actual typealias KmpRequestBody = RequestBody

actual typealias KmpHeaders = Headers

actual typealias KmpProtocol = Protocol
// endregion

// region KmpFormBodyBuilder.buildKmpRequestBody 扩展函数 actual
// KP4 修复: 原 expect class KmpFormBodyBuilder.build(): KmpRequestBody 与 OkHttp 5.3.2
// FormBody.Builder.build(): FormBody 协变返回类型不兼容, 改用扩展函数。
// KmpFormBodyBuilder 现已改为 wrapper class, 此处委托 wrapper.buildFormBody() (返回 FormBody, 多态赋给 RequestBody),
// 行为与原 commonMain 中 `formBody.build()` 直接调用完全一致 (零 diff)。
actual fun KmpFormBodyBuilder.buildKmpRequestBody(): KmpRequestBody = this.buildFormBody()
// endregion

// region 扩展函数 actual 实现 —— 委托 OkHttp 5.x Kotlin 扩展
actual fun String.toKmpHttpUrl(): KmpHttpUrl = this.toHttpUrl()

actual fun String.toKmpMediaType(): KmpMediaType = this.toMediaType()

actual fun String.toKmpRequestBody(contentType: KmpMediaType?): KmpRequestBody =
    this.toRequestBody(contentType)

actual fun ByteArray.toKmpRequestBody(contentType: KmpMediaType?): KmpRequestBody =
    this.toRequestBody(contentType)

actual fun <T : Any> KmpRequestBuilder.tagKmp(tagClass: KClass<T>, tag: T?): KmpRequestBuilder =
    // OkHttp 5.x 提供 Kotlin 扩展 `tag(tagClass: KClass<T>, tag: T?)`,
    // 与本 expect fun 签名一致, 直接调用。
    this.tag(tagClass, tag)

actual fun Any?.asKmpResponseBody(contentType: KmpMediaType?, length: Long): KmpResponseBody {
    // actual 端 this 是 okio.BufferedSource (DecompressInterceptor 中 decompressBody 返回),
    // 委托 okhttp3.ResponseBody.Companion.asResponseBody 扩展。
    val source = this as BufferedSource
    return source.asResponseBody(contentType, length)
}
// endregion
