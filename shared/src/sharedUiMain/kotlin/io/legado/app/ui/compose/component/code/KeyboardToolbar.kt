package io.legado.app.ui.compose.component.code

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppFilletTextButton
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.all
import legado.shared.generated.resources.close
import legado.shared.generated.resources.match_case
import legado.shared.generated.resources.match_whole_word
import legado.shared.generated.resources.more_menu
import legado.shared.generated.resources.regular_expression
import legado.shared.generated.resources.replace
import legado.shared.generated.resources.search
import legado.shared.generated.resources.skip_next
import legado.shared.generated.resources.skip_previous
import org.jetbrains.compose.resources.stringResource

/**
 * 键盘辅助条对接的代码编辑器 (原 app 版直接持 CodeView, 下沉后收为接口)。
 * 传 null 时查找/替换按钮为空操作 (对齐原版 activeCodeView() 返回 null)。
 */
interface KeyboardToolbarTarget {
    fun clearFocus()
    fun find(
        keyword: String,
        useRegex: Boolean,
        matchCase: Boolean,
        wholeWord: Boolean,
        forward: Boolean? = null,
    )

    fun replace(
        keyword: String,
        useRegex: Boolean,
        matchCase: Boolean,
        wholeWord: Boolean,
        replacement: String,
    )

    fun replaceAll(replacement: String)
}

/**
 * Compose 版键盘辅助条状态（原 View 版 KeyboardToolPop 等价件），宿主持有。
 */
class KeyboardToolbarState {

    var findPanelVisible by mutableStateOf(false)
    var findText by mutableStateOf(TextFieldValue(""))
    var replaceText by mutableStateOf(TextFieldValue(""))
    var useRegex by mutableStateOf(false)
    var matchCase by mutableStateOf(false)
    var matchWholeWord by mutableStateOf(false)

    /** 0=无 1=查找框 2=替换框：面板输入焦点，辅助键 sendText 的 Compose 侧插入目标 */
    var panelFocus = 0

    /** 组合侧回写，供 back 消费判断（对齐 View 版 mIsSoftKeyBoardShowing） */
    var imeVisible = false

    fun showFindReplace(keyword: String) {
        findText = TextFieldValue(keyword, TextRange(keyword.length))
        findPanelVisible = true
    }

    fun reset() {
        findText = TextFieldValue("")
        replaceText = TextFieldValue("")
        useRegex = false
        matchCase = false
        matchWholeWord = false
    }

    /** 对齐 View 版 tryConsumeBack：键盘已收起而面板仍开时，back 收面板并清理搜索态 */
    fun tryConsumeBack(clearSearch: () -> Unit): Boolean {
        if (imeVisible || !findPanelVisible) return false
        findPanelVisible = false
        reset()
        clearSearch()
        return true
    }
}

/** 在光标处插入文本，辅助键 sendText 落到面板输入框时用 */
fun TextFieldValue.insertAtCursor(insert: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    return TextFieldValue(text.replaceRange(start, end, insert), TextRange(start + insert.length))
}

/**
 * 键盘辅助条：软键盘弹出时出现；辅助键行(⚙️/撤销/重做/自定义键) + 查找替换面板。
 *
 * [onSendText] 仅在面板输入框未聚焦时触发, 面板聚焦时插入落到面板自身输入框
 * (原 app 版由宿主 Activity 按 panelFocus 分流, 下沉后收进组件内)。
 */
@Composable
fun KeyboardToolbar(
    state: KeyboardToolbarState,
    onSendText: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onShowConfig: () -> Unit,
    target: () -> KeyboardToolbarTarget? = { null },
) {
    val colors = AppTheme.colors
    // isImeVisible 非 CMP 公共 API, 用 ime inset 底部高度等价判断
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    SideEffect { state.imeVisible = imeVisible }
    // 键盘收起且面板未开时清空查找态（对齐 View 版 dismissRunnable）
    LaunchedEffect(imeVisible) {
        if (!imeVisible && !state.findPanelVisible) state.reset()
    }
    if (!imeVisible && !state.findPanelVisible) return
    val sendText: (String) -> Unit = { text ->
        when (state.panelFocus) {
            1 -> state.findText = state.findText.insertAtCursor(text)
            2 -> state.replaceText = state.replaceText.insertAtCursor(text)
            else -> onSendText(text)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bottomBackground)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(rememberColor("bg_divider_line"))
        )
        if (state.findPanelVisible) {
            FindReplacePanel(state, target)
        }
        AssistKeyRow(state, target, sendText, onUndo, onRedo, onShowConfig)
    }
}

@Composable
private fun AssistKeyRow(
    state: KeyboardToolbarState,
    target: () -> KeyboardToolbarTarget?,
    onSendText: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onShowConfig: () -> Unit,
) {
    val items by produceState(emptyList<KeyboardAssist>()) {
        AppDatabaseProviders.get().appDb.keyboardAssistsDao.flowByType(0).catch {
            AppLog.put("键盘帮助组件获取数据失败\n${it.message}", it)
        }.flowOn(IoDispatcher).collect { value = it }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppFilletTextButton(
            "⚙️",
            onLongClick = {
                if (state.findPanelVisible) {
                    state.findPanelVisible = false
                } else {
                    state.findText = TextFieldValue("")
                    target()?.clearFocus()
                    state.findPanelVisible = true
                }
            },
        ) { onShowConfig() }
        AppFilletTextButton("↩️") { onUndo() }
        AppFilletTextButton("↪️") { onRedo() }
        items.forEach { item ->
            AppFilletTextButton(item.key) { onSendText(item.value) }
        }
    }
}

@Composable
private fun FindReplacePanel(
    state: KeyboardToolbarState,
    target: () -> KeyboardToolbarTarget?,
) {
    val colors = AppTheme.colors
    val findFocus = remember { FocusRequester() }
    // 面板打开时聚焦查找框（对齐 View 版 requestFocus）
    LaunchedEffect(Unit) { findFocus.requestFocus() }
    // 查找词 200ms 防抖执行查找（对齐 View 版 Debounce）
    LaunchedEffect(state.findText.text) {
        delay(200)
        target()?.find(state.findText.text, state.useRegex, state.matchCase, state.matchWholeWord)
    }
    val triggerFind = { forward: Boolean ->
        target()?.find(
            state.findText.text, state.useRegex, state.matchCase, state.matchWholeWord, forward
        )
        Unit
    }
    Column(Modifier.fillMaxWidth()) {
        PanelInput(
            label = stringResource(Res.string.search),
            value = state.findText,
            onValueChange = { state.findText = it },
            textFieldModifier = Modifier.focusRequester(findFocus),
            onFocus = { has ->
                if (has) state.panelFocus = 1 else if (state.panelFocus == 1) state.panelFocus = 0
            },
        )
        PanelInput(
            label = stringResource(Res.string.replace),
            value = state.replaceText,
            onValueChange = { state.replaceText = it },
            onFocus = { has ->
                if (has) state.panelFocus = 2 else if (state.panelFocus == 2) state.panelFocus = 0
            },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(35.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelAction(
                stringResource(Res.string.skip_previous),
                Modifier.weight(1f)
            ) { triggerFind(false) }
            PanelAction(stringResource(Res.string.skip_next), Modifier.weight(1f)) {
                triggerFind(
                    true
                )
            }
            PanelAction(stringResource(Res.string.replace), Modifier.weight(1f)) {
                target()?.replace(
                    state.findText.text, state.useRegex, state.matchCase,
                    state.matchWholeWord, state.replaceText.text
                )
            }
            PanelAction(stringResource(Res.string.all), Modifier.weight(1f)) {
                target()?.replaceAll(state.replaceText.text)
            }
            var showMore by remember { mutableStateOf(false) }
            Box {
                Icon(
                    painter = rememberPainter("ic_more_vert"),
                    contentDescription = stringResource(Res.string.more_menu),
                    tint = colors.primaryText,
                    modifier = Modifier
                        .clickable { showMore = true }
                        .padding(8.dp),
                )
                AppDropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    FindOption(stringResource(Res.string.regular_expression), state.useRegex) {
                        showMore = false; state.useRegex = !state.useRegex; triggerFind(false)
                    }
                    FindOption(stringResource(Res.string.match_whole_word), state.matchWholeWord) {
                        showMore = false; state.matchWholeWord = !state.matchWholeWord; triggerFind(
                        false
                    )
                    }
                    FindOption(stringResource(Res.string.match_case), state.matchCase) {
                        showMore = false; state.matchCase = !state.matchCase; triggerFind(false)
                    }
                    DropdownMenuItem(
                        onClick = { showMore = false; state.findPanelVisible = false },
                    ) { Text(stringResource(Res.string.close), color = colors.primaryText) }
                }
            }
        }
    }
}

@Composable
private fun FindOption(text: String, checked: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    DropdownMenuItem(onClick = onClick) {
        Text(
            text,
            color = colors.primaryText,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        )
        AppMenuCheckbox(checked = checked)
    }
}

@Composable
private fun PanelInput(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    textFieldModifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit,
) {
    val colors = AppTheme.colors
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(45.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.primaryText)
        val lineColor = if (focused) colors.accent else colors.secondaryText.copy(alpha = 0.4f)
        // 保留 BasicTextField: IME 工具栏自绘 drawBehind 底线复刻 EditText, 无需 AppTextField 的浮动 label/最小高度
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.primaryText, fontSize = 16.sp),
            cursorBrush = SolidColor(colors.accent),
            modifier = textFieldModifier
                .weight(1f)
                .padding(start = 8.dp)
                .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
                // 复刻 EditText 底线形态
                .drawBehind {
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, size.height + 4.dp.toPx()),
                        end = Offset(size.width, size.height + 4.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
        )
    }
}

@Composable
private fun PanelAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AppTheme.colors.primaryText)
    }
}
