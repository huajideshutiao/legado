package io.legado.app.help.http

import okhttp3.Cookie

/**
 * KmpCookie jvmAndAndroid actual: typealias okhttp3.Cookie (行为零 diff)。
 *
 * Cookie.name/value/persistent 与 expect 声明一致, typealias 后直接可用。
 * 与 [KmpHttpTypes.jvmAndAndroid] 同构 (KmpRequest/KmpResponse 均 typealias okhttp3.*)。
 */
actual typealias KmpCookie = Cookie

/**
 * 解析响应 Set-Cookie 头 (actual)。
 *
 * 1:1 委托 `okhttp3.Cookie.parseAll(url, headers)`, 与 app 端 CookieManager.saveCookiesFromHeaders
 * 完全一致。typealias 让 KmpHttpUrl=HttpUrl / KmpHeaders=Headers, 参数类型零转换。
 */
actual fun parseResponseCookies(url: KmpHttpUrl, headers: KmpHeaders): List<KmpCookie> {
    return Cookie.parseAll(url, headers)
}
