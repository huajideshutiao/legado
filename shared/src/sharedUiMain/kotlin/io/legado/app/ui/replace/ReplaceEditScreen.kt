package io.legado.app.ui.replace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.replace.edit.ReplaceEditViewModelShared

/**
 * 替换规则编辑 Screen (KMP 版, commonMain 共享)。
 *
 * 对照 app 端 `ReplaceEditActivity.Content` 下沉, 做以下简化以消除 Android 依赖:
 * - **去掉 KeyboardToolbar**: app 端 `io.legado.app.ui.widget.keyboard.KeyboardToolbar`
 *   及 `insertAtCursor`/`KeyboardAssistsConfig` 未下沉, 强 Android 依赖; KMP 版用回调
 *   [onHelp] 暴露帮助入口, 宿主端可自行实现辅助键工具栏并接入 (app 端后续包装时回填)
 * - **剪贴板用回调**: [onCopyRule] / [onPasteRule] 由宿主处理 (app 端 sendToClip/getClipText,
 *   desktop 端 java.awt.datatransfer.Clipboard), VM 解析 JSON 后回调回填表单
 * - **去掉 WindowInsets.ime/navigationBars**: Android 专属 inset, desktop 无 IME insets 概念,
 *   宿主端如需 IME 适配可在包装层加 windowInsetsPadding
 * - **路由用回调**: [onBack] / [onSaved] 替代 Activity setResult/finish
 *
 * # VM 接入 (ReplaceEditViewModelShared)
 *
 * 接收 [ReplaceEditViewModelShared] (commonMain 共享核心), 与旧版 commonMain
 * `ReplaceEditViewModel` 区别:
 * - 共享核心走组合委托模式, scope + clipTextProvider 由宿主注入;
 * - save/pasteRule 内部已通过 `Toasters.get().toast(...)` 处理错误 (替代旧版 error 回调),
 *   故本 Screen 不再需要 `onShowError` 回调 (旧版接口已移除);
 * - `state` StateFlow 接口保留 (旧版 VM 同名接口), 用 [collectAsState] 订阅触发回填。
 *
 * 保留核心表单逻辑: [FieldState] (撤销/重做历史, 去 insertAtCursor) / [FormField] /
 * [UseRegexRow] / [ScopeCheckRow], 对齐 app 端字段语义与校验。
 *
 * @param onCopyRule 复制规则到剪贴板 (宿主端序列化 ReplaceRule 为 JSON 写剪贴板)
 * @param onPasteRule 粘贴规则: 宿主端在 lambda 内读剪贴板文本, 调
 *   [ReplaceEditViewModelShared.pasteRule], 解析成功后回调 success 回填表单;
 *   解析失败由 Shared 内部 `Toasters.get().toast(...)` 提示, 无需外部 error 回调
 * @param onHelp 正则帮助回调 (app 端 showHelp("regexHelp") / desktop 端浏览器跳转)
 */
@Composable
fun ReplaceEditScreen(
    viewModel: ReplaceEditViewModelShared,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onCopyRule: (ReplaceRule) -> Unit,
    onPasteRule: (success: (ReplaceRule) -> Unit) -> Unit,
    onHelp: () -> Unit,
) {
    val rule by viewModel.state.collectAsState()
    // 表单字段状态 (TextFieldValue 支持光标位置, 但去掉 app 端 insertAtCursor 辅助键插入)
    val name = remember { FieldState() }
    val group = remember { FieldState() }
    val pattern = remember { FieldState() }
    val replacement = remember { FieldState() }
    val scopeField = remember { FieldState() }
    val excludeScope = remember { FieldState() }
    val timeout = remember { FieldState() }
    var useRegex by remember { mutableStateOf(false) }
    var scopeTitle by remember { mutableStateOf(false) }
    var scopeContent by remember { mutableStateOf(true) }
    var focusedField by remember { mutableStateOf<FieldState?>(null) }

    // 规则加载完成后回填表单 (对齐 app 端 upReplaceView)
    LaunchedEffect(rule) {
        rule?.let {
            name.reset(it.name)
            group.reset(it.group.orEmpty())
            pattern.reset(it.pattern)
            useRegex = it.isRegex
            replacement.reset(it.replacement)
            scopeTitle = it.scopeTitle
            scopeContent = it.scopeContent
            scopeField.reset(it.scope.orEmpty())
            excludeScope.reset(it.excludeScope.orEmpty())
            timeout.reset(it.timeoutMillisecond.toString())
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("replace_rule_edit"),
            onBack = onBack,
            actions = {
                IconButton(onClick = {
                    val current = buildReplaceRule(rule, name, group, pattern, replacement, scopeField, excludeScope, timeout, useRegex, scopeTitle, scopeContent)
                    viewModel.save(current, onSaved)
                }) {
                    Icon(
                        painter = rememberPainter("ic_save"),
                        contentDescription = rememberString("action_save"),
                        tint = AppTheme.colors.primaryText,
                    )
                }
                OverflowMenu { dismiss ->
                    DropdownMenuItem(
                        onClick = {
                            dismiss()
                            rule?.let {
                                onCopyRule(buildReplaceRule(it, name, group, pattern, replacement, scopeField, excludeScope, timeout, useRegex, scopeTitle, scopeContent))
                            }
                        },
                    ) {
                        Text(rememberString("copy_rule"), color = AppTheme.colors.primaryText)
                    }
                    DropdownMenuItem(
                        onClick = {
                            dismiss()
                            // 宿主端在 lambda 内读剪贴板, 调 viewModel.pasteRule 解析后回调回填表单
                            onPasteRule(
                                { upRule ->
                                    // 粘贴规则回填表单 (沿用 app 端语义: 只回填字段, 不替换 VM 实例)
                                    name.reset(upRule.name)
                                    group.reset(upRule.group.orEmpty())
                                    pattern.reset(upRule.pattern)
                                    useRegex = upRule.isRegex
                                    replacement.reset(upRule.replacement)
                                    scopeTitle = upRule.scopeTitle
                                    scopeContent = upRule.scopeContent
                                    scopeField.reset(upRule.scope.orEmpty())
                                    excludeScope.reset(upRule.excludeScope.orEmpty())
                                    timeout.reset(upRule.timeoutMillisecond.toString())
                                },
                            )
                        },
                    ) {
                        Text(rememberString("paste_rule"), color = AppTheme.colors.primaryText)
                    }
                }
            },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            FormField(name, rememberString("replace_rule_summary")) { focusedField = it }
            FormField(group, rememberString("group")) { focusedField = it }
            FormField(pattern, rememberString("replace_rule")) { focusedField = it }
            UseRegexRow(useRegex, onToggle = { useRegex = !useRegex }, onHelp = onHelp)
            FormField(replacement, rememberString("replace_to")) { focusedField = it }
            ScopeCheckRow(scopeTitle, scopeContent, { scopeTitle = it }, { scopeContent = it })
            FormField(scopeField, rememberString("replace_scope")) { focusedField = it }
            FormField(excludeScope, rememberString("replace_exclude_scope")) { focusedField = it }
            FormField(timeout, rememberString("timeout_millisecond"), number = true) { focusedField = it }
        }
    }
}

/**
 * 单个输入框状态: 值 + 撤销/重做历史 (上限 100 步)。
 *
 * 对照 app 端 `ReplaceEditActivity.FieldState` 下沉, 去掉 `insertAtCursor` (依赖 app 端
 * `KeyboardToolbarState.insertAtCursor` 扩展); 保留 undo/redo/onChange/reset 语义。
 * undo/redo 暂未在 KMP Screen 内挂快捷键 (app 端走 KeyboardToolbar), 留待宿主接入。
 */
class FieldState {
    var value by mutableStateOf(TextFieldValue(""))
        private set
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    val text: String get() = value.text

    fun onChange(new: TextFieldValue) {
        if (new.text != value.text) {
            undoStack.addLast(value)
            if (undoStack.size > 100) undoStack.removeFirst()
            redoStack.clear()
        }
        value = new
    }

    fun reset(text: String) {
        value = TextFieldValue(text)
        undoStack.clear()
        redoStack.clear()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(value)
        value = prev
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(value)
        value = next
    }
}

/** 文本输入框: label 浮动提示, 聚焦时登记为焦点字段 (供宿主辅助键工具栏定向 undo/redo) */
@Composable
private fun FormField(
    field: FieldState,
    label: String,
    number: Boolean = false,
    onFocusChanged: (FieldState?) -> Unit,
) {
    AppTextField(
        value = field.value,
        onValueChange = { new ->
            field.onChange(
                if (number) new.copy(text = new.text.filter { it.isDigit() }) else new
            )
        },
        label = label,
        keyboardOptions = if (number) {
            KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            KeyboardOptions.Default
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { if (it.isFocused) onFocusChanged(field) },
    )
}

/** 是否使用正则 + 帮助按钮 */
@Composable
private fun UseRegexRow(
    useRegex: Boolean,
    onToggle: () -> Unit,
    onHelp: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppCheckbox(checked = useRegex, onCheckedChange = null)
            Text(
                rememberString("use_regex"),
                color = colors.primaryText,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        IconButton(onClick = onHelp) {
            Icon(
                painter = rememberPainter("ic_help"),
                contentDescription = rememberString("help"),
                tint = colors.primaryText,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 作用范围勾选: 标题 / 正文 */
@Composable
private fun ScopeCheckRow(
    scopeTitle: Boolean,
    scopeContent: Boolean,
    onScopeTitleChange: (Boolean) -> Unit,
    onScopeContentChange: (Boolean) -> Unit,
) {
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clickable { onScopeTitleChange(!scopeTitle) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppCheckbox(checked = scopeTitle, onCheckedChange = null)
            Text(
                rememberString("scope_title"),
                color = colors.primaryText,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.clickable { onScopeContentChange(!scopeContent) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppCheckbox(checked = scopeContent, onCheckedChange = null)
            Text(
                rememberString("scope_content"),
                color = colors.primaryText,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** 从表单字段组装 ReplaceRule (沿用原 rule 实例保留 id) */
private fun buildReplaceRule(
    base: ReplaceRule?,
    name: FieldState,
    group: FieldState,
    pattern: FieldState,
    replacement: FieldState,
    scope: FieldState,
    excludeScope: FieldState,
    timeout: FieldState,
    useRegex: Boolean,
    scopeTitle: Boolean,
    scopeContent: Boolean,
): ReplaceRule {
    val rule = base ?: ReplaceRule()
    rule.name = name.text
    rule.group = group.text
    rule.pattern = pattern.text
    rule.isRegex = useRegex
    rule.replacement = replacement.text
    rule.scopeTitle = scopeTitle
    rule.scopeContent = scopeContent
    rule.scope = scope.text
    rule.excludeScope = excludeScope.text
    rule.timeoutMillisecond = timeout.text.ifEmpty { "3000" }.toLong()
    return rule
}
