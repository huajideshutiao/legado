package io.legado.app.help.http

import okio.IOException

/**
 * OkHttp 异常包装拦截器。
 *
 * 把非 [IOException] 的 Throwable 包装为 [IOException], 让 OkHttp 重试机制生效。
 *
 * KP4 OkHttp 跨平台修复: 原直接 `import okhttp3.Interceptor/Response`,
 * iOS/鸿蒙 target 无 OkHttp 变体编译失败; 现改用 [KmpInterceptor]/[KmpInterceptorChain]/[KmpResponse]
 * 跨平台抽象 (jvmAndAndroidMain 经 typealias 等价 okhttp3.*; iOS/鸿蒙 stub)。
 * jvm/android 行为与原实现完全一致 (零 diff)。
 */
object OkHttpExceptionInterceptor : KmpInterceptor {

    @Throws(IOException::class)
    override fun intercept(chain: KmpInterceptorChain): KmpResponse {
        try {
            return chain.proceed(chain.request())
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            throw IOException(e)
        }
    }

}
