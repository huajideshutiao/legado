package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.gone


class GridExploreShowAdapter(context: Context, callBack: CallBack) :
    BaseExploreShowAdapter<ItemBookshelfGridBinding>(context, callBack) {

    var isVideoStyle = false

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfGridBinding {
        return ItemBookshelfGridBinding.inflate(inflater, parent, false)
    }

    override fun bind(
        binding: ItemBookshelfGridBinding, item: SearchBook
    ) {
        binding.run {
            bvUnread.gone()
            rlLoading.gone()
            if (isVideoStyle) {
                ivCover.coverRatio = CoverImageView.CoverRatio.VIDEO
            } else {
                ivCover.coverRatio = CoverImageView.CoverRatio.NOVEL
            }
            tvName.text = item.name
            ivInBookshelf.isVisible = callBack.isInBookshelf(item)
            if (item.coverUrl.isNullOrBlank()) ivCover.gone()
            else ivCover.load(
                item.coverUrl,
                item.name,
                item.author,
                false,
                item.origin,
                inBookshelf = callBack.isInBookshelf(item)
            )
        }
    }


    override fun bindChange(binding: ItemBookshelfGridBinding, item: SearchBook, bundle: Bundle) {
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "isInBookshelf" -> ivInBookshelf.isVisible = callBack.isInBookshelf(item)
                }
            }
        }
    }

}