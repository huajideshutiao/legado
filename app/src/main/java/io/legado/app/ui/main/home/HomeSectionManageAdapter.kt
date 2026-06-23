package io.legado.app.ui.main.home

import android.content.Context
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.HomeSection
import io.legado.app.databinding.ItemHomeManageBinding

class HomeSectionManageAdapter(
    context: Context,
    private val callback: Callback
) : RecyclerAdapter<HomeSection, ItemHomeManageBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemHomeManageBinding {
        return ItemHomeManageBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemHomeManageBinding,
        item: HomeSection,
        payloads: MutableList<Any>
    ) {
        binding.run {
            tvTitle.text = item.title
            tvDesc.text = "${styleName(item.style)} · ${item.sourceName}"
            tvEdit.setText(R.string.delete)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemHomeManageBinding) {
        binding.root.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let { callback.onEdit(it) }
        }
        binding.tvEdit.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let { callback.onDelete(it) }
        }
    }

    private fun styleName(style: Int): String = context.getString(
        when (style) {
            HomeSection.STYLE_RANK_LIST -> R.string.home_style_rank_list
            HomeSection.STYLE_FOUR_ROW -> R.string.home_style_four_row
            HomeSection.STYLE_INFINITE_GRID -> R.string.home_style_infinite_grid
            else -> R.string.home_style_cover_row
        }
    )

    fun swap(from: Int, to: Int) {
        swapItem(from, to)
    }

    interface Callback {
        fun onEdit(section: HomeSection)
        fun onDelete(section: HomeSection)
    }
}
