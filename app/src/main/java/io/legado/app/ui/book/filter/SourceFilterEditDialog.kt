package io.legado.app.ui.book.filter

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.search.SearchScopeDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * 新增 / 编辑过滤规则。作用范围复用 [SearchScopeDialog]。
 */
class SourceFilterEditDialog() : BaseComposeDialogFragment(),
    SearchScopeDialog.Callback {

    companion object {
        private const val ARG_EXISTING_ID = "existingId"
        private const val ARG_DEFAULT_SCOPE = "defaultScope"

        private val fieldLabels = listOf(
            SourceFilterRule.Field.NAME to R.string.source_filter_field_name,
            SourceFilterRule.Field.AUTHOR to R.string.source_filter_field_author,
            SourceFilterRule.Field.INTRO to R.string.source_filter_field_intro,
            SourceFilterRule.Field.KIND to R.string.source_filter_field_kind,
            SourceFilterRule.Field.WORD_COUNT to R.string.source_filter_field_word_count,
        )
    }

    constructor(existing: SourceFilterRule?) : this(existing, null)

    constructor(existing: SourceFilterRule?, defaultScope: String?) : this() {
        arguments = Bundle().apply {
            // 传主键到达端重查, 编辑期间进程死亡恢复读最新 DB 行
            existing?.let { putString(ARG_EXISTING_ID, it.id) }
            defaultScope?.let { putString(ARG_DEFAULT_SCOPE, it) }
        }
    }

    private var existing: SourceFilterRule? = null
    private var name by mutableStateOf("")
    private var pattern by mutableStateOf("")
    private var fields by mutableStateOf<Set<SourceFilterRule.Field>>(emptySet())
    private var scope by mutableStateOf("")
    private var enabled by mutableStateOf(true)

    private val callback: Callback?
        get() = (parentFragment as? Callback) ?: (activity as? Callback)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        existing = arguments?.getString(ARG_EXISTING_ID)?.let {
            runBlocking { appDb.sourceFilterRuleDao.get(it) }
        }
        existing?.let {
            name = it.name
            pattern = it.pattern
            fields = SourceFilterRule.parseFields(it.fields)
            scope = it.scope
            enabled = it.enabled
        } ?: run {
            fields = SourceFilterRule.Field.entries.toSet()
            arguments?.getString(ARG_DEFAULT_SCOPE)?.let { scope = it }
        }
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = if (existing == null) {
                    getString(R.string.add) + getString(R.string.source_filter_rule)
                } else {
                    stringResource(R.string.source_filter_rule)
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                AppOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.source_filter_rule_name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppOutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = stringResource(R.string.source_filter_rule_pattern),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.source_filter_rule_fields),
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
                FieldChecks()
                ScopeRow()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { enabled = !enabled },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.enable),
                        color = colors.primaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    AppSwitch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(
                    text = stringResource(R.string.cancel),
                    color = colors.secondaryText,
                ) { dismiss() }
                AppTextButton(text = stringResource(R.string.ok)) { onConfirm() }
            }
        }
    }

    /** 五个字段勾选平铺一行, 去掉 M3 48dp 触摸目标撑高(对齐原 40dp CheckBox 行) */
    @Composable
    private fun FieldChecks() {
        val colors = AppTheme.colors
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                fieldLabels.forEach { (field, labelRes) ->
                    Row(
                        Modifier
                            .weight(1f)
                            .clickable { toggleField(field) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppCheckbox(checked = field in fields, onCheckedChange = null)
                        Text(
                            text = stringResource(labelRes),
                            color = colors.primaryText,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ScopeRow() {
        val colors = AppTheme.colors
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 40.dp)
                .clickable { showDialogFragment<SearchScopeDialog>() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.source_filter_rule_scope_label),
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                )
                Text(
                    text = if (scope.isEmpty()) {
                        stringResource(R.string.source_filter_rule_scope_summary_all)
                    } else {
                        SearchScope(scope).display
                    },
                    color = colors.primaryText,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = stringResource(R.string.source_filter_rule_scope_label),
                tint = colors.secondaryText,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(24.dp),
            )
        }
    }

    private fun toggleField(field: SourceFilterRule.Field) {
        fields = if (field in fields) fields - field else fields + field
    }

    override fun onSearchScopeOk(searchScope: SearchScope) {
        scope = searchScope.toString()
    }

    private fun onConfirm() {
        val ctx = requireContext()
        val patternStr = pattern.trim()
        if (patternStr.isEmpty() || runCatching { Regex(patternStr) }.isFailure) {
            ctx.toastOnUi(R.string.source_filter_rule_invalid_pattern)
            return
        }
        val pickedFields = fieldLabels.map { it.first }.filter { it in fields }
        if (pickedFields.isEmpty()) {
            ctx.toastOnUi(R.string.source_filter_rule_no_field)
            return
        }
        val current = existing
        val rule = SourceFilterRule(
            id = current?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            enabled = enabled,
            pattern = patternStr,
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
