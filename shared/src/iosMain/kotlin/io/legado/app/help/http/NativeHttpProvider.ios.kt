package io.legado.app.help.http

/**
 * iOS actual: Ktor CIO engine 的 http/https 代理 (认证经 Proxy-Authorization 头预置,
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
