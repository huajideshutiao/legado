package io.legado.app.ui.book.manga

import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manga.render.MangaPageImageView
import kotlinx.coroutines.flow.Flow

object AndroidMangaReaderPlatform : MangaReaderScreenModel.Platform {
    override val config: MangaReaderConfig
        get() = MangaReaderConfig(
            hideMangaTitle = AppConfig.hideMangaTitle,
            preDownloadNum = AppConfig.mangaPreDownloadNum,
            syncBookProgressPlus = AppConfig.syncBookProgressPlus,
        )

    override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> =
        BookHelp.flowImages(bookChapter, content)

    @Composable
    override fun Image(
        url: String,
        modifier: Modifier,
        horizontal: Boolean,
        book: Book?,
        source: BookSource?,
    ) {
        AndroidView(
            factory = { MangaPageImageView(it) },
            modifier = modifier,
            onReset = { it.recycle() },
            onRelease = { it.recycle() },
            update = { view ->
                view.scaleType =
                    if (horizontal) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY
                view.loadPageImage(url, book, source, AppConfig.enableMangaGray)
            },
        )
    }
}
