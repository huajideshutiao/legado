package io.legado.app.ui.book.manga.render

import coil3.PlatformContext
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.image.DecodedImageResult
import io.legado.app.help.image.iosCoilImageLoader
import io.legado.app.help.image.loadMangaPage
import io.legado.app.help.image.peekMangaPage
import io.legado.app.help.image.preloadMangaPage

internal actual fun peekMangaPage(url: String, source: BookSource?): DecodedImageResult? =
    iosCoilImageLoader.peekMangaPage(url, source)

internal actual suspend fun loadMangaPage(
    url: String,
    book: Book,
    source: BookSource?,
    skipMemoryCache: Boolean,
): DecodedImageResult? = iosCoilImageLoader.loadMangaPage(
    PlatformContext.INSTANCE, url, book, source, skipMemoryCache
)

internal actual suspend fun preloadMangaPage(url: String, book: Book, source: BookSource?) {
    iosCoilImageLoader.preloadMangaPage(PlatformContext.INSTANCE, url, book, source)
}
