package io.legado.app.ui.main.home

import android.content.Context
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.HomeTab
import io.legado.app.databinding.ItemHomeManageBinding

class HomeTabManageAdapter(
    context: Context,
    private val callback: Callback
) : RecyclerAdapter<HomeTab, ItemHomeManageBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemHomeManageBinding {
        return ItemHomeManageBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemHomeManageBinding,
        item: HomeTab,
        payloads: MutableList<Any>
    ) {
        binding.run {
            tvTitle.text = item.title
            tvDesc.text = context.getString(R.string.home_tab_section_count, item.sections.size)
            tvEdit.setText(R.string.edit)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemHomeManageBinding) {
        binding.root.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let { callback.onOpenSections(it) }
        }
        binding.tvEdit.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let { callback.onEdit(it) }
        }
    }

    fun swap(from: Int, to: Int) {
        swapItem(from, to)
    }

    interface Callback {
        fun onOpenSections(tab: HomeTab)
        fun onEdit(tab: HomeTab)
    }
}
