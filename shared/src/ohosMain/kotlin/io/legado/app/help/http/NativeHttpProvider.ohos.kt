package io.legado.app.help.http

/**
 * 鸿蒙 actual: @ohos.net.http HttpProxy 的 http/https 代理 (API 12+ 原生带账号密码,
 * 见 [KmpHttpClientBuilder.proxy])。
 */
internal actual fun buildNativeProxyClient(
    host: String,
    port: Int,
    username: String?,
    password: String?,
): KmpHttpClient = KmpHttpClientBuilder()
    .proxy(host, port, username, password)
    .build()
