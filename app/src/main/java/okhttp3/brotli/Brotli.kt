// 兼容 shim: KeiSource(扩展 APK 内)构建客户端时引用 okhttp3.brotli.Brotli 与 CompressionInterceptor(Brotli, Gzip, Zstd)。
// 宿主未引入 okhttp-brotli 工件, 此 shim 对外仅协商 gzip, 避免 br 响应无法解码;
// 引入真实 okhttp-brotli 依赖后需删除本文件避免重复类。
package okhttp3.brotli

import okhttp3.CompressionInterceptor
import okhttp3.Interceptor
import okhttp3.Response
import okio.BufferedSource
import okio.GzipSource
import okio.Source

object Brotli : CompressionInterceptor.DecompressionAlgorithm {

    // 对外只声明 gzip: br 解码不可用, 若声明 br 会诱导服务器返回无法解压的响应
    override val encoding: String = "gzip"

    override fun decompress(compressedSource: BufferedSource): Source = GzipSource(compressedSource)
}

object BrotliInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Accept-Encoding", "gzip")
            .build()
        return chain.proceed(request)
    }
}
