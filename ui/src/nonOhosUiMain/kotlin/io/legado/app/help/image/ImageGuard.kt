package io.legado.app.help.image

import coil3.network.HttpException
import coil3.network.NetworkClient
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import io.legado.app.exception.NoStackTraceException

/**
 * 图片链失败 url 进程级跳过表 (原版 Glide OkHttpStreamFetcher.failUrl 语义, android/jvm/ios
 * 三端共享单份): 非 2xx 响应 ([ImageGuardNetworkClient] catch HttpException) 与封面解密失败
 * ([SourceHeaderNetworkClient]) 的 url 进表, 真正发起网络请求前拦截。
 *
 * key 为 NetworkRequest.url (okhttp/ktor 规范化串), 表内 add/查询自洽; 极端畸形 URL 的
 * 规范化差异仅导致该死链多请求一次。
 *
 * 存储与过期策略见 [isImageLoadFailed] (对照原版的改进: 原版是无上限、无过期的裸 HashSet,
 * 一次偶发 403 会把该封面永久拉黑到进程结束)。
 */
internal fun isFailUrl(url: String): Boolean = isImageLoadFailed(url)

internal fun markFailUrl(url: String) = markImageLoadFailed(url)

/**
 * 图片链网络层守卫 NetworkClient (android/jvm/ios 三端共享单份, 替代原 okhttp callFactory
 * 守卫与 iOS 版 IosGuardNetworkClient 两份实现): failUrls 死链跳过 + 非 2xx 拉黑,
 * 拦截点在 Coil3 磁盘缓存查询 (NetworkFetcher 内部) 之后 —— 缓存命中不经过此守卫,
 * 对齐原版 Glide "磁盘缓存命中不经过 DataFetcher.loadData, failUrl 只拦网络" 的语义。
 */
internal class ImageGuardNetworkClient(
    private val delegate: NetworkClient,
) : NetworkClient {

    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T {
        if (isFailUrl(request.url)) {
            throw NoStackTraceException("跳过加载失败的图片")
        }
        return try {
            delegate.executeRequest(request, block)
        } catch (e: HttpException) {
            // 非 2xx 响应拉黑 (NetworkFetcher 对非 2xx/非 304 抛 HttpException, 2xx 才会进
            // 写入磁盘缓存, 死链不污染磁盘缓存);
            // 原 CoverDecodeFetcher 的 catch 语义上移至此
            markFailUrl(request.url)
            throw e
        }
    }
}
