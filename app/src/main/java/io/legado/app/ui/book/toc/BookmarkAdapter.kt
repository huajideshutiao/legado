package io.legado.app.ui.book.toc

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.utils.gone
import io.legado.app.utils.visible
import splitties.views.onLongClick

class BookmarkAdapter(context: Context, val callback: Callback) :
    RecyclerAdapter<Bookmark, BookmarkAdapter.BookmarkBinding>(context) {

    class BookmarkBinding(
        val root: LinearLayout,
        val tvChapterName: TextView,
        val tvBookText: TextView,
        val tvContent: TextView
    ) : ViewBinding {
        override fun getRoot(): View = root
    }

    override fun getViewBinding(parent: ViewGroup): BookmarkBinding {
        val ctx = parent.context
        val padding = parent.context.resources.getDimensionPixelSize(R.dimen.arco_spacing_default)
        val childPadding = parent.context.resources.getDimensionPixelSize(R.dimen.arco_spacing_xs)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, padding, 0, padding)
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        val tvChapterName = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(childPadding, childPadding, childPadding, childPadding)
            isSingleLine = true
        }
        val tvBookText = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(childPadding, childPadding, childPadding, childPadding)
            textSize = 12f
            isSingleLine = true
        }
        val tvContent = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(childPadding, childPadding, childPadding, childPadding)
            textSize = 12f
            isSingleLine = true
        }
        root.addView(tvChapterName)
        root.addView(tvBookText)
        root.addView(tvContent)
        return BookmarkBinding(root, tvChapterName, tvBookText, tvContent)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: BookmarkBinding,
        item: Bookmark,
        payloads: MutableList<Any>
    ) {
        binding.tvChapterName.text = item.chapterName
        if (item.bookText.isEmpty()) binding.tvBookText.gone() else binding.tvBookText.visible()
        binding.tvBookText.text = item.bookText
        if (item.content.isEmpty()) binding.tvContent.gone() else binding.tvContent.visible()
        binding.tvContent.text = item.content
    }

    override fun registerListener(holder: ItemViewHolder, binding: BookmarkBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let { bookmark ->
                callback.onClick(bookmark)
            }
        }
        holder.itemView.onLongClick {
            getItem(holder.layoutPosition)?.let { bookmark ->
                callback.onLongClick(bookmark, holder.layoutPosition)
            }
        }
    }

    interface Callback {
        fun onClick(bookmark: Bookmark)
        fun onLongClick(bookmark: Bookmark, pos: Int)
    }

}
