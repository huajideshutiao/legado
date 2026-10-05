package com.github.catvod.net

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.net.ProtocolException

/** TVBox 壳代理重定向拦截器 (语义对齐 FongMi catvod 模块的同名实现)。 */
internal class ProxyRedirectInterceptor(private val selector: OkProxySelector) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var previousPolicy: OkProxySelector.Policy? = null
        var previousResponse: Response? = null
        var followUpCount = 0
        while (true) {
            var policy: OkProxySelector.Policy? = null
            var routed: Interceptor.Chain = chain
            if (chain.proxy == null && chain.proxySelector === selector) {
                policy = selector.policy(request.url.toUri(), previousPolicy)
                if (previousPolicy != null && !policy.sharesProxyCredentials(previousPolicy)) {
                    request = request.newBuilder().removeHeader("Proxy-Authorization").build()
                }
                request = request.newBuilder().tag(OkProxySelector.Policy::class.java, policy).build()
                routed = chain.withProxySelector(policy)
            }
            var response = routed.proceed(request)
            var prior = response.priorResponse
            while (prior != null) {
                followUpCount++
                prior = prior.priorResponse
            }
            response = withPriorResponse(response, previousResponse)
            if (followUpCount > 20) {
                response.close()
                throw ProtocolException("Too many follow-up requests: $followUpCount")
            }
            val followUp = redirect(response, chain.followSslRedirects)
            if (followUp == null) return response
            val body = followUp.body
            if (body != null && body.isOneShot()) return response
            response.close()
            if (++followUpCount > 20) throw ProtocolException("Too many follow-up requests: $followUpCount")
            previousResponse = response.newBuilder().body(ResponseBody.EMPTY).build()
            previousPolicy = policy
            request = followUp
        }
    }

    private fun withPriorResponse(response: Response, previous: Response?): Response {
        if (previous == null) return response
        val prior = response.priorResponse
        return response.newBuilder()
            .priorResponse(if (prior == null) previous else withPriorResponse(prior, previous))
            .build()
    }

    private fun redirect(response: Response, followSslRedirects: Boolean): Request? {
        val code = response.code
        if (code != 300 && code != 301 && code != 302 && code != 303 && code != 307 && code != 308) return null
        val location = response.header("Location") ?: return null
        val source = response.request.url
        val target: HttpUrl = source.resolve(location) ?: return null
        if (source.scheme != target.scheme && !followSslRedirects) return null
        val builder = response.request.newBuilder()
        val method = response.request.method
        if (method != "GET" && method != "HEAD") {
            if (redirectsToGet(method, code)) {
                builder.method("GET", null)
                    .removeHeader("Transfer-Encoding")
                    .removeHeader("Content-Length")
                    .removeHeader("Content-Type")
            } else {
                builder.method(method, response.request.body)
            }
        }
        if (source.scheme != target.scheme || source.host != target.host || source.port != target.port) {
            builder.removeHeader("Authorization")
        }
        return builder.url(target).build()
    }

    private fun redirectsToGet(method: String, code: Int): Boolean {
        if (code == 303) return method != "PROPFIND"
        if (code == 307 || code == 308) return false
        return method != "PROPFIND" && method != "QUERY"
    }
}
