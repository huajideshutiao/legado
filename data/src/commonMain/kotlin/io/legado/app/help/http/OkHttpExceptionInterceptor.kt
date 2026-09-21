package io.legado.app.help.http

import okio.IOException

/**
 * OkHttp 异常包装拦截器。
 *
 * 把非 [IOException] 的 Throwable 包装为 [IOException], 让 OkHttp 重试机制生效。
 *
 * KP4 OkHttp 跨平台修复: 原直接 `import okhttp3.Interceptor/Response`,
 * iOS/鸿蒙 target 无 OkHttp 变体编译失败; 现改用 [KmpInterceptor]/[KmpInterceptorChain]/[KmpResponse]
 * 跨平台抽象 (jvmAndAndroidMain 经 typealias 等价 okhttp3.*; iOS/鸿蒙 由 nativeMain 用 Ktor 包装实现)。
 * jvm/android 行为与原实现完全一致 (零 diff)。
 */
object OkHttpExceptionInterceptor : KmpInterceptor {

    // 注: Kotlin/Native 要求 override 与父声明 @Throws 过滤器一致,
    // KmpInterceptor.intercept 无 @Throws, 故此处不能单独标注 (仍抛 IOException)。
    override fun intercept(chain: KmpInterceptorChain): KmpResponse {
        try {
            return chain.proceed(chain.request())
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            // okio common 无 IOException(Throwable) 构造器; message 取 e.toString() 与 JVM 单参构造行为一致
            throw IOException(e.toString(), e)
        }
    }

}
