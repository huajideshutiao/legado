package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemExploreVideoBinding
import io.legado.app.utils.gone
import io.legado.app.utils.visible

class VideoExploreShowAdapter(context: Context, callBack: CallBack) :
    BaseExploreShowAdapter<ItemExploreVideoBinding>(context, callBack) {

    override fun getViewBinding(parent: ViewGroup): ItemExploreVideoBinding {
        return ItemExploreVideoBinding.inflate(inflater, parent, false)
    }

    override fun bind(binding: ItemExploreVideoBinding, item: SearchBook) {
        binding.run {
            tvTitle.text = item.name
            tvPubDate.text = item.wordCount
            tvPubDate.isVisible = !item.wordCount.isNullOrBlank()
            ivInBookshelf.isVisible = callBack.isInBookshelf(item)
            if (item.coverUrl.isNullOrBlank()) {
                ivCover.gone()
            } else {
                ivCover.visible()
                ivCover.load(
                    item.coverUrl,
                    item.name,
                    item.author,
                    false,
                    item.origin,
                    inBookshelf = callBack.isInBookshelf(item)
                )
            }
        }
    }

    override fun bindChange(binding: ItemExploreVideoBinding, item: SearchBook, bundle: Bundle) {
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "isInBookshelf" -> ivInBookshelf.isVisible = callBack.isInBookshelf(item)
                }
            }
        }
    }
}
