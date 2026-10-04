// JVM 集成测试桩: keiyoushi 扩展基类 (KeiSource) 校验宿主 client 链必须含
// simpleName 为 "CloudflareInterceptor" 的拦截器 (真类与 WebView 挑战处理留在
// app 模块, desktop 测试 classpath 无 WebView) —— 本桩仅满足链路校验, 请求直通。
package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class CloudflareInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
