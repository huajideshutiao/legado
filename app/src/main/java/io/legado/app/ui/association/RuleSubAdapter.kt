package io.legado.app.ui.association

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RuleSub
import io.legado.app.databinding.ItemRuleSubBinding
import io.legado.app.ui.widget.recycler.ItemTouchCallback

class RuleSubAdapter(context: Context, private val callBack: CallBack) :
    RecyclerAdapter<RuleSub, ItemRuleSubBinding>(context),
    ItemTouchCallback.Callback {

    override fun getViewBinding(parent: ViewGroup): ItemRuleSubBinding {
        return ItemRuleSubBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRuleSubBinding,
        item: RuleSub,
        payloads: MutableList<Any>
    ) {
        binding.run {
            tvName.text = item.name
            tvUrl.text = item.url
            tvType.text = typeName(item.type)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRuleSubBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.openSubscription(it)
            }
        }
        binding.ivEdit.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.edit(it)
            }
        }
        binding.ivMenuMore.setOnClickListener { v ->
            showMenu(v, holder.layoutPosition)
        }
    }

    private fun typeName(type: Int): String {
        return when (type) {
            1 -> context.getString(R.string.rss_source)
            2 -> context.getString(R.string.replace_rule)
            3 -> context.getString(R.string.txt_toc_rule)
            4 -> context.getString(R.string.dict_rule)
            5 -> context.getString(R.string.tts)
            else -> context.getString(R.string.book_source)
        }
    }

    private fun showMenu(view: View, position: Int) {
        val ruleSub = getItem(position) ?: return
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.rule_sub_item)
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_top -> callBack.toTop(ruleSub)
                R.id.menu_bottom -> callBack.toBottom(ruleSub)
                R.id.menu_del -> callBack.delete(ruleSub)
            }
            true
        }
        popupMenu.show()
    }

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        swapItem(srcPosition, targetPosition)
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        callBack.upOrder(getItems())
    }

    interface CallBack {
        fun openSubscription(ruleSub: RuleSub)
        fun edit(ruleSub: RuleSub)
        fun delete(ruleSub: RuleSub)
        fun toTop(ruleSub: RuleSub)
        fun toBottom(ruleSub: RuleSub)
        fun upOrder(items: List<RuleSub>)
    }

}
