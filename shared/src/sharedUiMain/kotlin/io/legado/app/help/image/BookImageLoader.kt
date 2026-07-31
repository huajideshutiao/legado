package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.Volatile

/**
 * Compose 图片加载跨平台抽象 (Coil3 迁移批 1 共享面)。
 *
 * 替代 app 端 Glide ImageLoader.load(context, url, sourceOrigin) 系列入口,
 * 供 sharedUiMain / app / desktop 的 Composable 直接调用。本批仅建共享面 + 接线,
 * 不替换既有 Glide 消费点 (Glide 与 Coil3 共存)。
 *
 * [sourceOrigin] 用于书源防盗链 header 注入: 传入书源 bookUrl 作为 key,
 * 实现内部按 [io.legado.app.help.source.SourceHelp.getSource] 解析书源 header
 * (对齐 app 端 Glide OkHttpModelLoader.sourceOriginOption 行为)。
 *
 * 实现注册:
 * - Android: app 端 App.onCreate 早期调用 [BookImageLoaders.register] 注入
 *   AndroidBookImageLoader (基于 Coil3 ImageLoader + AsyncImage)。
 * - 桌面 JVM: desktop Main.kt 注入 JvmBookImageLoader。
 * - iOS: registerIosProviders 注入 IosBookImageLoader (Coil3 + Ktor3 网络后端)。
 * - 鸿蒙: 未注册 (coil3 无 ohosArm64 变体), 消费点经 [BookImageLoaders.getOrNull] 拿到 null,
 *   恒走内置占位图。
 *
 * 模式参考 [io.legado.app.help.book.BookImageStorageProviders]。
 */
interface BookImageLoader {

    /**
     * 异步加载图片为 [ImageBitmap]。
     *
     * 注: 走实现方自己的 CoroutineScope, 调用方取消不了; 列表条目请改用 [loadImageOrNull]。
     *
     * @param url 图片 URL
     * @param sourceOrigin 书源 bookUrl (可为 null), 用于防盗链 header 注入
     * @param onSuccess 成功回调
     * @param onError 失败回调
     */
    fun loadImage(
        url: String,
        sourceOrigin: String?,
        onSuccess: (ImageBitmap) -> Unit,
        onError: (Throwable) -> Unit
    )

    /**
     * 挂起版加载: 在调用方协程里执行, 随之取消 (列表条目滚出视口即中止下载/解码)。
     *
     * [widthPx]/[heightPx] 均 > 0 时按目标尺寸降采样解码 (Scale.FILL + Precision.INEXACT,
     * 对齐消费端的 ContentScale.Crop); 否则解原图 —— 同屏几十张封面时决定性的开销差别。
     *
     * @return 失败返回 null (不抛)
     */
    suspend fun loadImageOrNull(
        url: String,
        sourceOrigin: String?,
        widthPx: Int = 0,
        heightPx: Int = 0,
    ): ImageBitmap?

    /**
     * 书籍封面加载: 同 [loadImageOrNull], 但磁盘缓存落**持久区** (应用数据目录),
     * 系统清缓存/用户清缓存都清不掉 —— 书源失效后封面不可重获, 对齐原版 Glide
     * `MultiDiskCacheFactory` 的 `filesDir/covers` 分区。
     *
     * 默认实现等同 [loadImageOrNull] (未分区的平台按原行为走)。
     */
    suspend fun loadCoverOrNull(
        url: String,
        sourceOrigin: String?,
        widthPx: Int = 0,
        heightPx: Int = 0,
    ): ImageBitmap? = loadImageOrNull(url, sourceOrigin, widthPx, heightPx)
}

/**
 * [BookImageLoader] provider 容器。宿主启动早期注册一次。
 */
object BookImageLoaders {

    @Volatile
    private var impl: BookImageLoader? = null

    /** 宿主启动早期注册一次 (任何 Composable 图片加载之前)。 */
    fun register(impl: BookImageLoader) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): BookImageLoader = impl ?: error("BookImageLoader not registered")

    /** 获取已注册实现, 未注册返回 null (供 ohos 等未注册平台安全回退占位)。 */
    fun getOrNull(): BookImageLoader? = impl
}
