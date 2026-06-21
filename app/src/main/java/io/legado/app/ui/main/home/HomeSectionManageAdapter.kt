package io.legado.app.ui.main.home

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.HomeSection
import io.legado.app.databinding.ItemHomeSectionManageBinding
import java.util.Collections

class HomeSectionManageAdapter(
    private val context: Context,
    private val callback: Callback
) : RecyclerView.Adapter<HomeSectionManageAdapter.VH>() {

    private val inflater = LayoutInflater.from(context)
    private val items = mutableListOf<HomeSection>()

    fun setItems(list: List<HomeSection>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun getItems(): List<HomeSection> = items.toList()

    /** 拖动相邻交换，同时同步内存顺序 */
    fun swap(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemHomeSectionManageBinding.inflate(inflater, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    private fun styleName(style: Int): String = context.getString(
        when (style) {
            HomeSection.STYLE_RANK_LIST -> R.string.home_style_rank_list
            HomeSection.STYLE_FOUR_ROW -> R.string.home_style_four_row
            HomeSection.STYLE_INFINITE_GRID -> R.string.home_style_infinite_grid
            else -> R.string.home_style_cover_row
        }
    )

    inner class VH(private val b: ItemHomeSectionManageBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(section: HomeSection) {
            b.tvTitle.text = section.title
            b.tvDesc.text = context.getString(
                R.string.home_manage_item_desc,
                styleName(section.style),
                section.sourceName
            )
            b.root.setOnClickListener { callback.onEdit(section) }
            b.tvDelete.setOnClickListener { callback.onDelete(section) }
        }
    }

    interface Callback {
        fun onEdit(section: HomeSection)
        fun onDelete(section: HomeSection)
    }
}
