// Copyright The Mihon Authors. Apache-2.0.
package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.interceptor.ChallengeCookieResolver
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

// remove/get 的默认参数下沉到接口, 兼容层与书源栈适配器共用同一抽象
// (Kotlin override 不得声明默认值)
class AndroidCookieJar : CookieJar, ChallengeCookieResolver {

    private val manager = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()

        cookies.forEach { manager.setCookie(urlString, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return get(url)
    }

    override fun get(url: HttpUrl): List<Cookie> {
        val cookies = manager.getCookie(url.toString())

        return if (cookies != null && cookies.isNotEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(url, it) }
        } else {
            emptyList()
        }
    }

    override fun remove(url: HttpUrl, cookieNames: List<String>?, maxAge: Int): Int {
        val urlString = url.toString()
        val cookies = manager.getCookie(urlString) ?: return 0

        fun List<String>.filterNames(): List<String> {
            return if (cookieNames != null) {
                this.filter { it in cookieNames }
            } else {
                this
            }
        }

        return cookies.split(";")
            .map { it.substringBefore("=") }
            .filterNames()
            .onEach { manager.setCookie(urlString, "$it=;Max-Age=$maxAge") }
            .count()
    }
}
