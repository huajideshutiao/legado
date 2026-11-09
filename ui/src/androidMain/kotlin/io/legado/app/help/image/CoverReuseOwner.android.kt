package io.legado.app.help.image

import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.asImage
import coil3.memory.MemoryCache
import coil3.toBitmap
import io.legado.app.ui.platform.sharedAppContext

/**
 * 封面复用的 Android 缓存来源: 与真实图片请求同一份内存缓存。
 *
 * 顺序: 已建立的请求单例 → 已绑定加载器自带 Context → 宿主已注入的共享 Context,
 * 三路都落到同一个 [androidBookImageLoader] 实例, 因此封面复用与真实请求同库。
 */
private fun requestMemoryCache(): MemoryCache? {
    // 1) 真实请求单例已建立: 直接用它 (与是否注册加载器 / 是否注入共享 Context 无关)
    existingAndroidBookImageLoader()?.let { return it.memoryCache }
    // 2) 已绑定的加载器带着自己的构造 Context: 用它建立同一个 androidBookImageLoader 单例
    (BookImageLoaders.getOrNull() as? AndroidBookImageLoader)?.requestMemoryCache?.let { return it }
    // 3) 宿主已注入的共享 Context: 同一个单例入口
    val context = sharedAppContext ?: return null
    return androidBookImageLoader(context).memoryCache
}

/** 唯一适配器: 与真实请求共用同一 memoryCache 与同一把用途登记锁。 */
private val coverStore: Lazy<CoverReuseStore> = lazy {
    val cache = requestMemoryCache()
        ?: error("封面复用需要现有图片缓存: 实际请求库未建立且没有可用的应用 Context")
    CoilCoverReuseStore(
        memoryCache = cache,
        toCoilImage = { it.asAndroidBitmap().asImage() },
        toImageBitmap = { it.toBitmap().asImageBitmap() },
    )
}

/** 只读: 缓存尚未建立时无项可读, 且不触初始化。 */
internal actual fun currentCoverReuseStore(): CoverReuseStore? =
    if (coverStore.isInitialized()) coverStore.value else null

/** 登记: 用同一个 Lazy 的真实初始化路径; 初始化失败由库自身抛出, 不会缓存失败结果。 */
internal actual fun coverReuseStoreForWrite(): CoverReuseStore = coverStore.value

/**
 * 登记 (加载器自带合法 Context 时): 先用它建立同一个请求单例, 再取同一份缓存所有者。
 *
 * 与无参路径落到同一个 [coverStore], 不另建适配器或第二把锁。
 */
internal fun coverReuseStoreForWrite(context: Context): CoverReuseStore {
    androidBookImageLoader(context.applicationContext)
    return coverStore.value
}
