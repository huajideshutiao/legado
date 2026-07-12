package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.data.entities.BaseBook
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.ui.main.bookshelf.bindGridCard
import io.legado.app.utils.gone


class GridExploreShowAdapter(
    context: Context,
    callBack: CallBack,
    showBookshelfBadge: Boolean = true,
) : BaseExploreShowAdapter<ItemBookshelfGridBinding>(context, callBack, showBookshelfBadge) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfGridBinding {
        return ItemBookshelfGridBinding.inflate(inflater, parent, false)
    }

    override fun bind(binding: ItemBookshelfGridBinding, item: BaseBook) {
        binding.run {
            bvUnread.gone()
            rlLoading.gone()
            bindGridCard(item, item.coverUrl, callBack.isInBookshelf(item), showBookshelfBadge)
        }
    }

    override fun bindChange(binding: ItemBookshelfGridBinding, item: BaseBook, bundle: Bundle) {
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "isInBookshelf" -> ivInBookshelf.isVisible =
                        showBookshelfBadge && callBack.isInBookshelf(item)
                }
            }
        }
    }
}
