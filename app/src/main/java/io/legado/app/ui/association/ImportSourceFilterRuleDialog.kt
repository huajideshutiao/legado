package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemSourceImportBinding
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.gone
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import splitties.views.onClick

class ImportSourceFilterRuleDialog() : BaseDialogFragment(R.layout.dialog_recycler_view),
    CodeDialog.Callback {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel by viewModels<ImportSourceFilterRuleViewModel>()
    private val adapter by lazy { SourcesAdapter(requireContext()) }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setTitle(R.string.import_source_filter_rule)
        binding.rotateLoading.visible()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.tvCancel.visible()
        binding.tvCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.tvOk.visible()
        binding.tvOk.setOnClickListener {
            val waitDialog = WaitDialog.from(requireActivity())
            waitDialog.show(requireActivity().supportFragmentManager)
            viewModel.importSelect {
                waitDialog.dismissSafe()
                dismissAllowingStateLoss()
            }
        }
        binding.tvFooterLeft.visible()
        binding.tvFooterLeft.setOnClickListener {
            val selectAll = viewModel.isSelectAll
            viewModel.selectStatus.forEachIndexed { index, b ->
                if (b != !selectAll) viewModel.selectStatus[index] = !selectAll
            }
            adapter.notifyDataSetChanged()
            upSelectText()
        }
        viewModel.errorLiveData.observe(this) {
            binding.rotateLoading.gone()
            binding.tvMsg.apply { text = it; visible() }
        }
        viewModel.successLiveData.observe(this) {
            binding.rotateLoading.gone()
            if (it > 0) {
                adapter.setItems(viewModel.allRules)
                upSelectText()
            } else {
                binding.tvMsg.apply { setText(R.string.wrong_format); visible() }
            }
        }
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismiss()
            return
        }
        viewModel.import(source)
    }

    private fun upSelectText() {
        val tpl = if (viewModel.isSelectAll) R.string.select_cancel_count
        else R.string.select_all_count
        binding.tvFooterLeft.text =
            getString(tpl, viewModel.selectCount, viewModel.allRules.size)
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toInt()?.let {
            GSON.fromJsonObject<SourceFilterRule>(code).getOrNull()?.let { rule ->
                viewModel.allRules[it] = rule
                adapter.setItem(it, rule)
            }
        }
    }

    inner class SourcesAdapter(context: Context) :
        RecyclerAdapter<SourceFilterRule, ItemSourceImportBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemSourceImportBinding {
            return ItemSourceImportBinding.inflate(inflater, parent, false)
        }

        @SuppressLint("SetTextI18n")
        override fun convert(
            holder: ItemViewHolder,
            binding: ItemSourceImportBinding,
            item: SourceFilterRule,
            payloads: MutableList<Any>,
        ) = binding.run {
            cbSourceName.isChecked = viewModel.selectStatus[holder.layoutPosition]
            cbSourceName.text = item.name.ifEmpty { item.pattern }
            val localRule = viewModel.checkRules[holder.layoutPosition]
            tvSourceState.text = when {
                localRule == null -> "新增"
                item.pattern != localRule.pattern
                    || item.fields != localRule.fields
                    || item.scope != localRule.scope -> "更新"

                else -> "已有"
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemSourceImportBinding) {
            binding.run {
                cbSourceName.setOnUserCheckedChangeListener { isChecked ->
                    viewModel.selectStatus[holder.layoutPosition] = isChecked
                    upSelectText()
                }
                root.onClick {
                    cbSourceName.isChecked = !cbSourceName.isChecked
                    viewModel.selectStatus[holder.layoutPosition] = cbSourceName.isChecked
                    upSelectText()
                }
                tvOpen.setOnClickListener {
                    val source = viewModel.allRules[holder.layoutPosition]
                    showDialogFragment(
                        CodeDialog(
                            GSON.toJson(source),
                            disableEdit = false,
                            requestId = holder.layoutPosition.toString()
                        )
                    )
                }
            }
        }
    }
}
