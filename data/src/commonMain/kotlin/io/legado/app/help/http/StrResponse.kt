package io.legado.app.help.http

/**
 * An HTTP response.
 *
 * KP4 OkHttp 跨平台修复: 原直接 `import okhttp3.*`,
 * iOS/鸿蒙 target 无 OkHttp 变体编译失败; 现改用 [KmpResponse]/[KmpResponseBuilder]/
 * [KmpResponseBody]/[KmpRequest]/[KmpRequestBuilder]/[KmpProtocol]/[KmpHeaders]
 * 跨平台抽象 (jvmAndAndroidMain 经 typealias 等价 okhttp3.*; iOS/鸿蒙 由 nativeMain 用 Ktor 包装实现)。
 * jvm/android 行为与原实现完全一致 (零 diff)。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class StrResponse {
    var raw: KmpResponse
        private set
    var body: String? = null
        private set
    var errorBody: KmpResponseBody? = null
        private set

    constructor(rawResponse: KmpResponse, body: String?) {
        this.raw = rawResponse
        this.body = body
    }

    constructor(url: String, body: String?) {
        val request = try {
            KmpRequestBuilder().url(url).build()
        } catch (e: Exception) {
            KmpRequestBuilder().url("http://localhost/").build()
        }
        raw = KmpResponseBuilder()
            .code(200)
            .message("OK")
            .protocol(KmpProtocol.HTTP_1_1)
            .request(request)
            .build()
        this.body = body
    }

    constructor(rawResponse: KmpResponse, errorBody: KmpResponseBody?) {
        this.raw = rawResponse
        this.errorBody = errorBody
    }

    fun raw() = raw

    fun url(): String {
        raw.networkResponse?.let {
            return it.request.url.toString()
        }
        return raw.request.url.toString()
    }

    val url: String get() = url()

    fun body() = body

    fun code(): Int {
        return raw.code
    }

    fun message(): String {
        return raw.message
    }

    fun headers(): KmpHeaders {
        return raw.headers()
    }

    fun isSuccessful(): Boolean = raw.isSuccessful

    fun errorBody(): KmpResponseBody? {
        return errorBody
    }

    override fun toString(): String {
        return raw.toString()
    }

}
