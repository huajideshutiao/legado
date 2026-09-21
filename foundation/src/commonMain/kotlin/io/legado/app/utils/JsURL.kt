package io.legado.app.utils

/**
 * URL 解析 expect/actual 门面。
 *
 * 原 jvmAndAndroidMain 件直接依赖 java.net.URL / java.net.URLDecoder,
 * 进不了 commonMain; 现下沉为 expect/actual:
 * - commonMain 暴露 [JsURL] class 与 4 个只读属性 (searchParams/host/origin/pathname),
 *   调用处 `JsURL(url, baseUrl)` 写法不变;
 * - jvmAndAndroidMain actual 用 java.net.URL 解析 + URLDecoder 解码 query (行为不变)。
 *
 * 公开 API 兼容性: 类名/构造参数/属性签名零改动; 原 jvmAndAndroidMain 调用方
 * (JsExtensionsCommon.toURL) 自动解析到 actual 实现。
 */
expect class JsURL(url: String, baseUrl: String? = null) {
    val searchParams: Map<String, String>?
    val host: String
    val origin: String
    val pathname: String
}
