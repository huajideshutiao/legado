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
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemManageRuleBinding
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.utils.setOnUserCheckedChangeListener
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showRuleItemMenu
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 展示当前 scope 下命中（已启用且范围覆盖）的屏蔽规则。
 * 点击条目 → 弹出 [SourceFilterEditDialog] 编辑；底部"管理全部" → 跳 [SourceFilterRuleActivity]。
 */
class SourceFilterRuleListDialog() : BaseDialogFragment(R.layout.dialog_recycler_view),
    SourceFilterEditDialog.Callback {

    companion object {
        private const val ARG_SCOPE = "scope"
    }

    constructor(scope: String?) : this() {
        arguments = Bundle().apply { putString(ARG_SCOPE, scope) }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { RuleAdapter(requireContext()) }

    override val isFullHeight: Boolean = true

    private val scope: String?
        get() = arguments?.getString(ARG_SCOPE)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setupTitleBar(
            title = getString(R.string.source_filter_rule),
            menuRes = R.menu.dialog_add
        ) {
            if (it?.itemId == R.id.menu_add) {
                showDialogFragment(SourceFilterEditDialog(existing = null, defaultScope = scope))
            }
            true
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.bottomLayout.visible()
        binding.tvCancel.visible()
        binding.tvCancel.text = getString(R.string.close)
        binding.tvCancel.setOnClickListener { dismiss() }
        binding.tvOk.visible()
        binding.tvOk.text = getString(R.string.source_filter_rule_manage)
        binding.tvOk.setOnClickListener {
            startActivity(Intent(requireContext(), SourceFilterRuleActivity::class.java))
            dismiss()
        }
        binding.tvEmpty.text = getString(R.string.source_filter_rule_no_match)
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
        RecyclerAdapter<SourceFilterRule, ItemManageRuleBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemManageRuleBinding {
            return ItemManageRuleBinding.inflate(inflater, parent, false).apply {
                // 对话框场景 cb_name 只用来展示名称，去掉勾选框视觉（一次性配置, 不在 convert 中重复设置）
                cbName.buttonDrawable = null
                cbName.isClickable = false
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemManageRuleBinding,
            item: SourceFilterRule,
            payloads: MutableList<Any>
        ) {
            binding.run {
                cbName.text = item.name.ifEmpty { item.pattern }
                swtEnabled.isChecked = item.enabled
                tvExtra.visibility = View.GONE
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemManageRuleBinding) {
            binding.apply {
                swtEnabled.setOnUserCheckedChangeListener { isChecked ->
                    getItem(holder.layoutPosition)?.let {
                        it.enabled = isChecked
                        lifecycleScope.launch(IO) { SearchBookFilter.save(it, isNew = false) }
                    }
                }
                // 只允许点击编辑按钮进入编辑界面
                ivEdit.setOnClickListener {
                    getItem(holder.layoutPosition)?.let {
                        showDialogFragment(SourceFilterEditDialog(it))
                    }
                }
                ivMenuMore.setOnClickListener {
                    showMenu(ivMenuMore, holder.layoutPosition)
                }
            }
        }

        private fun showMenu(view: View, position: Int) {
            val item = getItem(position) ?: return
            view.showRuleItemMenu {
                alert(R.string.draw) {
                    setMessage(getString(R.string.sure_del) + "\n" + item.name.ifEmpty { item.pattern })
                    noButton()
                    yesButton {
                        lifecycleScope.launch {
                            withContext(IO) { SearchBookFilter.delete(item) }
                            loadRules()
                        }
                    }
                }
            }
        }
    }
}
