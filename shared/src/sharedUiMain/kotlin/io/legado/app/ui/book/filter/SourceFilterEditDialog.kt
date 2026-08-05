package io.legado.app.ui.book.filter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.search.SearchScopeDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.enable
import legado.shared.generated.resources.ic_arrow_drop_down
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.source_filter_field_author
import legado.shared.generated.resources.source_filter_field_intro
import legado.shared.generated.resources.source_filter_field_kind
import legado.shared.generated.resources.source_filter_field_name
import legado.shared.generated.resources.source_filter_field_word_count
import legado.shared.generated.resources.source_filter_rule
import legado.shared.generated.resources.source_filter_rule_fields
import legado.shared.generated.resources.source_filter_rule_invalid_pattern
import legado.shared.generated.resources.source_filter_rule_name
import legado.shared.generated.resources.source_filter_rule_no_field
import legado.shared.generated.resources.source_filter_rule_pattern
import legado.shared.generated.resources.source_filter_rule_scope_label
import legado.shared.generated.resources.source_filter_rule_scope_summary_all
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewFilterRules

/** 五个作用字段与标签，顺序对齐 app 端 fieldLabels。 */
private val fieldLabels = listOf(
    SourceFilterRule.Field.NAME to Res.string.source_filter_field_name,
    SourceFilterRule.Field.AUTHOR to Res.string.source_filter_field_author,
    SourceFilterRule.Field.INTRO to Res.string.source_filter_field_intro,
    SourceFilterRule.Field.KIND to Res.string.source_filter_field_kind,
    SourceFilterRule.Field.WORD_COUNT to Res.string.source_filter_field_word_count,
)

/**
 * 书源屏蔽规则编辑对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.filter.SourceFilterEditDialog` (BaseComposeDialogFragment),
 * 去掉 Fragment / Bundle / appDb / toastOnUi 依赖, 改为纯 @Composable + 回调:
 * - 调用方传入 [rule] (null=新增, 对应 app 端到达端按 id 重查的结果), [onConfirm] / [onDismiss];
 * - 作用范围仍复用 [SearchScopeDialog] (已下沉), 与 app 端 showDialogFragment<SearchScopeDialog>() 等价。
 *
 * @param rule 待编辑规则 (null=新增)
 * @param defaultScope 新增时的默认作用范围, 对照 app 端 ARG_DEFAULT_SCOPE (仅规则列表弹窗传入)
 * @param onConfirm 校验通过后回调组装好的 SourceFilterRule
 * @param onDismiss 取消 / 关闭
 */
@Composable
fun SourceFilterEditDialog(
    rule: SourceFilterRule?,
    onConfirm: (SourceFilterRule) -> Unit,
    onDismiss: () -> Unit,
    defaultScope: String? = null,
) {
    val colors = AppTheme.colors
    // 所有字符串一次性在 @Composable 主体内取出, 避免 onClick 中误用 @Composable
    val titleText = if (rule == null) {
        stringResource(Res.string.add) + stringResource(Res.string.source_filter_rule)
    } else {
        stringResource(Res.string.source_filter_rule)
    }
    val nameLabel = stringResource(Res.string.source_filter_rule_name)
    val patternLabel = stringResource(Res.string.source_filter_rule_pattern)
    val fieldsLabel = stringResource(Res.string.source_filter_rule_fields)
    val scopeLabel = stringResource(Res.string.source_filter_rule_scope_label)
    val scopeSummaryAll = stringResource(Res.string.source_filter_rule_scope_summary_all)
    val enableText = stringResource(Res.string.enable)
    val cancelText = stringResource(Res.string.cancel)
    val okText = stringResource(Res.string.ok)
    val invalidPatternText = stringResource(Res.string.source_filter_rule_invalid_pattern)
    val noFieldText = stringResource(Res.string.source_filter_rule_no_field)

    var name by remember(rule) { mutableStateOf(rule?.name.orEmpty()) }
    var pattern by remember(rule) { mutableStateOf(rule?.pattern.orEmpty()) }
    var enabled by remember(rule) { mutableStateOf(rule?.enabled ?: true) }
    // 对照 app 端 onCreate: 编辑读原值, 新增默认全选字段 + 取 defaultScope
    var fields by remember(rule) {
        mutableStateOf(
            rule?.let { SourceFilterRule.parseFields(it.fields) }
                ?: SourceFilterRule.Field.entries.toSet()
        )
    }
    var scope by remember(rule) {
        mutableStateOf(rule?.scope ?: defaultScope.orEmpty())
    }
    var showScopeDialog by remember { mutableStateOf(false) }

    /**
     * 校验并保存, 与 app 端 onConfirm() 等价: pattern 非空 + 正则语法, 作用字段至少一项。
     */
    fun confirm() {
        val patternStr = pattern.trim()
        if (patternStr.isEmpty() || runCatching { Regex(patternStr) }.isFailure) {
            Toasters.get().toast(invalidPatternText)
            return
        }
        val pickedFields = fieldLabels.map { it.first }.filter { it in fields }
        if (pickedFields.isEmpty()) {
            Toasters.get().toast(noFieldText)
            return
        }
        val existing = rule
        val newRule = SourceFilterRule(
            id = existing?.id ?: SourceFilterRule().id, // 走实体默认 randomUUIDString
            name = name.trim(),
            enabled = enabled,
            pattern = patternStr,
            fields = SourceFilterRule.formatFields(pickedFields),
            scope = scope,
            order = existing?.order ?: 0,
            createTime = existing?.createTime ?: SourceFilterRule().createTime, // 走实体默认 systemCurrentTimeMillis
        )
        onConfirm(newRule)
        onDismiss()
    }

    Surface(
        shape = DesignTokens.dialogShape,
        color = colors.fillet,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = titleText,
                onBack = onDismiss,
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
                    label = nameLabel,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppOutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = patternLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = fieldsLabel,
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
                FieldChecks(
                    fields = fields,
                    onToggle = { field ->
                        fields = if (field in fields) fields - field else fields + field
                    },
                )
                ScopeRow(
                    scope = scope,
                    label = scopeLabel,
                    summaryAll = scopeSummaryAll,
                    onClick = { showScopeDialog = true },
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(DesignTokens.viewHeightXl)
                        .clickable { enabled = !enabled },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = enableText,
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
                    text = cancelText,
                    color = colors.secondaryText,
                ) { onDismiss() }
                Spacer(Modifier.width(4.dp))
                AppTextButton(
                    text = okText,
                    color = DesignTokens.arcoBlue6,
                ) { confirm() }
            }
        }
    }

    // 对照 app 端 showDialogFragment<SearchScopeDialog>() + onSearchScopeOk
    if (showScopeDialog) {
        SearchScopeDialog(
            onConfirm = { searchScope ->
                scope = searchScope.toString()
                showScopeDialog = false
            },
            onDismiss = { showScopeDialog = false },
        )
    }
}

/** 五个字段勾选平铺一行, 行高固定 40dp (M2 组件 48dp 触摸目标被行高钳制) */
@Composable
private fun FieldChecks(
    fields: Set<SourceFilterRule.Field>,
    onToggle: (SourceFilterRule.Field) -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.viewHeightLarge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        fieldLabels.forEach { (field, labelRes) ->
            Row(
                Modifier
                    .weight(1f)
                    .clickable { onToggle(field) },
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

/** 作用范围行, 点击弹 SearchScopeDialog; 空范围显示"全部书源"。 */
@Composable
private fun ScopeRow(
    scope: String,
    label: String,
    summaryAll: String,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = colors.secondaryText,
                fontSize = 13.sp,
            )
            Text(
                text = if (scope.isEmpty()) summaryAll else SearchScope(scope).display,
                color = colors.primaryText,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            painter = painterResource(Res.drawable.ic_arrow_drop_down),
            contentDescription = label,
            tint = colors.secondaryText,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(24.dp),
        )
    }
}

// ===== @Preview 合并自 androidMain 的 book/filter/SourceFilterRulePreviews.kt (SourceFilterEditDialog) =====

// ---- SourceFilterEditDialog ----

@Preview
@Composable
fun SourceFilterEditDialogPreview() = LegadoThemePreview {
    SourceFilterEditDialog(
        rule = previewFilterRules.first(),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SourceFilterEditDialogNewPreview() = LegadoThemePreview {
    SourceFilterEditDialog(
        rule = null,
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SourceFilterEditDialogDarkPreview() = LegadoThemePreview(dark = true) {
    SourceFilterEditDialog(
        rule = previewFilterRules.first(),
        onConfirm = {},
        onDismiss = {},
    )
}
