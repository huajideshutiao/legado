package io.legado.app.ui.book.filter

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.AppCompatCheckBox
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.databinding.DialogSourceFilterEditBinding
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.search.SearchScopeDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.UUID

/**
 * 新增 / 编辑过滤规则。作用范围复用 [SearchScopeDialog]。
 */
class SourceFilterEditDialog() : BaseDialogFragment(R.layout.dialog_source_filter_edit),
    SearchScopeDialog.Callback {

    companion object {
        private const val ARG_EXISTING = "existing"
        private const val ARG_DEFAULT_SCOPE = "defaultScope"
    }

    constructor(existing: SourceFilterRule?) : this(existing, null)

    constructor(existing: SourceFilterRule?, defaultScope: String?) : this() {
        arguments = Bundle().apply {
            existing?.let { putParcelable(ARG_EXISTING, it) }
            defaultScope?.let { putString(ARG_DEFAULT_SCOPE, it) }
        }
    }

    private val binding by viewBinding(DialogSourceFilterEditBinding::bind)
    private val checkBoxes = linkedMapOf<SourceFilterRule.Field, AppCompatCheckBox>()
    private var scope: String = ""
    private var existing: SourceFilterRule? = null

    private val callback: Callback?
        get() = (parentFragment as? Callback) ?: (activity as? Callback)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        @Suppress("DEPRECATION")
        existing = arguments?.getParcelable(ARG_EXISTING)
        binding.toolBar.title = if (existing == null) {
            getString(R.string.add) + getString(R.string.source_filter_rule)
        } else {
            getString(R.string.source_filter_rule)
        }
        setupFields()
        setupInputs()
        binding.llPickScope.setOnClickListener {
            showDialogFragment<SearchScopeDialog>()
        }
        binding.tvCancel.setOnClickListener { dismiss() }
        binding.tvOk.setOnClickListener { onConfirm() }
        refreshScopeSummary()
    }

    private fun setupFields() {
        checkBoxes[SourceFilterRule.Field.NAME] = binding.cbFieldName
        checkBoxes[SourceFilterRule.Field.AUTHOR] = binding.cbFieldAuthor
        checkBoxes[SourceFilterRule.Field.INTRO] = binding.cbFieldIntro
        checkBoxes[SourceFilterRule.Field.KIND] = binding.cbFieldKind
        checkBoxes[SourceFilterRule.Field.WORD_COUNT] = binding.cbFieldWordCount
        val initialFields = existing
            ?.let { SourceFilterRule.parseFields(it.fields) }
            ?: SourceFilterRule.Field.entries.toHashSet()
        checkBoxes.forEach { (field, cb) -> cb.isChecked = field in initialFields }
    }

    private fun setupInputs() {
        existing?.let {
            binding.etName.setText(it.name)
            binding.etPattern.setText(it.pattern)
            scope = it.scope
        } ?: run {
            arguments?.getString(ARG_DEFAULT_SCOPE)?.let { scope = it }
        }
        binding.swtEnabled.isChecked = existing?.enabled ?: true
    }

    override fun onSearchScopeOk(searchScope: SearchScope) {
        scope = searchScope.toString()
        refreshScopeSummary()
    }

    private fun refreshScopeSummary() {
        binding.tvScopeSummary.text = if (scope.isEmpty()) {
            getString(R.string.source_filter_rule_scope_summary_all)
        } else {
            SearchScope(scope).display
        }
    }

    private fun onConfirm() {
        val ctx = requireContext()
        val pattern = binding.etPattern.text.toString().trim()
        if (pattern.isEmpty() || runCatching { Regex(pattern) }.isFailure) {
            ctx.toastOnUi(R.string.source_filter_rule_invalid_pattern)
            return
        }
        val pickedFields = checkBoxes.entries.filter { it.value.isChecked }.map { it.key }
        if (pickedFields.isEmpty()) {
            ctx.toastOnUi(R.string.source_filter_rule_no_field)
            return
        }
        val current = existing
        val rule = SourceFilterRule(
            id = current?.id ?: UUID.randomUUID().toString(),
            name = binding.etName.text.toString().trim(),
            enabled = binding.swtEnabled.isChecked,
            pattern = pattern,
            fields = SourceFilterRule.formatFields(pickedFields),
            scope = scope,
            order = current?.order ?: 0,
            createTime = current?.createTime ?: System.currentTimeMillis(),
        )
        callback?.onSourceFilterRuleSave(rule, current == null)
        dismiss()
    }

    interface Callback {
        fun onSourceFilterRuleSave(rule: SourceFilterRule, isNew: Boolean)
    }

}
