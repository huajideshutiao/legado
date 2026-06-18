package io.legado.app.ui.book.searchContent

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.hexString


class SearchContentAdapter(context: Context, val callback: Callback) :
    RecyclerAdapter<SearchResult, SearchContentAdapter.ItemBinding>(context) {

    val textColor = context.getCompatColor(R.color.primaryText).hexString.substring(2)
    val accentColor = context.accentColor.hexString.substring(2)

    override fun getViewBinding(parent: ViewGroup): ItemBinding {
        val textView = TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val padding = 12.dpToPx()
            setPadding(padding, padding, padding, padding)
            val typedValue = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                typedValue,
                true
            )
            setBackgroundResource(typedValue.resourceId)
        }
        return ItemBinding(textView)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBinding,
        item: SearchResult,
        payloads: MutableList<Any>
    ) {
        binding.run {
            val isDur = callback.durChapterIndex() == item.chapterIndex
            if (payloads.isEmpty()) {
                tvSearchResult.text = item.getHtmlCompat(textColor, accentColor)
                tvSearchResult.paint.isFakeBoldText = isDur
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                if (it.query.isNotBlank()) callback.openSearchResult(it, holder.layoutPosition)
            }
        }
    }

    class ItemBinding(val tvSearchResult: TextView) : ViewBinding {
        override fun getRoot(): View = tvSearchResult
    }

    interface Callback {
        fun openSearchResult(searchResult: SearchResult, index: Int)
        fun durChapterIndex(): Int
    }
}
