package io.legado.app.ui.book.explore

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemExploreVideoBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CoverRatio
import io.legado.app.ui.main.bookshelf.ShelfCover
import io.legado.app.ui.widget.setUpExploreOptions

/**
 * 发现结果页 L3 (Android 专属) Composable 桥接实现。
 *
 * 下沉 shared 端 [ExploreShowScreen] 通过 slot 注入下列三类 Android-specific 渲染:
 * - [ExploreShowCover]: ShelfCover (AndroidView + CoverImageView + Glide + loadOnlyWifi)
 * - [ExploreOptionsRow]: AndroidView + LinearLayout + setUpExploreOptions (exploreOptions 桥接)
 * - [ExploreVideoItem]: AndroidView + ItemExploreVideoBinding (视频卡 ViewBinding)
 *
 * 对照 `BookInfoAndroidSlots.kt` 的 slot 桥接模式。
 */

/**
 * 封面渲染 slot: 包装 [ShelfCover] (AndroidView + CoverImageView + Glide)。
 *
 * 替代原 `ExploreListItem` / `ExploreGridItem` 内 ShelfCover 调用部分。
 * modifier 由 shared 端构造时已包含 height(coverHeight.dp) / fillMaxWidth().padding(...),
 * 本函数只负责把 (book, inBookshelf, isVideoStyle) 透传给 ShelfCover。
 *
 * @param book 当前搜索结果书
 * @param inBookshelf 是否在书架中 (绿点/书签徽标用)
 * @param isVideoStyle 是否视频风格封面 (true=VIDEO 比例, false=NOVEL 比例)
 * @param modifier shared 端构造的 modifier (含尺寸约束)
 */
@Composable
fun ExploreShowCover(
    book: SearchBook,
    inBookshelf: Boolean,
    isVideoStyle: Boolean,
    modifier: Modifier,
) {
    ShelfCover(
        path = book.coverUrl,
        name = book.name,
        author = book.author,
        origin = book.origin,
        ratio = if (isVideoStyle) CoverRatio.VIDEO else CoverRatio.NOVEL,
        reloadKey = 0,
        inBookshelf = inBookshelf,
        loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
        modifier = modifier,
    )
}

/**
 * 参数 chip 行 slot: 桥接 exploreOptions 的 LinearLayout + setUpExploreOptions。
 *
 * 替代原 `ExploreOptionsRow(activity)`, 内部读 `activity.optionsVersion` 触发重组重绑。
 * 由 `ExploreShowActivity.Content()` 的 optionsRowSlot lambda 调用本函数。
 *
 * @param activity 发现结果页 Activity (提供 viewModel.exploreOptions + onExploreOptionChanged)
 */
@Composable
fun ExploreOptionsRow(activity: ExploreShowActivity) {
    val version = activity.optionsVersion
    AndroidView(
        factory = { ctx ->
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        update = { container ->
            val key = version.toString()
            if (container.tag != key) {
                container.tag = key
                container.setUpExploreOptions(activity.viewModel.exploreOptions) {
                    activity.onExploreOptionChanged()
                }
            }
        },
    )
}

/**
 * 视频卡 slot: 桥接 ItemExploreVideoBinding ViewBinding。
 *
 * 替代原 `ExploreVideoItem(book, inBookshelf, onClick, onLongClick)`, 复用
 * [bindVideoCard] 统一视频卡 bind (发现/搜索/书架的 isVideo tier 共用同一份布局)。
 *
 * @param book 当前搜索结果书
 * @param inBookshelf 是否在书架中 (徽标显隐)
 * @param onClick 点击回调
 * @param onLongClick 长按回调
 */
@Composable
fun ExploreVideoItem(
    book: SearchBook,
    inBookshelf: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val holder = remember { VideoCardHolder() }
    AndroidView(
        factory = { ctx ->
            val b = ItemExploreVideoBinding.inflate(LayoutInflater.from(ctx))
            b.root.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            holder.binding = b
            b.root
        },
        modifier = Modifier.fillMaxWidth(),
        update = { root ->
            val b = holder.binding ?: return@AndroidView
            val key = "${book.bookUrl}|${book.coverUrl}|$inBookshelf"
            if (holder.key != key) {
                holder.key = key
                b.bindVideoCard(book, book.coverUrl, inBookshelf)
            }
            root.setOnClickListener { onClick() }
            root.setOnLongClickListener { onLongClick(); true }
        },
    )
}

/** 视频卡桥接 holder: 复用 item_explore_video (对齐 SearchScreen.SearchVideoItem) */
private class VideoCardHolder {
    var binding: ItemExploreVideoBinding? = null
    var key: String? = null
}
