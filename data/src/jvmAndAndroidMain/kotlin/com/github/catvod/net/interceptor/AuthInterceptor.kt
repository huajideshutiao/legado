package com.github.catvod.net.interceptor

import com.github.catvod.utils.Auth
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** TVBox 壳 HTTP Basic/Digest 鉴权拦截器 (签名对齐 FongMi catvod 模块的同名实现)。 */
class AuthInterceptor : Interceptor {

    private val userMap = ConcurrentHashMap<String, String>()

    fun clear() {
        userMap.clear()
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = check(chain.request())
        val response = chain.proceed(request)
        if (response.code != 401) return response
        val host = request.url.host
        val user = request.url.toUri().userInfo ?: userMap[host]
        if (user == null) return response
        response.close()
        val header = response.header("WWW-Authenticate")
        val auth = if (digest(header)) Auth.digest(user, header!!, request) else Auth.basic(user)
        return chain.proceed(request.newBuilder().header("Authorization", auth).build())
    }

    private fun digest(header: String?): Boolean =
        header != null && header.startsWith("Digest")

    private fun check(request: Request): Request {
        val uri: URI = request.url.toUri()
        val userInfo = uri.userInfo ?: return request
        userMap[request.url.host] = userInfo
        return request.newBuilder().header("Authorization", Auth.basic(userInfo)).build()
    }
}
