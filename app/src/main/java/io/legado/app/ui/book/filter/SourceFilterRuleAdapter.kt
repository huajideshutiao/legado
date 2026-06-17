package io.legado.app.ui.book.filter

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.databinding.ItemReplaceRuleBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.ColorUtils

class SourceFilterRuleAdapter(
    context: Context,
    private val callback: Callback,
) : RecyclerAdapter<SourceFilterRule, ItemReplaceRuleBinding>(context),
    ItemTouchCallback.Callback {

    private val selected = linkedSetOf<SourceFilterRule>()
    private val movedItems = linkedSetOf<SourceFilterRule>()

    val selection: List<SourceFilterRule>
        get() = getItems().filter { selected.contains(it) }

    val diffItemCallBack = object : DiffUtil.ItemCallback<SourceFilterRule>() {

        override fun areItemsTheSame(
            oldItem: SourceFilterRule,
            newItem: SourceFilterRule
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: SourceFilterRule,
            newItem: SourceFilterRule
        ): Boolean {
            return oldItem.name == newItem.name
                && oldItem.pattern == newItem.pattern
                && oldItem.enabled == newItem.enabled
        }

        override fun getChangePayload(oldItem: SourceFilterRule, newItem: SourceFilterRule): Any {
            val payload = Bundle()
            if (oldItem.name != newItem.name || oldItem.pattern != newItem.pattern) {
                payload.putBoolean("upName", true)
            }
            if (oldItem.enabled != newItem.enabled) {
                payload.putBoolean("enabled", newItem.enabled)
            }
            return payload
        }
    }

    fun selectAll() {
        getItems().forEach { selected.add(it) }
        notifyItemRangeChanged(0, itemCount, Bundle().apply { putString("selected", null) })
        callback.upCountView()
    }

    fun revertSelection() {
        getItems().forEach {
            if (selected.contains(it)) selected.remove(it) else selected.add(it)
        }
        notifyItemRangeChanged(0, itemCount, Bundle().apply { putString("selected", null) })
        callback.upCountView()
    }

    override fun getViewBinding(parent: ViewGroup): ItemReplaceRuleBinding {
        return ItemReplaceRuleBinding.inflate(inflater, parent, false)
    }

    override fun onCurrentListChanged() {
        callback.upCountView()
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemReplaceRuleBinding,
        item: SourceFilterRule,
        payloads: MutableList<Any>,
    ) = binding.run {
        if (payloads.isEmpty()) {
            root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
            cbName.text = displayName(item)
            cbName.isChecked = selected.contains(item)
            swtEnabled.isChecked = item.enabled
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bundle.keySet().forEach {
                    when (it) {
                        "selected" -> cbName.isChecked = selected.contains(item)
                        "upName" -> cbName.text = displayName(item)
                        "enabled" -> swtEnabled.isChecked = item.enabled
                    }
                }
            }
        }
    }

    private fun displayName(item: SourceFilterRule): String =
        item.name.ifEmpty { item.pattern }

    override fun registerListener(
        holder: ItemViewHolder,
        binding: ItemReplaceRuleBinding,
    ) {
        binding.apply {
            swtEnabled.setOnUserCheckedChangeListener { isChecked ->
                getItem(holder.layoutPosition)?.let {
                    it.enabled = isChecked
                    callback.update(it)
                }
            }
            ivEdit.setOnClickListener {
                getItem(holder.layoutPosition)?.let(callback::edit)
            }
            cbName.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    if (cbName.isChecked) selected.add(it) else selected.remove(it)
                }
                callback.upCountView()
            }
            ivMenuMore.setOnClickListener {
                showMenu(ivMenuMore, holder.layoutPosition)
            }
        }
    }

    private fun showMenu(view: View, position: Int) {
        val item = getItem(position) ?: return
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.replace_rule_item)
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_top -> callback.toTop(item)
                R.id.menu_bottom -> callback.toBottom(item)
                R.id.menu_del -> {
                    callback.delete(item)
                    selected.remove(item)
                }
            }
            true
        }
        popupMenu.show()
    }

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val srcItem = getItem(srcPosition)
        val targetItem = getItem(targetPosition)
        if (srcItem != null && targetItem != null) {
            if (srcItem.order == targetItem.order) {
                callback.upOrder()
            } else {
                val srcOrder = srcItem.order
                srcItem.order = targetItem.order
                targetItem.order = srcOrder
                movedItems.add(srcItem)
                movedItems.add(targetItem)
            }
        }
        swapItem(srcPosition, targetPosition)
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        if (movedItems.isNotEmpty()) {
            callback.update(*movedItems.toTypedArray())
            movedItems.clear()
        }
    }

    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<SourceFilterRule>(Mode.ToggleAndReverse) {
            override fun currentSelectedId(): MutableSet<SourceFilterRule> = selected

            override fun getItemId(position: Int): SourceFilterRule = getItem(position)!!

            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                getItem(position)?.let {
                    if (isSelected) selected.add(it) else selected.remove(it)
                    notifyItemChanged(position, Bundle().apply { putString("selected", null) })
                    callback.upCountView()
                    return true
                }
                return false
            }
        }

    interface Callback {
        fun update(vararg rule: SourceFilterRule)
        fun delete(rule: SourceFilterRule)
        fun edit(rule: SourceFilterRule)
        fun toTop(rule: SourceFilterRule)
        fun toBottom(rule: SourceFilterRule)
        fun upOrder()
        fun upCountView()
    }
}
