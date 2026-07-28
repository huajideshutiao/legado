package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * [ImageBitmapLoader] 的 iOS Coil3 实现 (复用 [iosCoilImageLoader] 共享管线:
 * 防盗链 Interceptor + Ktor3 网络后端 + 磁盘/内存缓存 + Skia 解码)。
 *
 * - `file://` / 绝对路径 / `http(s)://`: Coil3 统一处理 (对照 jvmMain JvmImageBitmapLoader 分支)
 * - `cbz://`: 前置拦截直解, 不进 Coil3 管线 ([loadCbzEntryBytes] 经 ArchiveProviders 抽
 *   压缩包条目字节 → Skia 解码; 较自定义 Fetcher 改动小, 且 cbz 页无需网络/磁盘缓存)
 * - 失败: 返回 null (调用方负责占位/日志)
 */
actual class ImageBitmapLoader actual constructor() {

    actual suspend fun loadBitmap(url: String, book: Book?, bookSource: BookSource?): ImageBitmap? {
        if (url.startsWith("cbz://")) {
            return withContext(Dispatchers.IO) {
                loadCbzEntryBytes(url, book?.bookUrl)?.let { bytes ->
                    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
                }
            }
        }
        return runCatching {
            val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(url)
                .sourceOrigin(bookSource?.bookSourceUrl)
                .build()
            val result = iosCoilImageLoader.execute(request)
            (result as? SuccessResult)?.image?.toBitmap()?.asComposeImageBitmap()
        }.getOrNull()
    }

    /**
     * iOS 端仅 `cbz://` 走裸字节 (与 [loadBitmap] 同一前置直解路径); 其余 scheme 返回 null。
     *
     * 网络/本地图由 Coil3 管线接管 (磁盘缓存 + 防盗链 Interceptor), 其 ImageRequest 不暴露原始
     * 字节流; 且 iOS 动图已由 Coil3/系统解码承担 (见 [decodeAnimatedFrames] 的 iOS actual),
     * 本方法无动图消费点, 不值得为取字节另开一条绕过缓存的下载链路。
     */
    actual suspend fun loadBytes(url: String, book: Book?, bookSource: BookSource?): ByteArray? {
        if (!url.startsWith("cbz://")) return null
        return withContext(Dispatchers.IO) { loadCbzEntryBytes(url, book?.bookUrl) }
    }
}
