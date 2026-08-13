package io.legado.app.ui.association

/*
 * 下沉所需资源 key 清单 (供 ResourceProvider 各平台 actual 补全)
 *
 * Painter key (drawable):
 *   - ic_arrow_back  (返回箭头, 已存在)
 *   - ic_save        (保存图标, 已存在)
 *
 * String key (string):
 *   - js_edit        (标题: JS 编辑)
 *   - file_name      (文件名输入框 label)
 *   - code           (代码输入框 label)
 *   - action_save    (保存按钮 contentDescription, 已存在)
 *   - run            (运行按钮文案)
 *   - result         (运行结果区标题)
 *   - empty          (空态文案, 已存在)
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.code.CodeEditorSearchTarget
import io.legado.app.ui.compose.component.code.CodeSearchHighlightState
import io.legado.app.ui.compose.component.code.CodeTextField
import io.legado.app.ui.compose.component.code.KeyboardToolbar
import io.legado.app.ui.compose.component.code.KeyboardToolbarState
import io.legado.app.ui.compose.component.code.rememberCodeEditorState
import io.legado.app.ui.compose.component.code.rememberCodeSyntax
import io.legado.app.ui.compose.platform.imeDismissPadding
import io.legado.app.ui.compose.platform.imeScrollNowFor
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_save
import legado.shared.generated.resources.code
import legado.shared.generated.resources.file_name
import legado.shared.generated.resources.ic_save
import legado.shared.generated.resources.js_edit
import legado.shared.generated.resources.result
import legado.shared.generated.resources.run
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * JS 编辑页用户交互回调。
 *
 * 平台相关能力 (文件持久化) 由宿主实现:
 * - app 端可写沙盒/SAF, desktop 端可走 java.io, iOS 端走 FileManager
 *
 * 保存成功后宿主应调用 [JsEditScreenModel.markSaved] 同步状态。
 */
interface JsEditUiActions {
    fun onBack()

    /** 持久化 JS 代码到文件 (宿主实现), 成功后调 [JsEditScreenModel.markSaved] */
    fun onSave(code: String, fileName: String)
}

/**
 * JS 编辑页 Screen (shared, 平台无关)。
 *
 * app 端无独立 JsEditActivity (JS 编辑分散在 BookSourceEdit / CodeDialog),
 * 本 Screen 作为通用 JS 代码编辑器, 桌面/iOS 端可直接复用。
 *
 * - 标题栏: 返回 + 保存图标 (复用 [AppTitleBar] actions 槽)
 * - 文件名: 单行 [AppTextField]
 * - 代码区: [io.legado.app.ui.compose.component.code.CodeTextField] (js 组语法高亮 + 等宽字体)
 * - 键盘辅助条: [io.legado.app.ui.compose.component.code.KeyboardToolbar] (辅助键/撤销/重做/查找替换)
 * - 运行结果: 可滚动文本区, 显示 JS eval 返回值或异常栈
 * - 运行按钮: [AppTextButton], 触发 [JsEditUiEvent.Run] (由 ScreenModel 走 JsEngines eval)
 *
 * @param state 当前 UI 状态 (由 [JsEditScreenModel] 暴露)
 * @param actions 平台交互回调 (返回/保存)
 * @param onRun 运行按钮回调 (由 Screen 调 dispatch(JsEditUiEvent.Run))
 * @param onCodeChange 代码变更回调 (由 Screen 调 dispatch(JsEditUiEvent.CodeChange))
 * @param onFileNameChange 文件名变更回调 (由 Screen 调 dispatch(JsEditUiEvent.FileNameChange))
 */
@Composable
fun JsEditScreen(
    state: JsEditUiState,
    actions: JsEditUiActions,
    onRun: () -> Unit,
    onCodeChange: (String) -> Unit,
    onFileNameChange: (String) -> Unit,
    onShowKeyboardConfig: () -> Unit = {},
) {
    val colors = AppTheme.colors
    val editor = rememberCodeEditorState(state.code)
    // 只在外部重新载入 (打开文件) 时同步; 记录自己回写的文本, 避免 state 异步滞后时被回卷
    var lastEmitted by remember { mutableStateOf(state.code) }
    LaunchedEffect(state.code) {
        if (state.code != lastEmitted) {
            editor.setText(state.code)
            lastEmitted = state.code
        }
    }
    val emit: (String) -> Unit = { lastEmitted = it; onCodeChange(it) }
    // 查找高亮状态: CodeTextField 叠加全量黄底 + 当前命中强调色 (对齐原版 CodeView 查找高亮)
    val searchHighlight = remember { CodeSearchHighlightState() }
    val focusManager = LocalFocusManager.current
    Column(
        Modifier
            .fillMaxSize()
            // ime 避让在根 (对齐原版 Activity adjustResize): 键盘弹出时编辑区/工具栏
            // 不被键盘覆盖, 收起动画期间提前归零 (imeDismissPadding)
            .imeDismissPadding()
            // 桌面端键盘监听: Ctrl+Z 撤销 / Ctrl+Shift+Z、Ctrl+Y 重做 (对照 BookSourceEditScreen
            // 根 Column); undo/redo 后回写 emit, 否则外部保存丢数据; 消费 Ctrl+Z 压住桌面
            // BasicTextField 内置撤销栈, 避免与 CodeEditorState 手写撤销栈双重撤销
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.Z if event.isShiftPressed -> {
                        editor.redo()
                        emit(editor.value.text)
                        true
                    }

                    Key.Z -> {
                        editor.undo()
                        emit(editor.value.text)
                        true
                    }

                    Key.Y -> {
                        editor.redo()
                        emit(editor.value.text)
                        true
                    }

                    else -> false
                }
            }
    ) {
        AppTitleBar(
            title = stringResource(Res.string.js_edit),
            onBack = { actions.onBack() },
            actions = {
                IconButton(onClick = { actions.onSave(state.code, state.fileName) }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_save),
                        contentDescription = stringResource(Res.string.action_save),
                        tint = colors.primaryText,
                    )
                }
            },
        )
        // 文件名输入
        AppTextField(
            value = state.fileName,
            onValueChange = onFileNameChange,
            label = stringResource(Res.string.file_name),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // 键盘弹出动画期间的瞬移滚动器 (见 ImeInsets): 视口逐帧收缩时把光标行无动画滚到
        // 可见 —— 光标始终可见且不打断; 编辑区顶部窗口 Y 由 onGloballyPositioned 记录
        val scrollState = rememberScrollState()
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val imeMarginPx = with(density) { 12.dp.toPx() }.roundToInt()
        var editWindowY by remember { mutableIntStateOf(0) }
        val imeScrollNow = remember(scrollState, scope) {
            imeScrollNowFor(scrollState, { editWindowY }, imeMarginPx, scope)
        }
        // 代码编辑区: 外部滚动容器 + wrap 高字段。键盘弹出后 (ime 避让收缩视口) 光标行
        // bringIntoView 请求须可达滚动容器 —— 字段自身有界内滚时外部请求无法到达
        // CoreTextField 内部滚动, 光标会被键盘挡住; 滚动与光标可见均由 CodeTextField
        // 内部按精确光标行发起 (对齐原版 EditText 内部滚动 + bringPointIntoView)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .onGloballyPositioned { editWindowY = it.positionInWindow().y.roundToInt() },
        ) {
            CodeTextField(
                value = editor.value,
                onValueChange = {
                    // emit 必须取调整后的 editor.value (回车自动缩进/退格整段删会改写文本),
                    // 旧实现 emit(it.text) 写调整前值 → 保存的代码缺缩进 (与书源编辑 entity
                    // 分叉同源问题)
                    editor.onValueChange(it)
                    emit(editor.value.text)
                },
                syntax = rememberCodeSyntax(js = true),
                label = stringResource(Res.string.code),
                fontSize = 14.sp,
                searchHighlight = searchHighlight,
                imeScrollNow = imeScrollNow,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 运行结果区 (有结果或错误时显示, 可滚动)
        val resultText = state.result
        val errorText = state.error?.stackTraceToString()
        if (resultText != null || errorText != null) {
            ResultSection(
                title = stringResource(Res.string.result),
                content = resultText ?: errorText ?: "",
                isError = resultText == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 160.dp),
            )
        }
        // 底部运行按钮
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextButton(
                text = stringResource(Res.string.run),
                enabled = !state.isRunning && state.code.isNotBlank(),
                onClick = onRun,
            )
        }
        // 键盘辅助条 (软键盘弹出时出现): 辅助键插入/撤销/重做走 editor
        val keyboardState = remember { KeyboardToolbarState() }
        KeyboardToolbar(
            state = keyboardState,
            onSendText = { editor.insertAtCursor(it); emit(editor.value.text) },
            onUndo = { editor.undo(); emit(editor.value.text) },
            onRedo = { editor.redo(); emit(editor.value.text) },
            onShowConfig = onShowKeyboardConfig,
            target = { CodeEditorSearchTarget(editor, searchHighlight) { focusManager.clearFocus() } },
        )
    }
}

/** 运行结果展示区: 标题 + 可滚动正文, error 时正文着 danger 色 */
@Composable
private fun ResultSection(
    title: String,
    content: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(
        modifier
            .background(colors.bottomBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            color = colors.secondaryText,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = content,
                color = if (isError) DesignTokens.arcoDanger else colors.primaryText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
