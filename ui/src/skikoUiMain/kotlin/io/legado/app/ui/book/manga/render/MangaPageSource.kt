package io.legado.app.ui.book.manga.render

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.image.DecodedImageResult

/**
 * 一页漫画的加载入口 (Skia 三端)。
 *
 * - desktop / iOS actual: 走各端的 Coil3 ImageLoader —— 内存缓存、磁盘缓存、请求去重、
 *   预载写缓存全部交给 Coil (与 app 端 `AndroidMangaReaderPlatform` 同机制),
 *   自研部分只剩解码器 (`MangaPageDecoder`, 因 coil-gif 无 jvm/ios 变体, 动图必须自己解)
 * - 鸿蒙 actual: 无 coil3 变体, 保留"取字节 + 解码 + DecodedBitmapCache"手写链路
 *
 * @param skipMemoryCache "重新加载" 用: 不读内存缓存 (仍写回)
 */
internal expect suspend fun loadMangaPage(
    url: String,
    book: Book,
    source: BookSource?,
    skipMemoryCache: Boolean,
): DecodedImageResult?

/** 同步窥视已缓存的一页: 命中则组合首帧直接出图, 不闪 Loading 态。 */
internal expect fun peekMangaPage(url: String, source: BookSource?): DecodedImageResult?

/** 预载一页漫画: 结果只写缓存不返回, 翻到该页时 [loadMangaPage] 直接命中。 */
internal expect suspend fun preloadMangaPage(
    url: String,
    book: Book,
    source: BookSource?,
)

/**
 * 漫画页预载 (desktop/iOS/鸿蒙的 `Platform.preloadImage` 共用同一份)。
 *
 * 与 [loadMangaPage] 同一条缓存, 所以翻页时不必再现场解码 —— 这是"预载了却还要卡一下
 * 才出图"的根治点 (原先预载只落磁盘字节, 解码仍压在翻页那一刻)。
 */
suspend fun preloadMangaImage(url: String, book: Book, source: BookSource?) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return
    preloadMangaPage(url, book, source)
}
