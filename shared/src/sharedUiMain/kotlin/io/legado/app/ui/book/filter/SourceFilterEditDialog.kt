package io.legado.app.ui.book.filter

// I18N KEYS (need to register in ResourceProvider.jvm.kt):
//   "source_filter_rule_edit_name" to "规则名",
//   "source_filter_rule_edit_pattern" to "规则",
//   "source_filter_rule_edit_invalid_pattern" to "正则无效或为空",
//   "source_filter_rule_edit_title_add" to "添加屏蔽规则",
//   "source_filter_rule_edit_title_edit" to "屏蔽规则"

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * Arco Design 主色 arcoblue-6 (#165DFF)。
 *
 * 用作对话框底部确认按钮的强调色。
 * 不复用 AppTheme.accent, 避免不同主题下颜色漂移导致与原 app 端 arco 规范不一致。
 */
private val ArcoBlue6 = Color(0xFF165DFF)

/**
 * 书源屏蔽规则编辑对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.filter.SourceFilterEditDialog` (BaseComposeDialogFragment),
 * 但去掉对 Android Fragment / Bundle / appDb / SearchScopeDialog / toastOnUi 的依赖,
 * 改为纯 @Composable + 回调形式:
 * - 调用方传入 [rule] (null=新增), [onConfirm] / [onDismiss] 回调。
 *
 * # 业务对齐 (对照 app 端原版)
 *
 * - 字段: 规则名 (name) / 规则 (pattern, 正则) / 是否启用 (enabled), 与任务规范一致;
 * - 标题: 新增 = "添加屏蔽规则", 编辑 = "屏蔽规则" (与 app 端 `getString(R.string.add) +
 *   getString(R.string.source_filter_rule)` / `stringResource(R.string.source_filter_rule)` 等价);
 * - 底部按钮栏: 取消 / 确定 (与 app 端 AppTextButton 一致, 取消左 + 确定右);
 * - 校验: pattern 非空 + 正则语法 (与 app 端 `runCatching { Regex(patternStr) }` 完全一致);
 * - 组装 SourceFilterRule: id/order/createTime 编辑时复用原值, 新增时由实体默认值生成
 *   (randomUUIDString / systemCurrentTimeMillis, 与 app 端 `UUID.randomUUID().toString()` /
 *   `System.currentTimeMillis()` 等价);
 * - fields 默认全选 (NAME/AUTHOR/INTRO/KIND/WORD_COUNT), 与 app 端新增分支
 *   `SourceFilterRule.Field.entries.toSet()` 等价 (任务字段简化版不暴露 fields 编辑,
 *   默认全选保留原业务对搜索/发现全字段过滤的语义);
 * - scope 默认空 (全部书源), 与 app 端新增分支 `scope = ""` 等价。
 *
 * # 与 app 端的差异
 *
 * - 不依赖 SearchScopeDialog (桌面端未下沉), scope 字段不可编辑, 默认空 (全部);
 * - 不暴露 fields 勾选 UI (任务字段简化版), 默认全选;
 * - 实际保存时 fields 写入 [SourceFilterRule.formatFields] (全选 5 字段);
 * - onConfirm 由调用方决定后续 DB 写入, 与 app 端 `callback?.onSourceFilterRuleSave(rule, isNew)` 等价。
 *
 * @param rule 待编辑规则 (null=新增)
 * @param onConfirm 用户点击确定按钮且校验通过, 参数为组装后的 SourceFilterRule
 * @param onDismiss 用户取消 (返回按钮 / 点击对话框外部 / 取消按钮)
 */
@Composable
fun SourceFilterEditDialog(
    rule: SourceFilterRule?,
    onConfirm: (SourceFilterRule) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    // 所有字符串一次性在 @Composable 主体内 rememberString, 避免 onClick 中误用 @Composable
    val titleText = if (rule == null) {
        rememberString("source_filter_rule_edit_title_add")
    } else {
        rememberString("source_filter_rule_edit_title_edit")
    }
    val nameLabel = rememberString("source_filter_rule_edit_name")
    val patternLabel = rememberString("source_filter_rule_edit_pattern")
    val enableText = rememberString("enable")
    val cancelText = rememberString("cancel")
    val okText = rememberString("ok")
    val invalidPatternText = rememberString("source_filter_rule_edit_invalid_pattern")

    var name by remember(rule) { mutableStateOf(rule?.name.orEmpty()) }
    var pattern by remember(rule) { mutableStateOf(rule?.pattern.orEmpty()) }
    var enabled by remember(rule) { mutableStateOf(rule?.enabled ?: true) }

    /**
     * 校验并保存, 与 app 端 onConfirm() 完全等价:
     * - pattern trim 后非空 + 正则语法校验 (runCatching { Regex(patternStr) });
     * - 失败 toast "正则无效或为空" (替代 `ctx.toastOnUi(R.string.source_filter_rule_invalid_pattern)`);
     * - 通过则组装 SourceFilterRule 并回调 [onConfirm] + [onDismiss]。
     *
     * fields 默认全选 (NAME/AUTHOR/INTRO/KIND/WORD_COUNT), 与 app 端新增分支
     * `SourceFilterRule.Field.entries.toSet()` 等价; 编辑分支沿用原 fields (保留原业务)。
     */
    fun confirm() {
        val patternStr = pattern.trim()
        if (patternStr.isEmpty() || runCatching { Regex(patternStr) }.isFailure) {
            Toasters.get().toast(invalidPatternText)
            return
        }
        val existing = rule
        // fields: 新增默认全选, 编辑沿用原值 (与 app 端 onCreate 分支一致)
        val fieldsStr = if (existing == null) {
            SourceFilterRule.formatFields(SourceFilterRule.Field.entries.toList())
        } else {
            existing.fields
        }
        val newRule = SourceFilterRule(
            id = existing?.id ?: SourceFilterRule().id, // 走实体默认 randomUUIDString
            name = name.trim(),
            enabled = enabled,
            pattern = patternStr,
            fields = fieldsStr,
            scope = existing?.scope ?: "",
            order = existing?.order ?: 0,
            createTime = existing?.createTime ?: SourceFilterRule().createTime, // 走实体默认 systemCurrentTimeMillis
        )
        onConfirm(newRule)
        onDismiss()
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.background,
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
                Spacer(Modifier.heightIn(min = 8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
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
                    color = ArcoBlue6,
                ) { confirm() }
            }
        }
    }
}
