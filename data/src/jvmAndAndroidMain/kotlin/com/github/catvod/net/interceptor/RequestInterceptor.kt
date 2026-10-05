package com.github.catvod.net.interceptor

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** TVBox 壳 auth 查询参数记忆拦截器 (签名对齐 FongMi catvod 模块的同名实现)。 */
class RequestInterceptor : Interceptor {

    private val authMap = ConcurrentHashMap<String, String>()

    fun clear() {
        authMap.clear()
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
        val url: HttpUrl = request.url
        checkAuth(url, builder)
        return chain.proceed(builder.build())
    }

    private fun checkAuth(url: HttpUrl, builder: okhttp3.Request.Builder) {
        val host = url.host
        val auth = url.queryParameter("auth")
        if (auth != null) authMap[host] = auth
        else if (authMap.containsKey(host)) builder.url(url.newBuilder().addQueryParameter("auth", authMap[host]).build())
    }
}
