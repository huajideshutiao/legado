package io.legado.app.help.http

/**
 * 透明 gzip / deflate 解压拦截器。
 *
 * 原实现位于 jvmAndAndroidMain, 因依赖 okhttp3.internal.http.promisesBody (internal API)
 * 与 java.util.zip.{GZIPInputStream, Inflater, InflaterInputStream} 无法直接进 commonMain;
 * 现以 [promisesBody] / [decompressBody] 两个 expect 函数包装 JVM 专属依赖, 主体逻辑下沉 commonMain。
 *
 * 行为零 diff: 入参 / 出参 / header 处理 / when 分支顺序与原文件一致。
 *
 * KP4 OkHttp 跨平台修复: 原直接 `import okhttp3.Interceptor/Response/ResponseBody`,
 * iOS/鸿蒙 target 无 OkHttp 变体编译失败; 现改用 [KmpInterceptor]/[KmpResponse]/[KmpResponseBody]
 * 跨平台抽象 (jvmAndAndroidMain 经 typealias 等价 okhttp3.*; iOS/鸿蒙 由 nativeMain 用 Ktor 包装实现)。
 * jvm/android 行为与原实现完全一致 (零 diff)。
 */
object DecompressInterceptor : KmpInterceptor {
    override fun intercept(chain: KmpInterceptorChain): KmpResponse {
        val request = chain.request()
        val requestBuilder = request.newBuilder()

        var transparentDecompress = false
        if (request.header("Accept-Encoding") == null && request.header("Range") == null) {
            transparentDecompress = true
            requestBuilder.header("Accept-Encoding", "gzip, deflate")
        }

        val response = chain.proceed(requestBuilder.build())
        val body = response.body

        if (!transparentDecompress || !response.promisesBody() || body == KmpResponseBody.EMPTY) {
            return response
        }

        val encoding = response.header("Content-Encoding")?.lowercase()
        // KP4 OkHttp 跨平台修复: decompressBody expect 返回 Any? (commonMain 不引用 okio.BufferedSource),
        // actual 在 jvmAndAndroidMain 返回 okio.BufferedSource, 此处直接传给 asKmpResponseBody expect fun。
        // iOS/鸿蒙 actual 返回 null, 此分支不会进入 (promisesBody=false 早返回)。
        val source = decompressBody(body, encoding) ?: return response

        return response.newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .body(source.asKmpResponseBody(body.contentType(), -1))
            .build()
    }
}
