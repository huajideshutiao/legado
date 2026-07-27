package io.legado.app.ui.book.manga

import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 鸿蒙端 MangaImageExtractor 实现。
 * 复用 [MangaImageExtractorShared.extractImageUrls] (纯字符串扫描, 无平台依赖)。
 *
 * 对照 desktop [io.legado.desktop.ui.book.manga.DesktopMangaImageExtractor]:
 * 鸿蒙端无 cbz/zip 本地漫画处理, 直接 emit 原始 src。
 */
class OhosMangaImageExtractor : MangaImageExtractor {
    override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> = flow {
        MangaImageExtractorShared.extractImageUrls(content).forEach { emit(it) }
    }.flowOn(Dispatchers.IO)
}
