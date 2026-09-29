package io.legado.app.ui.toolbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebastianneubauer.jsontree.JsonTreeItem
import com.sebastianneubauer.jsontree.defaultDarkColors
import com.sebastianneubauer.jsontree.defaultLightColors
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.book.manage.SourcePickerDialog
import io.legado.app.ui.compose.SelectableText
import io.legado.app.ui.compose.component.AppFilletTextButton
import io.legado.app.ui.compose.component.AppOutlinedButton
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.horizontalMouseWheel
import io.legado.app.ui.compose.component.code.CodeEditorSearchTarget
import io.legado.app.ui.compose.component.code.CodeEditorState
import io.legado.app.ui.compose.component.code.CodeSearchHighlightState
import io.legado.app.ui.compose.component.code.CodeTextField
import io.legado.app.ui.compose.component.code.KeyboardToolbar
import io.legado.app.ui.compose.component.code.KeyboardToolbarState
import io.legado.app.ui.compose.component.code.JsonTreePane
import io.legado.app.ui.compose.component.code.rememberCodeEditorState
import io.legado.app.ui.compose.component.code.rememberFullCodeSyntax
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.widget.dialog.TextDialog
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.json_tree
import legado.ui.generated.resources.result
import legado.ui.generated.resources.source_text
import legado.ui.generated.resources.source_toolbox
import legado.ui.generated.resources.toolbox_action_elements
import legado.ui.generated.resources.toolbox_action_text
import legado.ui.generated.resources.toolbox_action_text_list
import legado.ui.generated.resources.toolbox_input_hint
import legado.ui.generated.resources.toolbox_request
import legado.ui.generated.resources.toolbox_response
import legado.ui.generated.resources.toolbox_run
import legado.ui.generated.resources.toolbox_source_none
import legado.ui.generated.resources.toolbox_stage_content
import legado.ui.generated.resources.toolbox_stage_detail
import legado.ui.generated.resources.toolbox_stage_explore
import legado.ui.generated.resources.toolbox_stage_general
import legado.ui.generated.resources.toolbox_stage_search
import legado.ui.generated.resources.toolbox_stage_toc
import legado.ui.generated.resources.toolbox_url_hint
import org.jetbrains.compose.resources.stringResource

interface SourceToolboxUiActions {
    fun onBack()
    fun onShowKeyboardConfig()
    fun onSourceSelected(source: BookSource)
    fun onClearSource()
    fun onStageChange(stage: ToolboxStage)
    fun onUrlChange(text: String)
    fun onInputChange(text: String)
    fun onRequest()
    fun onRunRule(mode: ToolboxRuleMode)
    fun onRunJs()
    fun onTreeMode(on: Boolean)
}

@Composable
fun SourceToolboxScreen(
    state: ToolboxUiState,
    actions: SourceToolboxUiActions,
) {
    val colors = AppTheme.colors
    // 全文查看: title / content / 已解析 JSON 根 (树视图直通用, 文本查看场景为 null)
    var fullText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showSourcePicker by remember { mutableStateOf(false) }
    // 响应区树视图的聚焦栈与原始值文本视图 (就地聚焦, 不新开窗口)
    var focusStack by remember { mutableStateOf<List<JsonTreeItem>>(emptyList()) }
    // 键盘辅助条 + 活跃编辑器 (书源编辑同款: 聚焦字段登记, 辅助键/撤销/重做/查找都作用于它)
    val keyboardState = remember { KeyboardToolbarState() }
    val searchHighlight = remember { CodeSearchHighlightState() }
    val activeEditor = remember { mutableStateOf<CodeEditorState?>(null) }
    val focusManager = LocalFocusManager.current

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        AppTitleBar(
            title = stringResource(Res.string.source_toolbox),
            onBack = { actions.onBack() },
        )
        BoxWithConstraints(Modifier.weight(1f)) {
            val showFullText: (String, String) -> Unit = { title, content -> fullText = title to content }
            if (maxWidth >= DesignTokens.wideScreenMinWidth) {
                // 宽屏双栏: 选源/阶段通栏, 左栏请求管线 (URL/请求/响应), 右栏规则/JS, 两栏独立滚动
                Column(Modifier.fillMaxSize()) {
                    SourceStageHeader(state, actions) { showSourcePicker = true }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            RequestPane(
                                state, actions, activeEditor, searchHighlight, showFullText,
                                focusStack, { focusStack = it },
                            )
                            Spacer(Modifier.heightIn(min = DesignTokens.spacingLg))
                        }
                        Spacer(Modifier.width(DesignTokens.spacingMd))
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            InputSection(state, actions, activeEditor, searchHighlight, showFullText)
                            Spacer(Modifier.heightIn(min = DesignTokens.spacingLg))
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SourceStageHeader(state, actions) { showSourcePicker = true }
                    RequestPane(
                        state, actions, activeEditor, searchHighlight, showFullText,
                        focusStack, { focusStack = it },
                    )
                    InputSection(state, actions, activeEditor, searchHighlight, showFullText)
                    Spacer(Modifier.heightIn(min = DesignTokens.spacingLg))
                }
            }
        }
        KeyboardToolbar(
            state = keyboardState,
            onSendText = { activeEditor.value?.insertAtCursor(it) },
            onUndo = { activeEditor.value?.undo() },
            onRedo = { activeEditor.value?.redo() },
            onShowConfig = { actions.onShowKeyboardConfig() },
            target = {
                activeEditor.value?.let { editor ->
                    CodeEditorSearchTarget(editor, searchHighlight) { focusManager.clearFocus() }
                }
            },
        )
    }

    if (showSourcePicker) {
        SourcePickerDialog(
            sources = state.sources,
            onSourceSelected = { source ->
                actions.onSourceSelected(source)
                showSourcePicker = false
            },
            showDelayMenu = false,
            onDismiss = { showSourcePicker = false },
        )
    }

    fullText?.let { (title, content) ->
        TextDialog(
            title = title,
            content = content,
            onDismiss = { fullText = null },
            allowJsonTree = true,
        )
    }
}

/** 选源 chip + 阶段按钮: 顶部单行整体横滚 (窄屏放不下时连同选源一起滚) */
@Composable
private fun SourceStageHeader(
    state: ToolboxUiState,
    actions: SourceToolboxUiActions,
    onPickSource: () -> Unit,
) {
    val stageLabels = listOf(
        ToolboxStage.GENERAL to stringResource(Res.string.toolbox_stage_general),
        ToolboxStage.SEARCH to stringResource(Res.string.toolbox_stage_search),
        ToolboxStage.EXPLORE to stringResource(Res.string.toolbox_stage_explore),
        ToolboxStage.DETAIL to stringResource(Res.string.toolbox_stage_detail),
        ToolboxStage.TOC to stringResource(Res.string.toolbox_stage_toc),
        ToolboxStage.CONTENT to stringResource(Res.string.toolbox_stage_content),
    )
    val scrollState = rememberScrollState()
    Row(
        Modifier
            .padding(vertical = DesignTokens.spacingDefault)
            .horizontalScroll(scrollState)
            .horizontalMouseWheel(scrollState),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sourceLabel = if (state.sourceName.isBlank()) {
            stringResource(Res.string.toolbox_source_none)
        } else {
            state.sourceName
        }
        AppFilletTextButton(
            text = sourceLabel,
            bold = state.sourceName.isNotBlank(),
            focusable = false,
            // 长按清除所选书源
            onLongClick = { actions.onClearSource() },
        ) { onPickSource() }
        stageLabels.forEach { (stage, label) ->
            AppFilletTextButton(
                text = label,
                bold = state.stage == stage,
                focusable = false,
            ) { actions.onStageChange(stage) }
        }
    }
}

/** 请求管线: URL 输入 + 请求按钮 + 响应区 (窄屏单列的一段 / 宽屏左栏) */
@Composable
private fun RequestPane(
    state: ToolboxUiState,
    actions: SourceToolboxUiActions,
    activeEditor: MutableState<CodeEditorState?>,
    searchHighlight: CodeSearchHighlightState,
    showFullText: (String, String) -> Unit,
    focusStack: List<JsonTreeItem>,
    onFocusStackChange: (List<JsonTreeItem>) -> Unit,
) {
    val colors = AppTheme.colors
    val editMaxLine = remember { AppConfigProviders.get().sourceEditMaxLine }
    val searchRefreshScope = rememberCoroutineScope()
    val urlEditor = rememberCodeEditorState(state.urlText)
    urlEditor.onChanged = { newValue ->
        actions.onUrlChange(newValue.text)
        if (activeEditor.value === urlEditor && searchHighlight.keyword.isNotEmpty()) {
            searchHighlight.refreshDebounced(newValue.text, searchRefreshScope)
        }
    }
    LaunchedEffect(state.urlText) { urlEditor.setText(state.urlText) }
    CodeTextField(
        value = urlEditor.textFieldState,
        inputTransformation = urlEditor.inputTransformation,
        syntax = rememberFullCodeSyntax(),
        label = stringResource(Res.string.toolbox_url_hint),
        showLineNumbers = true,
        maxLines = editMaxLine,
        searchHighlight = if (activeEditor.value === urlEditor) searchHighlight else null,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (it.isFocused && activeEditor.value != urlEditor) {
                    searchHighlight.clear()
                    activeEditor.value = urlEditor
                }
            },
    )
    Row(
        Modifier.padding(vertical = DesignTokens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppOutlinedButton(
            stringResource(Res.string.toolbox_request),
            // URL 为空 (纯空白) 时请求无意义, 按钮禁用且不触发; 请求中禁用防重复
            enabled = !state.busy && state.urlText.isNotBlank(),
        ) { actions.onRequest() }
        if (state.busy) {
            Spacer(Modifier.width(DesignTokens.spacingMd))
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.accent,
            )
        }
        // 请求成功拿到响应才展示树形切换与耗时/状态/大小
        if (!state.busy && state.resError == null && state.resCode != 0) {
            if (state.resIsJson) {
                Spacer(Modifier.width(DesignTokens.spacingMd))
                AppOutlinedButton(
                    stringResource(if (state.resTreeMode) Res.string.source_text else Res.string.json_tree),
                ) { actions.onTreeMode(!state.resTreeMode) }
            }
            val resBodySize = remember(state.resBody) { formatBodySize(state.resBody.encodeToByteArray().size) }
            Text(
                text = "${state.resElapsedMs}ms · ${state.resCode} ${state.resMessage} · $resBodySize",
                color = colors.secondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = DesignTokens.spacingMd),
            )
        }
    }
    ResponseSection(
        state = state,
        showFullText = showFullText,
        focusStack = focusStack,
        onFocusStackChange = onFocusStackChange,
    )
}

@Composable
private fun ResponseSection(
    state: ToolboxUiState,
    showFullText: (String, String) -> Unit,
    focusStack: List<JsonTreeItem>,
    onFocusStackChange: (List<JsonTreeItem>) -> Unit,
) {
    val colors = AppTheme.colors
    if (state.resCode == 0 && state.resError == null) return
    val responseTitle = stringResource(Res.string.toolbox_response)
    if (state.resError != null) {
        SelectableText(
            text = state.resError,
            color = colors.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = DesignTokens.spacingXs),
        )
        return
    }
    if (state.resTreeMode) {
        var treeError by remember(state.resBody) { mutableStateOf<Throwable?>(null) }
        // 换响应后聚焦栈失效 (旧聚焦指向上一份文档的节点), 就地复位
        LaunchedEffect(state.resBody) {
            onFocusStackChange(emptyList())
        }
        // "查看": 对象/数组就地聚焦到该节点, 原始值就地显示值文本 (面包屑均照常可退回)
        JsonTreePane(
            json = state.resBody,
            focusStack = focusStack,
            onFocusStackChange = onFocusStackChange,
            rootLabel = responseTitle,
            modifier = Modifier.fillMaxWidth(),
            treeModifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp),
            colors = if (colors.isDark) defaultDarkColors else defaultLightColors,
            contentPadding = PaddingValues(horizontal = DesignTokens.spacingXs),
            onError = { treeError = it },
        )
        treeError?.let { err ->
            SelectableText(
                text = err.toString(),
                color = colors.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = DesignTokens.spacingXs),
            )
        }
    } else {
        Text(
            text = state.resBody,
            color = colors.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                // 点击响应区进全文 (对话框内自带复制/树形视图)
                .clickable { showFullText(responseTitle, state.resBody) }
                .padding(vertical = DesignTokens.spacingXs),
            maxLines = 10,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 解析/运行区: 单一输入框, 对当前响应按需执行 —— 取文本/取列表/取元素走书源规则解析,
 * 运行走书源 jsLib 环境。结果为规则解析多行或 JS 返回值单行。
 */
@Composable
private fun InputSection(
    state: ToolboxUiState,
    actions: SourceToolboxUiActions,
    activeEditor: MutableState<CodeEditorState?>,
    searchHighlight: CodeSearchHighlightState,
    showFullText: (String, String) -> Unit,
) {
    val colors = AppTheme.colors
    val editMaxLine = remember { AppConfigProviders.get().sourceEditMaxLine }
    val searchRefreshScope = rememberCoroutineScope()
    val inputEditor = rememberCodeEditorState(state.inputText)
    inputEditor.onChanged = { newValue ->
        actions.onInputChange(newValue.text)
        if (activeEditor.value === inputEditor && searchHighlight.keyword.isNotEmpty()) {
            searchHighlight.refreshDebounced(newValue.text, searchRefreshScope)
        }
    }
    LaunchedEffect(state.inputText) { inputEditor.setText(state.inputText) }
    CodeTextField(
        value = inputEditor.textFieldState,
        inputTransformation = inputEditor.inputTransformation,
        syntax = rememberFullCodeSyntax(),
        label = stringResource(Res.string.toolbox_input_hint),
        showLineNumbers = true,
        maxLines = editMaxLine,
        searchHighlight = if (activeEditor.value === inputEditor) searchHighlight else null,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (it.isFocused && activeEditor.value != inputEditor) {
                    searchHighlight.clear()
                    activeEditor.value = inputEditor
                }
            },
    )
    Row(Modifier.padding(vertical = DesignTokens.spacingXs)) {
        AppOutlinedButton(stringResource(Res.string.toolbox_action_text)) {
            actions.onRunRule(ToolboxRuleMode.TEXT)
        }
        AppOutlinedButton(
            stringResource(Res.string.toolbox_action_text_list),
            modifier = Modifier.padding(horizontal = DesignTokens.spacingMd),
        ) { actions.onRunRule(ToolboxRuleMode.TEXT_LIST) }
        AppOutlinedButton(stringResource(Res.string.toolbox_action_elements)) {
            actions.onRunRule(ToolboxRuleMode.ELEMENTS)
        }
        AppOutlinedButton(
            stringResource(Res.string.toolbox_run),
            modifier = Modifier.padding(start = DesignTokens.spacingMd),
        ) { actions.onRunJs() }
    }
    if (state.resultError != null) {
        SelectableText(
            text = state.resultError,
            color = colors.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = DesignTokens.spacingXs),
        )
    }
    val resultTitle = stringResource(Res.string.result)
    val showIndex = state.result.size > 1
    if (state.result.size > RESULT_LAZY_THRESHOLD) {
        // 大列表限高懒加载, 避免全量组合拖垮页面
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp),
        ) {
            itemsIndexed(state.result) { index, item ->
                ResultRow(resultTitle, showIndex, index, item, showFullText)
            }
        }
    } else {
        state.result.forEachIndexed { index, item ->
            ResultRow(resultTitle, showIndex, index, item, showFullText)
        }
    }
}

/** 结果列表转懒渲染的条数阈值: 低于此值直渲染 (自然高度), 高于此值限高滚动 */
private const val RESULT_LAZY_THRESHOLD = 50

@Composable
private fun ResultRow(
    resultTitle: String,
    showIndex: Boolean,
    index: Int,
    item: String,
    showFullText: (String, String) -> Unit,
) {
    Text(
        text = if (showIndex) "${index + 1}. $item" else item,
        color = AppTheme.colors.secondaryText,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            // 点击结果项进全文 (对话框内自带复制)
            .clickable { showFullText(resultTitle, item) }
            .padding(vertical = DesignTokens.spacingXs),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 响应体大小文案: B/KB/MB 一位小数, 纯整数运算 (跨端一致) */
private fun formatBodySize(bytes: Int): String = when {
    bytes >= 1 shl 20 -> "${bytes / (1 shl 20)}.${(bytes % (1 shl 20)) * 10 / (1 shl 20)}MB"
    bytes >= 1 shl 10 -> "${bytes / (1 shl 10)}.${(bytes % (1 shl 10)) * 10 / (1 shl 10)}KB"
    else -> "${bytes}B"
}

