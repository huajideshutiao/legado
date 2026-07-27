package io.legado.app.ui.book.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CoverRatio
import io.legado.app.ui.main.bookshelf.ShelfCover

/**
 * 搜索页 Android 专属 Composable 桥接 (对照 `ExploreShowAndroidSlots.kt`)。
 *
 * shared 端 [SearchScreen] 的封面走 slot 注入, app 端在此包装 [ShelfCover]
 * (AndroidView + CoverImageView + Coil3) 还原原版封面 (含默认封面绘制 + 防盗链 + 仅 WiFi)。
 *
 * modifier 由 shared 端构造时已含尺寸/比例约束, 本文件只负责透传参数。
 */

/**
 * 搜索结果封面 slot (对照原 app SearchScreen 的 SearchListItem/SearchGridItem 内 ShelfCover)。
 *
 * 原版 coverPath 取 [SearchBook.coverUrl] (非 getDisplayCover, 搜索结果无 customCoverUrl),
 * loadOnlyWifi 走 [AppConfig.loadCoverOnlyWifi], reloadKey 恒 0。
 *
 * @param book 搜索结果书
 * @param modifier shared 端构造的 modifier (含尺寸约束)
 * @param isVideoCover true=VIDEO(16:9) 比例, false=NOVEL(3:4)
 * @param inBookshelf 是否在书架中 (影响 CoverImageView 的缓存策略)
 */
@Composable
fun SearchResultCover(
    book: SearchBook,
    modifier: Modifier,
    isVideoCover: Boolean,
    inBookshelf: Boolean,
) {
    ShelfCover(
        path = book.coverUrl,
        name = book.name,
        author = book.author,
        origin = book.origin,
        ratio = if (isVideoCover) CoverRatio.VIDEO else CoverRatio.NOVEL,
        reloadKey = 0,
        inBookshelf = inBookshelf,
        loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
        modifier = modifier,
    )
}

/**
 * 输入帮助区书架命中项封面 slot (对照原 app SearchScreen 的 ShelfHitArea 内 ShelfCover)。
 *
 * 原版书架命中项 coverPath 取 [Book.getDisplayCover] (含 customCoverUrl),
 * inBookshelf 恒 true, loadOnlyWifi 恒 false。
 *
 * @param book 书架书籍
 * @param modifier shared 端构造的 modifier (含尺寸约束)
 * @param isVideoCover true=VIDEO(16:9) 比例, false=NOVEL(3:4)
 */
@Composable
fun SearchShelfCover(
    book: Book,
    modifier: Modifier,
    isVideoCover: Boolean,
) {
    ShelfCover(
        path = book.getDisplayCover(),
        name = book.name,
        author = book.author,
        origin = book.origin,
        ratio = if (isVideoCover) CoverRatio.VIDEO else CoverRatio.NOVEL,
        reloadKey = 0,
        inBookshelf = true,
        loadOnlyWifi = false,
        modifier = modifier,
    )
}
