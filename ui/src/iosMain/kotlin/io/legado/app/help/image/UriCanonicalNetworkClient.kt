package io.legado.app.help.image

import coil3.network.NetworkClient
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import io.legado.app.utils.canonicalizeUri

/**
 * Coil 图片链的 URI 语法合法化边界：把最终请求 url 改写为 RFC 3986 合法 URI 后交给 [delegate]。
 *
 * 外部约束：Darwin 引擎用 NSURLComponents 组装请求 URL，段内非法字符（空格、`{}`、`|`、`[]`、
 * 反引号、非 ASCII）或不成套的 `%`（RFC 3986 §2.1 要求 `%` 后恰跟两位十六进制）会抛
 * NSInvalidArgumentException —— ObjC 异常在 Kotlin/Native 无法捕获，进程直接终止。
 *
 * Coil 的 NetworkFetcher 经 `HttpClient.prepareRequest` 直达 ktor，消费的是字符串 url，
 * 不经过 [io.legado.app.help.http.KmpHttpUrl] 的「构造即合法」不变式，因此在跨入平台
 * 网络栈的边界上施加同一套合法化。
 *
 * 缓存维度保持调用方传入的原始 url（外层 [ImageGuardNetworkClient] 的拉黑查询、Coil 磁盘 key
 * 均在委托之前），本类只影响真正发出的请求行。
 */
internal class UriCanonicalNetworkClient(
    private val delegate: NetworkClient,
) : NetworkClient {

    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T = delegate.executeRequest(request.copy(url = request.url.canonicalizeUri()), block)
}
