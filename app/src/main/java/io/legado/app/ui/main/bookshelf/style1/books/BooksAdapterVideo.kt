package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemExploreVideoBinding
import io.legado.app.ui.book.explore.bindVideoCard
import splitties.views.onLongClick

/**
 * 书架 isVideo tier: 直接复用发现/搜索的 item_explore_video 卡片视觉,
 * 通过 [ItemExploreVideoBinding.bindVideoCard] 与 VideoExploreShowAdapter 共享 bind.
 * 书架内所有 book 都已入库, ivInBookshelf 徽标恒隐避免噪音.
 */
class BooksAdapterVideo(context: Context, private val callBack: CallBack) :
    BaseBooksAdapter<ItemExploreVideoBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemExploreVideoBinding {
        return ItemExploreVideoBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemExploreVideoBinding,
        item: Book,
        payloads: MutableList<Any>
    ) = binding.run {
        if (payloads.isEmpty()) {
            bindVideoCard(
                item,
                item.getDisplayCover(),
                isInBookshelf = true,
                showBookshelfBadge = false,
            )
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bundle.keySet().forEach {
                    when (it) {
                        "name", "author", "cover" -> bindVideoCard(
                            item,
                            item.getDisplayCover(),
                            isInBookshelf = true,
                            showBookshelfBadge = false,
                        )
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemExploreVideoBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let { callBack.open(it) }
            }
            onLongClick {
                getItem(holder.layoutPosition)?.let { callBack.openBookInfo(it) }
            }
        }
    }
}
