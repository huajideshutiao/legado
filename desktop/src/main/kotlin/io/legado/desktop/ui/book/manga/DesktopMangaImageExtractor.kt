package io.legado.desktop.ui.book.manga

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.IntentData
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.ui.book.manga.MangaImageExtractor
import io.legado.app.ui.book.manga.MangaImageExtractorShared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Desktop 平台漫画图片提取器 (actual 实现 [MangaImageExtractor])。
 *
 * 对照 app 端 BookHelp.flowImages: 桌面端用 [MangaImageExtractorShared.extractImageUrls] 解析 `<img src="...">`。
 * 本地 cbz/zip 漫画的图片 src 是 zip entry name, 加 `cbz://` 前缀由 loadMangaImage 识别走 CbzFile。
 * cbz 判定经 [IntentData.book] 取当前书 (shared VM initData 前由 Screen 写入)。
 */
class DesktopMangaImageExtractor : MangaImageExtractor {

    override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> = flow {
        val isCbz = (IntentData.book as? Book)?.isLocalCbz() ?: false
        MangaImageExtractorShared.extractImageUrls(content).forEach { src ->
            emit(if (isCbz) "cbz://$src" else src)
        }
    }.flowOn(Dispatchers.IO)

    private fun Book.isLocalCbz(): Boolean =
        isLocal && (originName.endsWith(".cbz", true) ||
                originName.endsWith(".zip", true) && isImage)
}
