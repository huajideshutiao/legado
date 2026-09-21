package io.legado.app.help.http

/**
 * Cookie 跨平台抽象 (expect/actual)。
 *
 * app 端 CookieManager.saveResponse 用 `okhttp3.Cookie.parseAll(url, headers)` 解析响应 Set-Cookie,
 * 并按 `it.persistent` 区分会话/持久 cookie。OkHttp 仅发布 common+android+jvm 变体,
 * iOS/鸿蒙 native target 无对应类型, 故抽象 KmpCookie 让 commonMain 的 SharedCookieJarBridge
 * 可跨平台解析响应 cookie。
 *
 * - jvmAndAndroidMain: `actual typealias KmpCookie = okhttp3.Cookie` (行为零 diff, Cookie.name/value/persistent 直接可用);
 * - nativeMain: 简单 data class, 由 [parseResponseCookies] 纯 Kotlin 解析 Set-Cookie 头填充。
 *
 * 与 [KmpHttpTypes] 同构: typealias actual 让 jvm/android 端零改动, native 端独立实现。
 */
expect class KmpCookie {
    val name: String
    val value: String
    val persistent: Boolean
}

/**
 * 解析响应 Set-Cookie 头为 [KmpCookie] 列表 (对应 app 端 `okhttp3.Cookie.parseAll(url, headers)`)。
 *
 * - jvmAndAndroidMain: 委托 `okhttp3.Cookie.parseAll(url, headers)` (1:1 与 app 端一致);
 * - nativeMain: 纯 Kotlin 解析 Set-Cookie 头 (RFC 6265 子集: name=value; Expires/Max-Age 判持久)。
 *
 * iOS/鸿蒙 HTTP 引擎 (Ktor/@ohos.net.http) 响应头经 [KmpHeaders] 统一暴露,
 * Set-Cookie 多值通过 `headers.toMultimap()["Set-Cookie"]` 取出逐条解析。
 */
expect fun parseResponseCookies(url: KmpHttpUrl, headers: KmpHeaders): List<KmpCookie>

/**
 * Cookie 列表转 "name=value; name=value" 字符串 (对应 app 端 CookieManager 私有扩展 `List<Cookie>.toCookieString`)。
 *
 * 跨平台共享, 避免在 SharedCookieJarBridge 各端重复定义。
 */
fun List<KmpCookie>.toCookieString(): String {
    return joinToString("; ") { "${it.name}=${it.value}" }
}
