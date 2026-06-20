package io.legado.app.ui.main.bookshelf

import androidx.core.view.updateLayoutParams
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.setTextIfNotEqual
import io.legado.app.utils.toTimeAgo
import io.legado.app.utils.visible

fun ItemBookshelfListBinding.applyCoverWidth() {
    val coverWidth = AppConfig.bookshelfCoverWidth.dpToPx()
    // 封面宽度只随全局配置变化,绝大多数 bind 宽度未变;
    // 不判等就会每次 onBind 都触发一次无谓的 requestLayout
    if (ivCover.layoutParams.width != coverWidth) {
        ivCover.updateLayoutParams { width = coverWidth }
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
