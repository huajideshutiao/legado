package com.github.catvod.net.interceptor

import com.github.catvod.bean.Header
import com.github.catvod.utils.Json
import com.github.catvod.utils.Util
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** TVBox 壳响应拦截器 (防挂头/deflate/302 记忆, 签名对齐 FongMi catvod 模块的同名实现)。 */
class ResponseInterceptor : Interceptor {

    private val headers = CopyOnWriteArrayList<Header>()
    private val redirectMap = ConcurrentHashMap<String, String>()

    fun addAll(items: List<Header>) {
        headers.addAll(items)
    }

    fun clear() {
        headers.clear()
        redirectMap.clear()
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = check(chain.request())
        val response = chain.proceed(request)
        val encoding = response.header("Content-Encoding")
        if ("deflate".equals(encoding, ignoreCase = true)) return deflate(response)
        if (response.code == 406 && redirectMap.containsKey(request.url.toString())) return redirect(request, response)
        if (response.code == 302 && response.header("Location") != null) {
            redirectMap[response.header("Location")!!] = request.url.toString()
        }
        return response
    }

    private fun check(request: Request): Request {
        val host = request.url.host
        val builder = request.newBuilder()
        for (item in headers) if (Util.containOrMatch(host, item.getHost())) {
            Json.toMap(item.getHeader()).forEach { (key, value) -> builder.header(key, value) }
        }
        return builder.build()
    }

    private fun redirect(request: Request, response: Response): Response =
        Response.Builder()
            .request(request)
            .protocol(response.protocol)
            .code(302)
            .message("Found")
            .header("Location", redirectMap[request.url.toString()]!!)
            .build()

    private fun deflate(response: Response): Response {
        val stream: InputStream = InflaterInputStream(response.body.byteStream(), Inflater(true))
        return response.newBuilder().headers(response.headers).body(getBody(response, stream)).build()
    }

    private fun getBody(response: Response, stream: InputStream): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType? = response.body.contentType()

        override fun contentLength(): Long = -1

        override fun source(): BufferedSource = stream.source().buffer()
    }
}
