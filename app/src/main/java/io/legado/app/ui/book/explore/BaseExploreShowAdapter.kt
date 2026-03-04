package io.legado.app.ui.book.explore

import android.content.Context
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.addType


abstract class BaseExploreShowAdapter<VB : ViewBinding>(context: Context, val callBack: CallBack) :
    RecyclerAdapter<SearchBook, VB>(context) {
    override fun registerListener(holder: ItemViewHolder, binding: VB) {
        val tmp = getItem(holder.layoutPosition)?.toBook()?.apply {
                if (bookUrl.contains("::")||!callBack.isInBookshelf(this))
                    addType(BookType.notShelf)
            } ?: return
        holder.itemView.setOnClickListener { callBack.showBookInfo(tmp)  }
        holder.itemView.setOnLongClickListener { callBack.showBookInfo(tmp, true); true }
    }
    interface CallBack {
        /**
         * 是否已经加入书架
         */
        fun isInBookshelf(book: BaseBook): Boolean

        fun showBookInfo(book: Book, action: Boolean = false)
    }
}