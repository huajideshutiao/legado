// 兼容 shim: KeiSource(扩展 APK 内)构建客户端时引用 okhttp3.zstd.Zstd 与 CompressionInterceptor(Brotli, Gzip, Zstd)。
// 宿主未引入 okhttp-zstd/zstd-kmp-okio 工件, 此 shim 对外仅协商 gzip;
// 引入真实 okhttp-zstd 依赖后需删除本文件避免重复类。
package okhttp3.zstd

import okhttp3.CompressionInterceptor
import okio.BufferedSource
import okio.GzipSource
import okio.Source

object Zstd : CompressionInterceptor.DecompressionAlgorithm {

    // 对外只声明 gzip: zstd 解码不可用, 若声明 zstd 会诱导服务器返回无法解压的响应
    override val encoding: String = "gzip"

    override fun decompress(compressedSource: BufferedSource): Source = GzipSource(compressedSource)
}
