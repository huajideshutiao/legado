package io.legado.app.ui.book.explore

import androidx.core.view.isVisible
import io.legado.app.data.entities.BaseBook
import io.legado.app.databinding.ItemExploreVideoBinding
import io.legado.app.model.CoverRatio
import io.legado.app.utils.gone
import io.legado.app.utils.visible

/**
 * 视频卡片统一 bind: 发现/搜索/书架的 isVideo tier 共用同一份布局.
 * coverUrl 交给调用方: 发现/搜索走 SearchBook.coverUrl, 书架走 Book.getDisplayCover().
 * showBookshelfBadge=false 时永久隐藏 ivInBookshelf 徽标 (书架内所有 book 都已入库, 每条都亮就是噪音).
 */
fun ItemExploreVideoBinding.bindVideoCard(
    item: BaseBook,
    coverUrl: String?,
    isInBookshelf: Boolean,
    showBookshelfBadge: Boolean = true,
) {
    tvTitle.text = item.name
    tvAuthor.text = item.getRealAuthor()
    tvAuthor.isVisible = tvAuthor.text.isNotBlank()
    val kinds = item.getKindList()
    llKind.isVisible = kinds.isNotEmpty()
    if (kinds.isNotEmpty()) llKind.setLabels(kinds)
    ivInBookshelf.isVisible = showBookshelfBadge && isInBookshelf
    ivCover.coverRatio = CoverRatio.VIDEO
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
            inBookshelf = isInBookshelf,
        )
    }
}
