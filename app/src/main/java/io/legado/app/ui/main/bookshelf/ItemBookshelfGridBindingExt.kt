package io.legado.app.ui.main.bookshelf

import androidx.core.view.isVisible
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible

/**
 * 发现/搜索/书架三界面 Grid tier 共用主 bind: name/cover/inBookshelf.
 * 书架的未读徽标/刷新转圈由 [upRefresh] 单独处理.
 */
fun ItemBookshelfGridBinding.bindGridCard(
    item: BaseBook,
    coverUrl: String?,
    isInBookshelf: Boolean,
    showBookshelfBadge: Boolean = true,
) {
    ivCover.coverRatio = BookCover.CoverRatio.NOVEL
    tvName.text = item.name
    ivInBookshelf.isVisible = showBookshelfBadge && isInBookshelf
    if (coverUrl.isNullOrBlank()) {
        ivCover.gone()
    } else {
        ivCover.visible()
        ivCover.load(
            coverUrl,
            item.name,
            item.author,
            false,
            item.origin,
            inBookshelf = isInBookshelf
        )
    }
}

fun ItemBookshelfGridBinding.upRefresh(item: Book, isUpdate: Boolean) {
    if (!item.isLocal && isUpdate) {
        bvUnread.invisible()
        rlLoading.visible()
    } else {
        rlLoading.invisible()
        if (AppConfig.showUnread) {
            bvUnread.setBadgeCount(item.getUnreadChapterNum())
            bvUnread.setHighlight(item.lastCheckCount > 0)
        } else {
            bvUnread.invisible()
        }
    }
}
