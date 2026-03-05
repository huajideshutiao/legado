package io.legado.app.ui.book.explore

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemBookshelfGridBinding


class GridExploreShowAdapter(context: Context, callBack: CallBack) :
    BaseExploreShowAdapter<ItemBookshelfGridBinding>(context, callBack) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfGridBinding {
        return ItemBookshelfGridBinding.inflate(inflater, parent, false)
    }

    @SuppressLint("CheckResult")
    override fun bind(
        binding: ItemBookshelfGridBinding,
        item: SearchBook
    ) {
        binding.run {
            tvName.text = item.name
            ivInBookshelf.isVisible = callBack.isInBookshelf(item)
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


        override fun bindChange(binding: ItemBookshelfGridBinding, item: SearchBook, bundle: Bundle) {
            binding.run {
                bundle.keySet().forEach {
                    when (it) {
                        "isInBookshelf" -> ivInBookshelf.isVisible =
                            callBack.isInBookshelf(item)
                    }
                }
            }
        }

    }