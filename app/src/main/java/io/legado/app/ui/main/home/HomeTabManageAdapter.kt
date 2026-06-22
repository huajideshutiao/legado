package io.legado.app.ui.main.home

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.HomeTab
import io.legado.app.databinding.ItemHomeManageBinding
import java.util.Collections

class HomeTabManageAdapter(
    private val context: Context,
    private val callback: Callback
) : RecyclerView.Adapter<HomeTabManageAdapter.VH>() {

    private val inflater = LayoutInflater.from(context)
    private val items = mutableListOf<HomeTab>()

    fun setItems(list: List<HomeTab>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun getItems(): List<HomeTab> = items.toList()

    fun swap(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemHomeManageBinding.inflate(inflater, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val b: ItemHomeManageBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(tab: HomeTab) {
            b.tvTitle.text = tab.title
            b.tvDesc.text = context.getString(R.string.home_tab_section_count, tab.sections.size)
            b.tvEdit.setText(R.string.edit)
            b.root.setOnClickListener { callback.onOpenSections(tab) }
            b.tvEdit.setOnClickListener { callback.onEdit(tab) }
        }
    }

    interface Callback {
        fun onOpenSections(tab: HomeTab)
        fun onEdit(tab: HomeTab)
    }
}
