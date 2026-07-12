package io.legado.app.ui.main.bookshelf

import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.setTextIfNotEqual
import io.legado.app.utils.toTimeAgo
import io.legado.app.utils.visible

/**
 * 16:9 视频封面横宽,列表里容易挤到别的列;按 3/4 倍高度收一下,
 * 视觉上宽度仍与 3:4 小说封面同量级.
 */
private const val VIDEO_HEIGHT_SCALE = 0.75f

fun ItemBookshelfListBinding.applyCoverHeight(isVideoStyle: Boolean) {
    val baseHeightDp = AppConfig.bookshelfCoverHeight
    val heightDp = if (isVideoStyle) (baseHeightDp * VIDEO_HEIGHT_SCALE).toInt() else baseHeightDp
    val coverHeight = heightDp.dpToPx()
    // 封面高度只随全局配置变化,绝大多数 bind 值未变;
    // 不判等就会每次 onBind 都触发一次无谓的 requestLayout
    if (ivCover.layoutParams.height != coverHeight) {
        ivCover.updateLayoutParams { height = coverHeight }
    }
}

fun ItemBookshelfListBinding.upKind(kinds: List<String>?, show: Boolean = true) {
    if (show && !kinds.isNullOrEmpty()) {
        llKind.visible()
        llKind.setLabels(kinds)
    } else {
        llKind.gone()
    }
}

fun ItemBookshelfListBinding.upLast(latestChapterTitle: String?, show: Boolean = true) {
    if (show && !latestChapterTitle.isNullOrEmpty()) {
        tvLast.text = latestChapterTitle
        ivLast.visible()
        tvLast.visible()
    } else {
        ivLast.gone()
        tvLast.gone()
    }
}

fun ItemBookshelfListBinding.upRefresh(item: Book, isUpdate: Boolean) {
    if (!item.isLocal && isUpdate) {
        bvUnread.invisible()
        rlLoading.visible()
    } else {
        rlLoading.gone()
        if (AppConfig.showUnread) {
            bvUnread.setHighlight(item.lastCheckCount > 0)
            bvUnread.setBadgeCount(item.getUnreadChapterNum())
        } else {
            bvUnread.invisible()
        }
    }
}

fun ItemBookshelfListBinding.upLastUpdateTime(item: Book) {
    if (AppConfig.showLastUpdateTime && !item.isLocal) {
        val time = item.latestChapterTime.toTimeAgo()
        tvLastUpdateTime.setTextIfNotEqual(time)
        tvLastUpdateTime.visible()
    } else {
        tvLastUpdateTime.gone()
    }
}

fun ItemBookshelfListBinding.upRead(durChapterTitle: String?, show: Boolean = true) {
    if (show && !durChapterTitle.isNullOrEmpty()) {
        tvRead.text = durChapterTitle
        ivRead.visible()
        tvRead.visible()
    } else {
        ivRead.gone()
        tvRead.gone()
    }
}

fun ItemBookshelfListBinding.upIntro(
    intro: CharSequence?,
    maxLines: Int = 3,
    show: Boolean = true,
) {
    if (show && !intro.isNullOrBlank()) {
        tvIntro.maxLines = maxLines
        tvIntro.text = intro
        tvIntro.visible()
    } else {
        tvIntro.gone()
    }
}

/**
 * 发现/搜索/书架三界面 List tier 共用主 bind: name/author/cover/last/inBookshelf.
 * tvRead(进度)、tvIntro、tvLastUpdateTime、flHasNew、ll_kind 由调用方按场景补齐:
 * 发现里 tvRead/tvLastUpdateTime 恒 gone, 书架里恒 visible; kind 显隐还受 AppConfig 控.
 */
fun ItemBookshelfListBinding.bindExploreCard(
    item: BaseBook,
    coverUrl: String?,
    isVideoStyle: Boolean,
    isInBookshelf: Boolean,
    showBookshelfBadge: Boolean = true,
    showOrigins: Boolean = false,
    loadCoverOnlyWifi: Boolean = AppConfig.loadCoverOnlyWifi,
) {
    applyCoverHeight(isVideoStyle)
    tvName.text = item.name
    tvAuthor.text = item.getRealAuthor()
    ivInBookshelf.isVisible = showBookshelfBadge && isInBookshelf
    if (showOrigins && item is SearchBook) {
        bvOriginCount.setBadgeCount(item.origins.size)
    } else {
        bvOriginCount.setBadgeCount(0)
    }
    upLast(item.latestChapterTitle)
    ivCover.coverRatio =
        if (isVideoStyle) BookCover.CoverRatio.VIDEO else BookCover.CoverRatio.NOVEL
    ivCover.load(
        coverUrl,
        item.name,
        item.author,
        loadCoverOnlyWifi,
        item.origin,
        inBookshelf = isInBookshelf
    )
}
