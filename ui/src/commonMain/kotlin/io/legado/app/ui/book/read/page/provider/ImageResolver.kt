package io.legado.app.ui.book.read.page.provider

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import kotlin.concurrent.Volatile

/** 图片原始尺寸（px）。 */
data class ImageSize(val width: Int, val height: Int)

/**
 * 图片尺寸解析接口（commonMain，供平台注入）。
 *
 * 对应 app 端 `ImageProvider.getImageSize(book, src, bookSource)`：内部负责下载 / 磁盘缓存 /
 * 解码，排版层只查询尺寸。与原版一致，取不到图时返回错误占位尺寸而不是 0（原版返回
 * `errorBitmap` 的宽高），让排版仍产出图片行，由绘制层画错误占位。
 *
 * 四端宿主启动时都注册（[ImageResolverProviders]）；未注入时 [SimpleChapterLayout] 仍排图片行，
 * 只是尺寸取不到、退回默认占位方块。
 */
interface ImageResolver {

    /** 取图片原始尺寸；实现内部触发缓存下载（对应原版「始终调 getImageSize 触发缓存」）。 */
    suspend fun getImageSize(src: String): ImageSize
}

/**
 * [ImageResolver] 工厂注册处：实现在 sharedUiMain（需 Compose `ImageBitmap` 解码），
 * 由宿主启动时注册（Android `MainActivity` / desktop `Main.kt` / iOS、鸿蒙 ProviderRegistry）。
 *
 * 未注册时 [createOrNull] 返回 null。
 */
object ImageResolverProviders {

    @Volatile
    private var factory: ((Book, BookChapter, BookSource?) -> ImageResolver)? = null

    /** 宿主启动早期注册一次（任何章节排版之前）。 */
    fun register(factory: (Book, BookChapter, BookSource?) -> ImageResolver) {
        this.factory = factory
    }

    fun createOrNull(book: Book, chapter: BookChapter, bookSource: BookSource?): ImageResolver? =
        factory?.invoke(book, chapter, bookSource)
}
