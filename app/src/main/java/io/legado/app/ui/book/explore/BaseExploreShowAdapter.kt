package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BaseBook
import io.legado.app.help.book.addType


abstract class BaseExploreShowAdapter<VB : ViewBinding>(
    context: Context,
    val callBack: CallBack,
    val showBookshelfBadge: Boolean = true,
) : RecyclerAdapter<BaseBook, VB>(context) {
    override fun registerListener(holder: ItemViewHolder, binding: VB) {
        val tmp = getItemByLayoutPosition(holder.layoutPosition)?.apply {
            if (!callBack.isInBookshelf(this))
                    addType(BookType.notShelf)
            } ?: return
        holder.itemView.setOnClickListener { callBack.showBookInfo(tmp)  }
        holder.itemView.setOnLongClickListener { callBack.showBookInfo(tmp, true); true }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: VB,
        item: BaseBook,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            bind(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bindChange(binding, item, bundle)
            }
        }

    }

    abstract fun bind(binding: VB, item: BaseBook)
    abstract fun bindChange(binding: VB, item: BaseBook, bundle: Bundle)
    interface CallBack {
        /**
         * 是否已经加入书架
         */
        fun isInBookshelf(book: BaseBook): Boolean

        fun showBookInfo(book: BaseBook, longClick: Boolean = false)
    }
}
