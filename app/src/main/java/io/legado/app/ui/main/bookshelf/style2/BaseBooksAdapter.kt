package io.legado.app.ui.main.bookshelf.style2

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup

abstract class BaseBooksAdapter<VH : RecyclerView.ViewHolder>(
    val context: Context,
    val callBack: CallBack
) : RecyclerView.Adapter<VH>() {

    protected val inflater: LayoutInflater = LayoutInflater.from(context)

    private val diffItemCallback = object : DiffUtil.ItemCallback<Any>() {

        override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
            return when {
                oldItem is Book && newItem is Book -> {
                    oldItem.name == newItem.name
                            && oldItem.author == newItem.author
                }

                oldItem is BookGroup && newItem is BookGroup -> {
                    oldItem.groupId == newItem.groupId
                }

                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean {
            return when {
                oldItem is Book && newItem is Book -> {
                    oldItem.durChapterTime == newItem.durChapterTime &&
                            oldItem.name == newItem.name &&
                            oldItem.author == newItem.author &&
                            oldItem.durChapterTitle == newItem.durChapterTitle &&
                            oldItem.latestChapterTitle == newItem.latestChapterTitle &&
                            oldItem.lastCheckCount == newItem.lastCheckCount &&
                            oldItem.getDisplayCover() == newItem.getDisplayCover() &&
                            oldItem.getUnreadChapterNum() == newItem.getUnreadChapterNum()
                }

                oldItem is BookGroup && newItem is BookGroup -> {
                    oldItem.groupName == newItem.groupName &&
                            oldItem.cover == newItem.cover
                }

                else -> false
            }
        }

        override fun getChangePayload(oldItem: Any, newItem: Any): Any? {
            val bundle = bundleOf()
            when {
                oldItem is Book && newItem is Book -> {
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
                }

                oldItem is BookGroup && newItem is BookGroup -> {
                    if (oldItem.groupName != newItem.groupName) {
                        bundle.putString("groupName", newItem.groupName)
                    }
                    if (oldItem.cover != newItem.cover) {
                        bundle.putString("cover", newItem.cover)
                    }
                }
            }
            if (bundle.isEmpty) return null
            return bundle
        }
    }

    /** bookUrl → position 索引, 仅主线程访问 (ListListener 和 notification 都在主线程) */
    private val bookUrlToPosition = HashMap<String, Int>()

    private val asyncListDiffer by lazy {
        AsyncListDiffer(this, diffItemCallback).apply {
            addListListener { _, current ->
                bookUrlToPosition.clear()
                current.forEachIndexed { index, item ->
                    if (item is Book) bookUrlToPosition[item.bookUrl] = index
                }
            }
        }
    }

    fun updateItems() {
        asyncListDiffer.submitList(callBack.getItems())
    }

    fun notification(bookUrl: String) {
        val position = bookUrlToPosition[bookUrl] ?: return
        if (position !in 0 until itemCount) return
        notifyItemChanged(position, Bundle().apply {
            putString("refresh", null)
        })
    }

    fun getItems() = asyncListDiffer.currentList

    fun getItem(position: Int) = getItems().getOrNull(position)

    override fun getItemCount(): Int {
        return getItems().size
    }

    override fun getItemViewType(position: Int): Int {
        if (getItem(position) is BookGroup) {
            return 1
        }
        return 0
    }

    final override fun onBindViewHolder(holder: VH, position: Int) {}


    interface CallBack {
        fun onItemClick(item: Any)
        fun onItemLongClick(item: Any)
        fun isUpdate(bookUrl: String): Boolean
        fun getItems(): List<Any>
    }
}