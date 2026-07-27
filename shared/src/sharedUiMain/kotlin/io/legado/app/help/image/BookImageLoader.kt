package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap

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
 * - iOS / 鸿蒙: 暂未实现 (后续批补 Ktor 后端)。
 *
 * 模式参考 [io.legado.app.help.book.BookImageStorageProviders]。
 */
interface BookImageLoader {

    /**
     * 异步加载图片为 [ImageBitmap]。
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
}
