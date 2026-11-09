package io.legado.app.help.image

import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import coil3.asImage
import coil3.toBitmap

/** 唯一适配器: 与 iOS 真实请求共用同一 iosCoilImageLoader 的 memoryCache。 */
private val coverStore: Lazy<CoverReuseStore> = lazy {
    val cache = iosCoilImageLoader.memoryCache
        ?: error("封面复用需要现有图片缓存: iosCoilImageLoader 未启用内存缓存")
    CoilCoverReuseStore(
        memoryCache = cache,
        toCoilImage = { it.asSkiaBitmap().asImage() },
        toImageBitmap = { it.toBitmap().asComposeImageBitmap() },
    )
}

/** 只读: 缓存尚未建立时无项可读, 且不触初始化。 */
internal actual fun currentCoverReuseStore(): CoverReuseStore? =
    if (coverStore.isInitialized()) coverStore.value else null

/** 登记: 用同一个 Lazy 的真实初始化路径; 初始化失败由库自身抛出, 不会缓存失败结果。 */
internal actual fun coverReuseStoreForWrite(): CoverReuseStore = coverStore.value
