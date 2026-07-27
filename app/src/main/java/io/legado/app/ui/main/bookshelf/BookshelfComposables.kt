package io.legado.app.ui.main.bookshelf

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.model.CoverRatio
import io.legado.app.ui.widget.image.CoverImageView

/*
 * 书架 Composable app 端保留区。
 *
 * 原文件中的跨端共享组件 (ShelfTier / ShelfLayoutSpec / ShelfScrollState /
 * rememberShelfLayoutSpec / ShelfBooksContent / BookshelfTopBar / BookshelfActions /
 * UnreadBadge / KindLabels / ShelfListItem / ShelfGridItem / ShelfVideoItem /
 * GroupListItem / GroupGridItem / GroupVideoItem / LaunchedEffectResumedTick)
 * 已下沉到 shared 模块:
 *   modules/shared/src/sharedUiMain/kotlin/io/legado/app/ui/bookshelf/BookshelfComposablesShared.kt
 * (包名 io.legado.app.ui.bookshelf, 与本文件 io.legado.app.ui.main.bookshelf 不同)。
 *
 * shared 版本改动:
 * - 资源访问: stringResource(R.string.xxx) → rememberString("xxx");
 *   painterResource(R.drawable.xxx) → rememberPainter("xxx")
 * - Android API: LocalContext/LocalConfiguration/AppConfig/ThemeConfig/ColorUtils →
 *   provider 间接访问 + 回调注入 + 内联实现
 * - 封面: AndroidView + CoverImageView (Glide) → coverSlot: @Composable (Book) -> Unit
 *   参数注入, app 端调用 shared 版条目时用本文件 ShelfCover 包装注入
 * - Lifecycle: repeatOnLifecycle(RESUMED) → LaunchedEffect + while(true) + delay
 *   (shared 不依赖 androidx.lifecycle)
 *
 * 本文件仅保留 ShelfCover (AndroidView + CoverImageView + Glide, Android 专属,
 * 被 GroupEditDialog/ReadRecordActivity/SearchScreen/BookInfoScreen/BookInfoEditActivity/
 * ExploreShowScreen/HomeSectionComposables 引用, 不下沉)。
 *
 * app 端调用 shared 版 ShelfListItem/ShelfGridItem 等条目时, 通过 coverSlot 参数
 * 包装 ShelfCover 注入, 例如:
 * ```
 * ShelfBooksContent(
 *     ...,
 *     bookCoverSlot = { book ->
 *         ShelfCover(
 *             path = book.getDisplayCover(),
 *             name = book.name,
 *             author = book.author,
 *             origin = book.origin,
 *             ratio = CoverRatio.NOVEL,
 *             reloadKey = configTick,
 *             modifier = Modifier.fillMaxWidth(),
 *         )
 *     },
 *     groupCoverSlot = { group ->
 *         ShelfCover(
 *             path = group.cover,
 *             name = null, author = null, origin = null,
 *             ratio = CoverRatio.NOVEL,
 *             reloadKey = configTick,
 *             modifier = Modifier.fillMaxWidth(),
 *         )
 *     },
 * )
 * ```
 */

/**
 * 封面沿用 View 版 CoverImageView(附录 D 欠账), update 块用 tag 判等防止无关重组触发 Glide 重载;
 * reloadKey 变化(BOOKSHELF_REFRESH)时强制重载以应用封面配置变更.
 */
@Composable
fun ShelfCover(
    path: String?,
    name: String?,
    author: String?,
    origin: String?,
    ratio: CoverRatio,
    reloadKey: Int,
    modifier: Modifier = Modifier,
    inBookshelf: Boolean = true,
    loadOnlyWifi: Boolean = false,
) {
    AndroidView(
        factory = { CoverImageView(it) },
        modifier = modifier,
        update = { iv ->
            // inBookshelf 不参与 key: 对齐原 bindChange, 书架态翻转不重载封面
            val key = "$reloadKey|$ratio|$path|$name|$author|$origin"
            if (iv.tag != key) {
                iv.tag = key
                iv.coverRatio = ratio
                iv.load(path, name, author, loadOnlyWifi, origin, inBookshelf = inBookshelf)
            }
        },
    )
}
