// 桌面端扩展 client 链的 Cloudflare 拦截面。keiyoushi 扩展基类 (KeiSource) 校验宿主
// client 链上必须存在 simpleName 为 CloudflareInterceptor 的拦截器 (真 WebView 类留在
// app 模块, JVM 端无 WebView) —— 本类借 simpleName 满足契约, 挑战求解委托桌面引擎
// (DesktopCloudflareInterceptor): 无引擎注册 (headless) 时直通。
package eu.kanade.tachiyomi.network.interceptor

import io.legado.desktop.http.DesktopCloudflareInterceptor

class CloudflareInterceptor : DesktopCloudflareInterceptor()
