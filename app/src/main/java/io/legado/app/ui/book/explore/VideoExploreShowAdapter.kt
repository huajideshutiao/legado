package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.data.entities.BaseBook
import io.legado.app.databinding.ItemExploreVideoBinding

class VideoExploreShowAdapter(
    context: Context,
    callBack: CallBack,
    showBookshelfBadge: Boolean = true,
) : BaseExploreShowAdapter<ItemExploreVideoBinding>(context, callBack, showBookshelfBadge) {

    override fun getViewBinding(parent: ViewGroup): ItemExploreVideoBinding {
        return ItemExploreVideoBinding.inflate(inflater, parent, false)
    }

    override fun bind(binding: ItemExploreVideoBinding, item: BaseBook) {
        binding.bindVideoCard(
            item,
            item.coverUrl,
            callBack.isInBookshelf(item),
            showBookshelfBadge,
        )
    }

    override fun bindChange(binding: ItemExploreVideoBinding, item: BaseBook, bundle: Bundle) {
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
