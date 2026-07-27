package io.legado.app.ui.replace.edit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.keyboard.KeyboardAssistsConfig
import io.legado.app.ui.widget.keyboard.KeyboardToolbar
import io.legado.app.ui.widget.keyboard.KeyboardToolbarState
import io.legado.app.ui.widget.keyboard.insertAtCursor
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp

/**
 * 编辑替换规则（纯 Compose，迁 activity_replace_edit）。
 * 表单值由 [FieldState] 承载（TextFieldValue 支持光标处插入辅助键文本）；
 * 无 CodeView，键盘辅助条 activeCodeView 恒 null（与原 KeyboardToolPop.CallBack 返回 null 一致），
 * undo/redo 原依赖 EditText 内建撤销，Compose 侧以每字段轻量历史栈等价。
 */
class ReplaceEditActivity : BaseComposeActivity() {

    companion object {

        fun startIntent(
            context: Context,
            id: Long = -1,
            pattern: String? = null,
            isRegex: Boolean = false,
            scope: String? = null
        ): Intent {
            val intent = Intent(context, ReplaceEditActivity::class.java)
            intent.putExtra("id", id)
            intent.putExtra("pattern", pattern)
            intent.putExtra("isRegex", isRegex)
            intent.putExtra("scope", scope)
            return intent
        }

    }

    val viewModel by viewModels<ReplaceEditViewModel>()

    /** 单个输入框状态：值 + 撤销/重做历史（上限 100 步） */
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

        fun insert(text: String) = onChange(value.insertAtCursor(text))

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

    private val name = FieldState()
    private val group = FieldState()
    private val pattern = FieldState()
    private val replacement = FieldState()
    private val scope = FieldState()
    private val excludeScope = FieldState()
    private val timeout = FieldState()
    private var useRegex by mutableStateOf(false)
    private var scopeTitle by mutableStateOf(false)
    private var scopeContent by mutableStateOf(true)

    /** 当前聚焦的输入框，辅助键 sendText/undo/redo 的目标 */
    private var focusedField: FieldState? = null

    private val toolbarState = KeyboardToolbarState()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!toolbarState.tryConsumeBack {}) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        viewModel.initData(intent) {
            upReplaceView(it)
        }
    }

    @Composable
    override fun Content() {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            AppTitleBar(
                title = stringResource(R.string.replace_rule_edit),
                onBack = { finish() },
                actions = { TitleActions() },
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            ) {
                FormField(name, stringResource(R.string.replace_rule_summary))
                FormField(group, stringResource(R.string.group))
                FormField(pattern, stringResource(R.string.replace_rule))
                UseRegexRow()
                FormField(replacement, stringResource(R.string.replace_to))
                ScopeCheckRow()
                FormField(scope, stringResource(R.string.replace_scope))
                FormField(excludeScope, stringResource(R.string.replace_exclude_scope))
                FormField(timeout, stringResource(R.string.timeout_millisecond), number = true)
            }
            KeyboardToolbar(
                state = toolbarState,
                activeCodeView = { null },
                onSendText = { text ->
                    if (text.isNotBlank()) {
                        when (toolbarState.panelFocus) {
                            1 -> toolbarState.findText =
                                toolbarState.findText.insertAtCursor(text)

                            2 -> toolbarState.replaceText =
                                toolbarState.replaceText.insertAtCursor(text)

                            else -> focusedField?.insert(text)
                        }
                    }
                },
                onUndo = { focusedField?.undo() },
                onRedo = { focusedField?.redo() },
                onShowConfig = { showDialogFragment<KeyboardAssistsConfig>() },
            )
        }
    }

    @Composable
    private fun TitleActions() {
        val colors = AppTheme.colors
        IconButton(onClick = {
            viewModel.save(getReplaceRule()) {
                setResult(RESULT_OK)
                finish()
            }
        }) {
            Icon(
                painter = rememberPainter("ic_save"),
                contentDescription = stringResource(R.string.action_save),
                tint = colors.primaryText,
            )
        }
        OverflowMenu { dismiss ->
            DropdownMenuItem(
                onClick = {
                    dismiss()
                    sendToClip(GSON.toJson(getReplaceRule()))
                },
            ) { Text(stringResource(R.string.copy_rule), color = colors.primaryText) }
            DropdownMenuItem(
                onClick = {
                    dismiss()
                    viewModel.pasteRule { upReplaceView(it) }
                },
            ) { Text(stringResource(R.string.paste_rule), color = colors.primaryText) }
        }
    }

    /** 对照 TextInputLayout+EditText：label 浮动提示，聚焦时登记为辅助键目标 */
    @Composable
    private fun FormField(field: FieldState, label: String, number: Boolean = false) {
        AppTextField(
            value = field.value,
            onValueChange = { new ->
                if (number) {
                    field.onChange(new.copy(text = new.text.filter { it.isDigit() }))
                } else {
                    field.onChange(new)
                }
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
                .onFocusChanged { if (it.isFocused) focusedField = field },
        )
    }

    @Composable
    private fun UseRegexRow() {
        val colors = AppTheme.colors
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable { useRegex = !useRegex },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppCheckbox(checked = useRegex, onCheckedChange = null)
                Text(
                    stringResource(R.string.use_regex),
                    color = colors.primaryText,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            IconButton(onClick = { showHelp("regexHelp") }) {
                Icon(
                    painter = rememberPainter("ic_help"),
                    contentDescription = stringResource(R.string.help),
                    tint = colors.primaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    @Composable
    private fun ScopeCheckRow() {
        val colors = AppTheme.colors
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clickable { scopeTitle = !scopeTitle },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppCheckbox(checked = scopeTitle, onCheckedChange = null)
                Text(
                    stringResource(R.string.scope_title),
                    color = colors.primaryText,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier.clickable { scopeContent = !scopeContent },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppCheckbox(checked = scopeContent, onCheckedChange = null)
                Text(
                    stringResource(R.string.scope_content),
                    color = colors.primaryText,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }

    private fun upReplaceView(replaceRule: ReplaceRule) {
        name.reset(replaceRule.name)
        group.reset(replaceRule.group.orEmpty())
        pattern.reset(replaceRule.pattern)
        useRegex = replaceRule.isRegex
        replacement.reset(replaceRule.replacement)
        scopeTitle = replaceRule.scopeTitle
        scopeContent = replaceRule.scopeContent
        scope.reset(replaceRule.scope.orEmpty())
        excludeScope.reset(replaceRule.excludeScope.orEmpty())
        timeout.reset(replaceRule.timeoutMillisecond.toString())
    }

    private fun getReplaceRule(): ReplaceRule {
        val replaceRule: ReplaceRule = viewModel.replaceRule ?: ReplaceRule()
        replaceRule.name = name.text
        replaceRule.group = group.text
        replaceRule.pattern = pattern.text
        replaceRule.isRegex = useRegex
        replaceRule.replacement = replacement.text
        replaceRule.scopeTitle = scopeTitle
        replaceRule.scopeContent = scopeContent
        replaceRule.scope = scope.text
        replaceRule.excludeScope = excludeScope.text
        replaceRule.timeoutMillisecond = timeout.text.ifEmpty { "3000" }.toLong()
        return replaceRule
    }

}
