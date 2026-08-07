package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.data.AppDbProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    override suspend fun loadImageOrNull(
        url: String,
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
    ): ImageBitmap? {
        // 书源防盗链/解密上下文: sourceOrigin 查 DB; book 规则上下文可空
        // (ImageUtils.decode 的 book 参数默认 null, 解密脚本仅依赖 src/result 时不受影响)
        val bookSource = sourceOrigin?.takeIf { it.isNotBlank() }?.let {
            runCatching { AppDbProviders.get().bookSourceDao.getBookSource(it) }.getOrNull()
        }
        return ImageBitmapLoader().loadBitmap(
            url = url,
            book = null,
            bookSource = bookSource,
            isCover = true,
            widthPx = widthPx,
            heightPx = heightPx,
            useBitmapCache = true,
        )
    }
}

/** 注册 (调用时机: registerOhosProviders 内, AppDbProviders/OkHttpClientProviders 之后)。 */
fun registerOhosBookImageLoader() {
    BookImageLoaders.register(OhosBookImageLoader())
}
