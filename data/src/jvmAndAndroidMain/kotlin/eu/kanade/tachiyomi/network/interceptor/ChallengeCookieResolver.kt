// Copyright The Mihon Authors. Apache-2.0.
// 由 app 模块 CloudflareInterceptor.kt 下沉拆出: AndroidCookieJar (本模块) 实现它,
// CloudflareInterceptor (app 模块) 消费它, 拦截器与 cookie 栈因此可分模块编译。
package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * CF 挑战所需 cookie 最小能力: get 读 WebView cookie 落地处, remove 在解挑战前清旧值。
 * 兼容层 [AndroidCookieJar] (webkit CookieManager) 与书源栈适配器 (SharedCookieJarBridge/
 * CookieStore 体系) 各自实现; 拦截器实例各自持有, host 去重锁互不串味。
 */
interface ChallengeCookieResolver {

    fun get(url: HttpUrl): List<Cookie>

    fun remove(url: HttpUrl, cookieNames: List<String>?, maxAge: Int): Int
}
