package io.legado.app.ui.book.filter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.databinding.DialogSourceFilterListBinding
import io.legado.app.databinding.Item1lineTextBinding
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.utils.applyTint
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 展示当前 scope 下命中（已启用且范围覆盖）的屏蔽规则。
 * 点击条目 → 弹出 [SourceFilterEditDialog] 编辑；底部"管理全部" → 跳 [SourceFilterRuleActivity]。
 */
class SourceFilterRuleListDialog() : BaseDialogFragment(R.layout.dialog_source_filter_list),
    SourceFilterEditDialog.Callback {

    companion object {
        private const val ARG_SCOPE = "scope"
    }

    constructor(scope: String?) : this() {
        arguments = Bundle().apply { putString(ARG_SCOPE, scope) }
    }

    private val binding by viewBinding(DialogSourceFilterListBinding::bind)
    private val adapter by lazy { RuleAdapter(requireContext()) }

    override val isFullHeight: Boolean = true

    private val scope: String?
        get() = arguments?.getString(ARG_SCOPE)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setTitle(R.string.source_filter_rule)
        binding.toolBar.inflateMenu(R.menu.dialog_add)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_add) {
                showDialogFragment(SourceFilterEditDialog(existing = null, defaultScope = scope))
            }
            true
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.tvClose.setOnClickListener { dismiss() }
        binding.tvManage.setOnClickListener {
            startActivity(Intent(requireContext(), SourceFilterRuleActivity::class.java))
            dismiss()
        }
        loadRules()
    }

    private fun loadRules() {
        lifecycleScope.launch {
            val rules = withContext(IO) { SearchBookFilter.rulesInScope(scope) }
            adapter.setItems(rules)
            binding.tvEmpty.isVisible = rules.isEmpty()
        }
    }

    override fun onSourceFilterRuleSave(rule: SourceFilterRule, isNew: Boolean) {
        lifecycleScope.launch {
            withContext(IO) { SearchBookFilter.save(rule, isNew) }
            loadRules()
        }
    }

    private inner class RuleAdapter(context: Context) :
        RecyclerAdapter<SourceFilterRule, Item1lineTextBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): Item1lineTextBinding {
            return Item1lineTextBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: Item1lineTextBinding,
            item: SourceFilterRule,
            payloads: MutableList<Any>
        ) {
            binding.textView.text = item.name.ifEmpty { item.pattern }
        }

        override fun registerListener(holder: ItemViewHolder, binding: Item1lineTextBinding) {
            binding.root.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    showDialogFragment(SourceFilterEditDialog(it))
                }
            }
        }
    }
}
