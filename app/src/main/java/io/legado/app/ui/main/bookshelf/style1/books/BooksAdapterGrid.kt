package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.ui.main.bookshelf.bindGridCard
import io.legado.app.ui.main.bookshelf.upRefresh
import splitties.views.onLongClick

class BooksAdapterGrid(context: Context, private val callBack: CallBack) :
    BaseBooksAdapter<ItemBookshelfGridBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfGridBinding {
        return ItemBookshelfGridBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfGridBinding,
        item: Book,
        payloads: MutableList<Any>
    ) = binding.run {
        if (payloads.isEmpty()) {
            bindGridCard(
                item = item,
                coverUrl = item.getDisplayCover(),
                isInBookshelf = true,
                showBookshelfBadge = false,
            )
            upRefresh(item, callBack.isUpdate(item.bookUrl))
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bundle.keySet().forEach {
                    when (it) {
                        "name" -> tvName.text = item.name
                        "cover" -> ivCover.load(
                            item.getDisplayCover(),
                            item.name,
                            item.author,
                            false,
                            item.origin,
                            inBookshelf = true
                        )

                        "refresh" -> upRefresh(item, callBack.isUpdate(item.bookUrl))
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfGridBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.open(it)
                }
            }

            onLongClick {
                getItem(holder.layoutPosition)?.let {
                    callBack.openBookInfo(it)
                }
            }
        }
    }
}
