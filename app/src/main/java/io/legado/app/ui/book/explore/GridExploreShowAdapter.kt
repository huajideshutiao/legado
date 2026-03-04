package io.legado.app.ui.book.explore

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemBookshelfGridBinding


class GridExploreShowAdapter(context: Context, callBack: CallBack) :
    BaseExploreShowAdapter<ItemBookshelfGridBinding>(context, callBack) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfGridBinding {
        return ItemBookshelfGridBinding.inflate(inflater, parent, false)
    }

    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfGridBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        binding.run {
            tvName.text = item.name
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