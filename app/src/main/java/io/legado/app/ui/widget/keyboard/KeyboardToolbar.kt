package io.legado.app.ui.widget.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppFilletTextButton
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.code.CodeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn

/**
 * Compose 版键盘辅助条状态（原 View 版 KeyboardToolPop 等价件），宿主 Activity 持有。
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
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardToolbar(
    state: KeyboardToolbarState,
    activeCodeView: () -> CodeView?,
    onSendText: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onShowConfig: () -> Unit,
) {
    val colors = AppTheme.colors
    val imeVisible = WindowInsets.isImeVisible
    SideEffect { state.imeVisible = imeVisible }
    // 键盘收起且面板未开时清空查找态（对齐 View 版 dismissRunnable）
    LaunchedEffect(imeVisible) {
        if (!imeVisible && !state.findPanelVisible) state.reset()
    }
    if (!imeVisible && !state.findPanelVisible) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bottomBackground)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colorResource(R.color.bg_divider_line))
        )
        if (state.findPanelVisible) {
            FindReplacePanel(state, activeCodeView)
        }
        AssistKeyRow(state, activeCodeView, onSendText, onUndo, onRedo, onShowConfig)
    }
}

@Composable
private fun AssistKeyRow(
    state: KeyboardToolbarState,
    activeCodeView: () -> CodeView?,
    onSendText: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onShowConfig: () -> Unit,
) {
    val items by produceState(emptyList<KeyboardAssist>()) {
        appDb.keyboardAssistsDao.flowByType(0).catch {
            AppLog.put("键盘帮助组件获取数据失败\n${it.localizedMessage}", it)
        }.flowOn(Dispatchers.IO).collect { value = it }
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
                    activeCodeView()?.clearFocus()
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
    activeCodeView: () -> CodeView?,
) {
    val colors = AppTheme.colors
    val findFocus = remember { FocusRequester() }
    // 面板打开时聚焦查找框（对齐 View 版 requestFocus）
    LaunchedEffect(Unit) { findFocus.requestFocus() }
    // 查找词 200ms 防抖执行查找（对齐 View 版 Debounce）
    LaunchedEffect(state.findText.text) {
        delay(200)
        activeCodeView()?.find(state.findText.text, state.useRegex, state.matchCase, state.matchWholeWord)
    }
    val triggerFind = { forward: Boolean ->
        activeCodeView()?.find(
            state.findText.text, state.useRegex, state.matchCase, state.matchWholeWord, forward
        )
        Unit
    }
    Column(Modifier.fillMaxWidth()) {
        PanelInput(
            labelRes = R.string.search,
            value = state.findText,
            onValueChange = { state.findText = it },
            textFieldModifier = Modifier.focusRequester(findFocus),
            onFocus = { has -> if (has) state.panelFocus = 1 else if (state.panelFocus == 1) state.panelFocus = 0 },
        )
        PanelInput(
            labelRes = R.string.replace,
            value = state.replaceText,
            onValueChange = { state.replaceText = it },
            onFocus = { has -> if (has) state.panelFocus = 2 else if (state.panelFocus == 2) state.panelFocus = 0 },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(35.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelAction(stringResource(R.string.skip_previous), Modifier.weight(1f)) { triggerFind(false) }
            PanelAction(stringResource(R.string.skip_next), Modifier.weight(1f)) { triggerFind(true) }
            PanelAction(stringResource(R.string.replace), Modifier.weight(1f)) {
                activeCodeView()?.replace(
                    state.findText.text, state.useRegex, state.matchCase,
                    state.matchWholeWord, state.replaceText.text
                )
            }
            PanelAction(stringResource(R.string.all), Modifier.weight(1f)) {
                activeCodeView()?.replaceAll(state.replaceText.text)
            }
            var showMore by remember { mutableStateOf(false) }
            Box {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.more_menu),
                    tint = colors.primaryText,
                    modifier = Modifier
                        .clickable { showMore = true }
                        .padding(8.dp),
                )
                AppDropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    FindOption("正则表达式", state.useRegex) {
                        showMore = false; state.useRegex = !state.useRegex; triggerFind(false)
                    }
                    FindOption("全词匹配", state.matchWholeWord) {
                        showMore = false; state.matchWholeWord = !state.matchWholeWord; triggerFind(false)
                    }
                    FindOption("区分大小写", state.matchCase) {
                        showMore = false; state.matchCase = !state.matchCase; triggerFind(false)
                    }
                    DropdownMenuItem(
                        text = { Text("关闭", color = colors.primaryText) },
                        onClick = { showMore = false; state.findPanelVisible = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun FindOption(text: String, checked: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    DropdownMenuItem(
        text = { Text(text, color = colors.primaryText) },
        trailingIcon = { AppMenuCheckbox(checked = checked) },
        onClick = onClick,
    )
}

@Composable
private fun PanelInput(
    labelRes: Int,
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
        Text(stringResource(labelRes), color = colors.primaryText)
        val lineColor = if (focused) colors.accent else colors.secondaryText.copy(alpha = 0.4f)
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
