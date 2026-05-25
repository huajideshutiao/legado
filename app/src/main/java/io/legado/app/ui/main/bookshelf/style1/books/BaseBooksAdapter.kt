package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book

abstract class BaseBooksAdapter<VB : ViewBinding>(context: Context) :
    DiffRecyclerAdapter<Book, VB>(context) {

    override val keepScrollPosition = true

    override val diffItemCallback: DiffUtil.ItemCallback<Book> =
        object : DiffUtil.ItemCallback<Book>() {

            override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.name == newItem.name
                    && oldItem.author == newItem.author
            }

            override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean {
                return when {
                    oldItem.durChapterTime != newItem.durChapterTime -> false
                    oldItem.name != newItem.name -> false
                    oldItem.author != newItem.author -> false
                    oldItem.durChapterTitle != newItem.durChapterTitle -> false
                    oldItem.latestChapterTitle != newItem.latestChapterTitle -> false
                    oldItem.lastCheckCount != newItem.lastCheckCount -> false
                    oldItem.getDisplayCover() != newItem.getDisplayCover() -> false
                    oldItem.getUnreadChapterNum() != newItem.getUnreadChapterNum() -> false
                    else -> true
                }
            }

            override fun getChangePayload(oldItem: Book, newItem: Book): Any? {
                val bundle = bundleOf()
                if (oldItem.name != newItem.name) {
                    bundle.putString("name", newItem.name)
                }
                if (oldItem.author != newItem.author) {
                    bundle.putString("author", newItem.author)
                }
                if (oldItem.durChapterTitle != newItem.durChapterTitle) {
                    bundle.putString("dur", newItem.durChapterTitle)
                }
                if (oldItem.latestChapterTitle != newItem.latestChapterTitle) {
                    bundle.putString("last", newItem.latestChapterTitle)
                }
                if (oldItem.getDisplayCover() != newItem.getDisplayCover()) {
                    bundle.putString("cover", newItem.getDisplayCover())
                }
                if (oldItem.lastCheckCount != newItem.lastCheckCount
                    || oldItem.durChapterTime != newItem.durChapterTime
                    || oldItem.getUnreadChapterNum() != newItem.getUnreadChapterNum()
                    || oldItem.lastCheckCount != newItem.lastCheckCount
                ) {
                    bundle.putBoolean("refresh", true)
                }
                if (oldItem.latestChapterTime != newItem.latestChapterTime) {
                    bundle.putBoolean("lastUpdateTime", true)
                }
                if (bundle.isEmpty) return null
                return bundle
            }

        }

    override fun onViewRecycled(holder: ItemViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnLongClickListener(null)
    }

    /** bookUrl → position 索引, 仅主线程访问 (onCurrentListChanged 和 notification 都在主线程) */
    private val bookUrlToPosition = HashMap<String, Int>()

    override fun onCurrentListChanged() {
        bookUrlToPosition.clear()
        getItems().forEachIndexed { index, book ->
            bookUrlToPosition[book.bookUrl] = index
        }
    }

    fun notification(bookUrl: String) {
        val position = bookUrlToPosition[bookUrl] ?: return
        if (position !in 0 until itemCount) return
        notifyItemChanged(
            position,
            Bundle().apply {
                putString("refresh", null)
                putString("lastUpdateTime", null)
            }
        )
    }

    fun upLastUpdateTime() {
        notifyItemRangeChanged(0, itemCount, Bundle().apply {
            putString("lastUpdateTime", null)
        })
    }

    interface CallBack {
        fun open(book: Book)
        fun openBookInfo(book: Book)
        fun isUpdate(bookUrl: String): Boolean
    }
}
