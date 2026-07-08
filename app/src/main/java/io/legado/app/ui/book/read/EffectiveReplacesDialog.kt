package io.legado.app.ui.book.read

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.DialogSourceFilterListBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeUtils
import io.legado.app.model.ReadBook
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 展示当前章节起效的替换规则；点击单条编辑，底部"管理全部"跳 [ReplaceRuleActivity]。
 * 复用 [R.layout.dialog_source_filter_list] 布局，承担原"净化"入口职责。
 */
class EffectiveReplacesDialog : BaseDialogFragment(R.layout.dialog_source_filter_list) {

    private val binding by viewBinding(DialogSourceFilterListBinding::bind)
    private val viewModel by activityViewModels<ReadBookViewModel>()
    private val adapter by lazy { ReplaceAdapter(requireContext()) }
    private val chineseConvert by lazy { ReplaceRule(0, "繁简转换") }

    override val isFullHeight: Boolean = true

    private var isEdit = false

    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == AppCompatActivity.RESULT_OK) {
                isEdit = true
            }
        }

    private val manageActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == AppCompatActivity.RESULT_OK) {
                isEdit = true
            }
        }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setupTitleBar(
            title = getString(R.string.effective_replaces),
            menuRes = R.menu.dialog_add
        ) {
            if (it?.itemId == R.id.menu_add) {
                val scope = listOfNotNull(
                    ReadBook.book?.name,
                    ReadBook.bookSource?.bookSourceUrl
                ).joinToString(";")
                editActivity.launch(
                    ReplaceEditActivity.startIntent(requireContext(), scope = scope)
                )
            }
            true
        }
        binding.tvEmpty.setText(R.string.empty)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.tvClose.setOnClickListener { dismiss() }
        binding.tvManage.setOnClickListener {
            manageActivity.launch(Intent(requireContext(), ReplaceRuleActivity::class.java))
        }
        val effectiveReplaceRules = ReadBook.curTextChapter?.effectiveReplaceRules ?: emptyList()
        val items = if (AppConfig.chineseConverterType > 0) {
            effectiveReplaceRules + chineseConvert
        } else {
            effectiveReplaceRules
        }
        adapter.setItems(items)
        binding.tvEmpty.isVisible = items.isEmpty()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (isEdit) {
            viewModel.replaceRuleChanged()
        }
    }

    private fun showChineseConvertAlert() {
        ChineseUtils.showConverterSelector(requireContext()) {
            isEdit = true
        }
    }

    class TextItemBinding(val root: TextView) : ViewBinding {
        override fun getRoot(): View = root
    }

    private inner class ReplaceAdapter(context: Context) :
        RecyclerAdapter<ReplaceRule, TextItemBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): TextItemBinding {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(
                    parent.context.resources.getDimensionPixelSize(R.dimen.arco_spacing_lg),
                    parent.context.resources.getDimensionPixelSize(R.dimen.arco_spacing_default),
                    parent.context.resources.getDimensionPixelSize(R.dimen.arco_spacing_lg),
                    parent.context.resources.getDimensionPixelSize(R.dimen.arco_spacing_default)
                )
                // 点击编辑替换规则,需有 ripple 反馈
                background = ThemeUtils.resolveDrawable(
                    parent.context, android.R.attr.selectableItemBackground
                )
                isSingleLine = true
                setTextColor(parent.context.getColor(R.color.primaryText))
            }
            return TextItemBinding(tv)
        }

        override fun registerListener(holder: ItemViewHolder, binding: TextItemBinding) {
            binding.root.setOnClickListener {
                getItem(holder.layoutPosition)?.let { item ->
                    if (item === chineseConvert) {
                        showChineseConvertAlert()
                        return@let
                    }
                    editActivity.launch(ReplaceEditActivity.startIntent(requireContext(), item.id))
                }
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: TextItemBinding,
            item: ReplaceRule,
            payloads: MutableList<Any>
        ) {
            binding.root.text = item.name
        }
    }
}
