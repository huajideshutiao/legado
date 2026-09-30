package io.legado.app.help.http

/**
 * iOS actual: 使用 Ktor Darwin engine 构造客户端 (见 [KmpHttpClientBuilder.build])。
 */
internal actual fun buildNativeProxyClient(
    host: String,
    port: Int,
    username: String?,
    password: String?,
): KmpHttpClient = KmpHttpClientBuilder()
    .proxy(host, port, username, password)
    .build()
