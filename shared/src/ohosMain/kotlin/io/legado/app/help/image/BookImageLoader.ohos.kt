package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.SourceHelp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 鸿蒙端 [BookImageLoader]: 复用 [ImageBitmapLoader] 的 OHOS 图像管线
 * (OHOS image_source/pixelmap/native_drawing 解码, 书源防盗链 header + coverDecodeJs
 * 解密, 见 ImageBitmapLoader.ohos.kt)。Coil3 无 ohosArm64 变体, 封面/模糊背景/歌词取色
 * 经本实现补全 (此前 BookImageLoaders.getOrNull() 恒 null, 音频页封面与模糊背景恒占位)。
 *
 * 模式参考 [io.legado.app.help.image.BookImageLoader.ios.kt] 的接口实现;
 * 注册时机: registerOhosProviders 内, AppDbProviders / OkHttpClientProviders 之后。
 */
class OhosBookImageLoader : BookImageLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun loadImage(
        url: String,
        sourceOrigin: String?,
        onSuccess: (ImageBitmap) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        scope.launch {
            try {
                onSuccess(
                    loadImageOrNull(url, sourceOrigin) ?: error("图片加载失败: $url")
                )
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    /**
     * 非持久区加载 (对齐 Coil 端同名方法的 persistent=false): 非书架书封面/书评头像/歌词取色/
     * 默认封面占位一律落临时缓存区。旧实现经 loadBitmap(isCover=true) 被连带判成持久区
     * (鸿蒙把"封面解密规则"与"字节分区"绑成了同一个参数), 现经 [ImageBitmapLoader.loadCoverBitmap] 分开传。
     */
    override suspend fun loadImageOrNull(
        url: String,
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
    ): ImageBitmap? = loadInternal(url, sourceOrigin, widthPx, heightPx, persistent = false)

    /** 书架/分组/音频页真封面: 解密后字节落封面持久区 (系统清缓存清不掉), 与 Coil 端同语义。 */
    override suspend fun loadCoverOrNull(
        url: String,
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
    ): ImageBitmap? = loadInternal(url, sourceOrigin, widthPx, heightPx, persistent = true)

    private suspend fun loadInternal(
        url: String,
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
        persistent: Boolean,
    ): ImageBitmap? {
        // 书源防盗链/解密上下文: 走 SourceHelp.getSource (享会话缓存与单飞去重); book 规则上下文可空
        // (ImageUtils.decode 的 book 参数默认 null, 解密脚本仅依赖 src/result 时不受影响)
        val bookSource = sourceOrigin?.takeIf { it.isNotBlank() }?.let {
            runCatching { SourceHelp.getSource(it) as? BookSource }.getOrNull()
        }
        return ImageBitmapLoader().loadCoverBitmap(
            url = url,
            bookSource = bookSource,
            widthPx = widthPx,
            heightPx = heightPx,
            persistent = persistent,
        )
    }

    /**
     * 鸿蒙无 Coil3 DiskCache, 封面字节只落在 [ImageBytesCache] 的持久子目录
     * (`bookCoverCacheDir/image_cache_p`), 所以下载层缓存 + 失败表就是该端的全部封面缓存。
     */
    override suspend fun clearCoverCache(): Boolean {
        withContext(IoDispatcher) {
            ImageBytesCache.clearPersistent()
            clearImageLoadFailures()
        }
        return true
    }
}

/** 注册 (调用时机: registerOhosProviders 内, AppDbProviders/OkHttpClientProviders 之后)。 */
fun registerOhosBookImageLoader() {
    BookImageLoaders.register(OhosBookImageLoader())
}
