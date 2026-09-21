package io.legado.app.ui.book.manga.render

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.image.mangaPageCacheKey
import io.legado.app.model.manga.MangaModel

/**
 * 预载一页漫画 (app 端 `AndroidMangaReaderPlatform.preloadImage` 的实现体)。
 *
 * 与显示端 [MangaCoilImage] 同 key 同参：显式 [mangaPageCacheKey] + Size.ORIGINAL +
 * WRITE_ONLY 只写内存缓存——翻到该页时显示请求按同一 key 同步 peek/execute 直接命中，
 * 不再现场解码 (原实现未显式指定 key 且显示请求带采样 size/灰度 transformations，
 * 会因 coil3 isCacheValueValidForSize / extras 追加 key 两条规则 miss)。
 */
suspend fun preloadMangaImageAndroid(
    context: Context,
    url: String,
    book: Book,
    source: BookSource?,
) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return
    val request = ImageRequest.Builder(context)
        .data(MangaModel(url, book, source))
        .memoryCacheKey(mangaPageCacheKey(url, source))
        .memoryCachePolicy(CachePolicy.WRITE_ONLY)
        .diskCachePolicy(CachePolicy.DISABLED)
        .size(Size.ORIGINAL)
        .build()
    SingletonImageLoader.get(context).execute(request)
}
