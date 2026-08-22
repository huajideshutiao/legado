package io.legado.app.ui.book.source.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.code.CodeEditorSearchTarget
import io.legado.app.ui.compose.component.code.CodeEditorState
import io.legado.app.ui.compose.component.code.CodeSearchHighlightState
import io.legado.app.ui.compose.component.code.CodeSyntaxScheme
import io.legado.app.ui.compose.component.code.CodeTextField
import io.legado.app.ui.compose.component.code.KeyboardToolbar
import io.legado.app.ui.compose.component.code.KeyboardToolbarState
import io.legado.app.ui.compose.component.code.rememberFullCodeSyntax
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.TextToolbarFindReplaceEffect
import io.legado.app.ui.widget.text.EditEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_save
import legado.shared.generated.resources.auto_indent
import legado.shared.generated.resources.book_source_tutorial
import legado.shared.generated.resources.book_type
import legado.shared.generated.resources.cookie
import legado.shared.generated.resources.copy_source
import legado.shared.generated.resources.debug_source
import legado.shared.generated.resources.edit_book_source
import legado.shared.generated.resources.explore_cols
import legado.shared.generated.resources.explore_item_style
import legado.shared.generated.resources.explore_style
import legado.shared.generated.resources.help
import legado.shared.generated.resources.ic_arrow_drop_down
import legado.shared.generated.resources.ic_bug_report
import legado.shared.generated.resources.ic_save
import legado.shared.generated.resources.js_tutorial
import legado.shared.generated.resources.login
import legado.shared.generated.resources.paste_source
import legado.shared.generated.resources.regex_tutorial
import legado.shared.generated.resources.search
import legado.shared.generated.resources.set_source_variable
import legado.shared.generated.resources.str_share
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * 书源编辑 Screen (KMP 版, 替代 app 端 BookSourceEditScreen)。
 *
 * 下沉改动 (对照 app 端原版 11 个 @Composable):
 * - 去掉对 BookSourceEditActivity 的直接依赖, 改经 [BookSourceEditState] + [BookSourceEditCallbacks]
 *   + [editEntities] 传状态与回调, 解耦 Composable 与 Android Activity
 * - 资源访问全改 key-based: stringResource(Res.string.xxx) / rememberPainter("xxx") /
 *   rememberColor("xxx") / stringArrayResource(Res.array.xxx); 所需 key 见下方清单
 * - 原版 Android View 版 CodeView 已删除, 四端统一走 [CodeTextField]
 *   (等宽/高亮/行号/查找替换); KeyboardToolbar → 共享组件 (直连 DAO)
 * - 底部避让收敛为根 Column 一处 (ime ∪ 导航条), 页内不再散落第二份
 * - L3 不可下沉项: KeyboardAssistsConfig 弹窗平台专属, 经 [onShowKeyboardConfig] 回调注入
 *
 * @param state           顶部表单状态 (书源类型/各启用开关/tab/版本号)
 * @param callbacks       事件回调 (菜单动作/状态变更)
 * @param editEntities    按 tab 返回当前页的 [EditEntity] 列表 (宿主持有可变列表, 供 getSource 读取)
 * @param fieldEditors    字段编辑器状态容器 (fieldId → [CodeEditorState]), 宿主持有, 按
 *                        sourceVersion 重建: 版本变化即全部字段编辑器重置 (对齐原版 upSourceView
 *                        重建实体); 容器内编辑器 value 是 Compose State, 单字段写入只重组该字段,
 *                        避免 SnapshotStateMap 写一字段全字段失效
 * @param onFieldFocus    字段获得焦点回调 `(fieldId, entity)`, 供宿主定位自动缩进目标
 * @param onShowKeyboardConfig 辅助键配置入口 (app 端 `showDialogFragment<KeyboardAssistsConfig>()`)
 * @param requestFocusSignal 请求根节点持焦的信号 (宿主在页面回到栈顶时投递; 页面全程留在
 *                           Composition, 进入时的持焦只在首次组合执行, 返回后需重新请求)
 * @param modifier        外部 modifier
 */
@Composable
fun BookSourceEditScreen(
    state: BookSourceEditState,
    callbacks: BookSourceEditCallbacks,
    editEntities: (Int) -> List<EditEntity>,
    fieldEditors: MutableMap<String, CodeEditorState>,
    modifier: Modifier = Modifier,
    onFieldFocus: (String, EditEntity) -> Unit = { _, _ -> },
    onShowKeyboardConfig: () -> Unit = {},
    requestFocusSignal: Flow<Unit> = emptyFlow(),
) {
    // 焦点字段的编辑器状态 (对照 app 端 lastActiveCodeView): 辅助键插入/撤销/重做/查找替换的目标。
    // 以 State 引用下发到字段层, 字段内部用 derivedStateOf 做 === 判定 (见 CodeField),
    // 聚焦切换只重组新旧两个字段, 其余可见字段的调用点不读取本 State
    val activeEditor = remember { mutableStateOf<CodeEditorState?>(null) }
    val keyboardState = remember { KeyboardToolbarState() }
    // 查找高亮状态: 供聚焦字段的 CodeTextField 叠加全量黄底 + 当前命中强调色 (对齐原版 CodeView 查找高亮)
    val searchHighlight = remember { CodeSearchHighlightState() }
    val focusManager = LocalFocusManager.current
    // 选词菜单的"查找替换"项 (对齐原版 CodeView.onCreateActionMode → onSearchReplaceAction):
    // 菜单不带选中文本, 从聚焦编辑器的选区取; 顺序对齐原版 clearFocus → showFindReplace
    val findReplaceAction = remember {
        {
            activeEditor.value?.let { editor ->
                val tf = editor.value
                focusManager.clearFocus()
                keyboardState.showFindReplace(tf.text.substring(tf.selection.min, tf.selection.max))
            }
            Unit
        }
    }
    // 仅本屏注册, 离屏自动注销: 别处长按选词不会多出这一项
    TextToolbarFindReplaceEffect(findReplaceAction)
    // 对齐原版 BookSourceEditActivity 的 onBackPressedDispatcher → keyboardTool.tryConsumeBack():
    // 键盘已收起而查找面板仍开时, 返回键先收面板并清查找态, 不退出页面
    AppBackHandler(enabled = keyboardState.canConsumeBack) {
        keyboardState.tryConsumeBack { searchHighlight.clear() }
    }
    // 根节点持焦: 进入即请求焦点 (无字段持焦时键盘事件会被焦点系统直接丢弃, ESC 无响应;
    // 聚焦路径含根 handleBackKey, 持焦后 ESC/快捷键立即可用, 对照调试页进入聚焦的做法)
    val rootFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocusRequester.requestFocus() } }
    LaunchedEffect(requestFocusSignal) {
        requestFocusSignal.collect { runCatching { rootFocusRequester.requestFocus() } }
    }
    // 屏幕级文本变化观察器 (对齐 rememberCodeEditorState 的观察语义): 本屏编辑器经 fieldEditors
    // 普通 map 创建 (editorOf 非 @Composable, 不走 rememberCodeEditorState), 若只挂字段级
    // 观察器, LazyColumn 回收离屏字段时观察中断 —— 而工具栏撤销/重做/辅助键插入/查找替换都以
    // 激活编辑器为目标, 字段滚出视口后编辑仍可发生, 漏同步 → entity.value 不更新 → 保存丢修改。
    // 观察器挂屏幕层只盯激活编辑器 (唯一可被编辑的编辑器): 文本变化 → onChanged → entity 同步。
    // drop(1) 跳过初始发射 (首次无激活编辑器 / 聚焦瞬间的幂等回写), 对齐旧 onChanged 只在
    // 变化后触发的语义。
    LaunchedEffect(activeEditor) {
        snapshotFlow { activeEditor.value?.let { it.textFieldState.text.toString() } }
            .drop(1)
            .collect {
                activeEditor.value?.let { editor -> editor.onChanged?.invoke(editor.value) }
            }
    }
    Column(
        modifier
            .fillMaxSize()
            // 底部避让唯一来源: 两者同链取 max(ime, 导航条), 不叠加 (对齐原版语义)
            .imePadding()
            .navigationBarsPadding()
            .focusRequester(rootFocusRequester)
            // 桌面端 Ctrl+Z/Y 撤销/重做由新版 BasicTextField 原生处理 (KeyCommand.UNDO/REDO →
            // 同一个 undoState), 无需手写按键路由
            // 无字段持焦时根节点自己持焦, 保证键盘事件可达 (对照 ReplaceEditScreen)
            .focusable()
    ) {
        AppTitleBar(
            title = stringResource(Res.string.edit_book_source),
            onBack = callbacks.onBack,
            actions = { EditActions(state, callbacks) },
        )
        HeaderRow1(state, callbacks)
        HeaderRow2(state, callbacks)
        TabBar(state, callbacks)
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(rememberColor("bg_divider_line"))
        )
        // onEditorActive 逻辑只依赖稳定引用 (remember 的 activeEditor/searchHighlight),
        // remember 固定 lambda 实例: 聚焦切换触发的整屏重组不再新建 lambda 引用,
        // EditFields 的 item 调用点可跳过重组 (对齐 onCodeViewFocus 语义)
        val stableOnEditorActive: (CodeEditorState) -> Unit = remember {
            { editor ->
                if (activeEditor.value != editor) {
                    // 对齐原版 onCodeViewFocus: 切换到别的字段时清空上一字段的查找高亮
                    searchHighlight.clear()
                    activeEditor.value = editor
                }
            }
        }
        EditFields(
            state = state,
            editEntities = editEntities,
            fieldEditors = fieldEditors,
            activeEditorState = activeEditor,
            searchHighlight = searchHighlight,
            onFieldFocus = onFieldFocus,
            onEditorActive = stableOnEditorActive,
            modifier = Modifier.weight(1f),
        )
        KeyboardToolbar(
            state = keyboardState,
            onSendText = { activeEditor.value?.insertAtCursor(it) },
            onUndo = { activeEditor.value?.undo() },
            onRedo = { activeEditor.value?.redo() },
            onShowConfig = onShowKeyboardConfig,
            target = {
                activeEditor.value?.let {
                    CodeEditorSearchTarget(it, searchHighlight) { focusManager.clearFocus() }
                }
            },
        )
    }
}

/**
 * 顶部表单状态 (mutable, 宿主持有实例直接赋值)。
 *
 * 对照 app 端 `BookSourceEditActivity` 的 10 个 `var X by mutableStateOf/Y` 字段:
 * - `bookSourceTypeIndex` / `enabled` / `enabledCookieJar` / `enableDangerousApi` /
 *   `enabledExplore` / `enabledReview` / `exploreStyleIndex` / `exploreColsIndex`
 *   由 `upSourceView(bookSource)` 同步写回
 * - `currentTab` 由 TabBar 点击切换
 * - `sourceVersion` 由 `upSourceView` 自增, 驱动 [EditFields] 整体重建 (对齐原 `private set`
 *   语义: 仅宿主端自增, shared 端只读)
 */
@Stable
class BookSourceEditState {
    var bookSourceTypeIndex by mutableIntStateOf(0)
    var enabled by mutableStateOf(false)
    var enabledCookieJar by mutableStateOf(false)
    var enableDangerousApi by mutableStateOf(false)
    var enabledExplore by mutableStateOf(false)
    var enabledReview by mutableStateOf(false)
    var exploreStyleIndex by mutableIntStateOf(0)
    var exploreColsIndex by mutableIntStateOf(0)
    var currentTab by mutableIntStateOf(0)

    /** upSourceView 重建实体列表后自增, 驱动表单区整体重建 */
    var sourceVersion by mutableIntStateOf(0)
}

/**
 * 事件回调集合。宿主端用 `remember { BookSourceEditCallbacks(...) }` 持有稳定实例,
 * 避免 lambda 重组; 不用的回调用默认空实现。
 *
 * 对照 app 端 `BookSourceEditActivity` 的菜单动作方法 (`saveSource`/`debugSource`/`login`/...)
 * 与 header 状态变更 (`bookSourceTypeIndex = it` / `enabled = it` / ...)。
 */
@Stable
data class BookSourceEditCallbacks(
    val onBack: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onDebug: () -> Unit = {},
    val onLogin: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onClearCookie: () -> Unit = {},
    val onCopySource: () -> Unit = {},
    val onPasteSource: () -> Unit = {},
    val onAutoIndent: () -> Unit = {},
    val onSetSourceVariable: () -> Unit = {},
    val onShareSourceStr: () -> Unit = {},
    val onHelp: (String) -> Unit = {},
    /** 当前源是否有登录入口 (对齐 View 版 onMenuOpened 每次展开计算) */
    val hasLogin: () -> Boolean = { false },
    val onBookSourceTypeChange: (Int) -> Unit = {},
    val onEnabledChange: (Boolean) -> Unit = {},
    val onEnabledCookieJarChange: (Boolean) -> Unit = {},
    val onEnableDangerousApiClick: (Boolean) -> Unit = {},
    val onEnabledReviewChange: (Boolean) -> Unit = {},
    val onEnabledExploreChange: (Boolean) -> Unit = {},
    val onExploreStyleChange: (Int) -> Unit = {},
    val onExploreColsChange: (Int) -> Unit = {},
    val onTabChange: (Int) -> Unit = {},
)

// ---- 私有 Composable (对照 app 端 BookSourceEditScreen.kt 同名函数) ----

/** Tab 标题 key 列表 (对齐 app 端 `tabTitles = listOf(R.string.source_tab_*)`) */
private val tabTitles = listOf(
    "source_tab_base",
    "source_tab_search",
    "source_tab_find",
    "source_tab_info",
    "source_tab_toc",
    "source_tab_content",
    "source_tab_review",
)

@Composable
private fun EditActions(state: BookSourceEditState, callbacks: BookSourceEditCallbacks) {
    val colors = AppTheme.colors
    IconButton(onClick = callbacks.onSave) {
        Icon(
            painter = painterResource(Res.drawable.ic_save),
            contentDescription = stringResource(Res.string.action_save),
            tint = colors.primaryText,
        )
    }
    IconButton(onClick = callbacks.onDebug) {
        Icon(
            painter = painterResource(Res.drawable.ic_bug_report),
            contentDescription = stringResource(Res.string.debug_source),
            tint = colors.primaryText,
        )
    }
    OverflowMenu { dismiss ->
        // 对齐 View 版 onMenuOpened 的登录项可见性: 每次展开时计算 (DropdownMenu content
        // 在 expanded 时新组合, remember 无 key 等价每次展开重新求值)
        val hasLogin = remember { callbacks.hasLogin() }
        // 「帮助」二级菜单: 同一 Popup 内用 if/else 切换内容(对照 BookshelfManageScreen),
        // 避免嵌套 DropdownMenu/Popup 导致桌面端卡死; 菜单关闭随 Popup 释放状态, 下次打开回到一级
        var showHelpSubmenu by remember { mutableStateOf(false) }
        if (showHelpSubmenu) {
            MenuItem(stringResource(Res.string.book_source_tutorial)) {
                dismiss(); callbacks.onHelp("ruleHelp")
            }
            MenuItem(stringResource(Res.string.js_tutorial)) {
                dismiss(); callbacks.onHelp("jsHelp")
            }
            MenuItem(stringResource(Res.string.regex_tutorial)) {
                dismiss(); callbacks.onHelp("regexHelp")
            }
        } else {
            if (hasLogin) {
                MenuItem(stringResource(Res.string.login)) { dismiss(); callbacks.onLogin() }
            }
            MenuItem(stringResource(Res.string.search)) { dismiss(); callbacks.onSearch() }
            MenuItem(stringResource(Res.string.cookie)) { dismiss(); callbacks.onClearCookie() }
            MenuItem(stringResource(Res.string.copy_source)) { dismiss(); callbacks.onCopySource() }
            MenuItem(stringResource(Res.string.paste_source)) { dismiss(); callbacks.onPasteSource() }
            MenuItem(stringResource(Res.string.auto_indent)) { dismiss(); callbacks.onAutoIndent() }
            MenuItem(stringResource(Res.string.set_source_variable)) { dismiss(); callbacks.onSetSourceVariable() }
            MenuItem(stringResource(Res.string.str_share)) { dismiss(); callbacks.onShareSourceStr() }
            MenuItem(stringResource(Res.string.help)) { showHelpSubmenu = true }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick) {
        Text(text, color = AppTheme.colors.primaryText)
    }
}

@Composable
private fun HeaderRow1(state: BookSourceEditState, callbacks: BookSourceEditCallbacks) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.book_type),
                color = colors.primaryText,
                modifier = Modifier.padding(end = 8.dp),
            )
            DropdownBox(
                options = stringArrayResource(Res.array.book_type),
                selectedIndex = state.bookSourceTypeIndex,
            ) { callbacks.onBookSourceTypeChange(it) }
        }
        HeaderCheckBox("is_enable", state.enabled) { callbacks.onEnabledChange(it) }
        HeaderCheckBox("auto_save_cookie", state.enabledCookieJar) {
            callbacks.onEnabledCookieJarChange(it)
        }
        HeaderCheckBox("enable_dangerous_api", state.enableDangerousApi) {
            callbacks.onEnableDangerousApiClick(it)
        }
        HeaderCheckBox("enable_review", state.enabledReview) {
            callbacks.onEnabledReviewChange(it)
        }
    }
}

@Composable
private fun HeaderRow2(state: BookSourceEditState, callbacks: BookSourceEditCallbacks) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCheckBox("discovery", state.enabledExplore) {
            callbacks.onEnabledExploreChange(it)
        }
        Row(
            Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.explore_style),
                color = colors.primaryText,
                modifier = Modifier.padding(end = 8.dp),
            )
            DropdownBox(
                options = stringArrayResource(Res.array.explore_item_style),
                selectedIndex = state.exploreStyleIndex,
            ) { callbacks.onExploreStyleChange(it) }
        }
        Row(
            Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.explore_cols),
                color = colors.primaryText,
                modifier = Modifier.padding(end = 8.dp),
            )
            DropdownBox(
                options = remember { (0..6).map { it.toString() } },
                selectedIndex = state.exploreColsIndex,
            ) { callbacks.onExploreColsChange(it) }
        }
    }
}

@Composable
private fun HeaderCheckBox(textKey: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .clickable { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppCheckbox(checked = checked, onCheckedChange = null)
        Text(
            rememberString(textKey),
            color = AppTheme.colors.primaryText,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun TabBar(state: BookSourceEditState, callbacks: BookSourceEditCallbacks) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.viewHeightLarge)
    ) {
        tabTitles.forEachIndexed { i, key ->
            val selected = state.currentTab == i
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { callbacks.onTabChange(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    rememberString(key),
                    color = if (selected) colors.accent else colors.secondaryText,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
                if (selected) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(colors.accent)
                    )
                }
            }
        }
    }
}

@Composable
private fun EditFields(
    state: BookSourceEditState,
    editEntities: (Int) -> List<EditEntity>,
    fieldEditors: MutableMap<String, CodeEditorState>,
    activeEditorState: State<CodeEditorState?>,
    searchHighlight: CodeSearchHighlightState,
    onFieldFocus: (String, EditEntity) -> Unit,
    onEditorActive: (CodeEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 虚拟化滚动: 只组合可见字段, 长字段 (数百行规则文本) 滚动到时才布局/测量,
    // 对齐原版 RecyclerView 定高内滚的惰性行为 (原版每字段 item 高度即内容高度)
    val listState = rememberLazyListState()
    val tab = state.currentTab
    val version = state.sourceVersion
    // 原版 BookSourceEditAdapter 对每个字段三连 addLegado/addJs/addJsonPattern, 不分组
    val syntax = rememberFullCodeSyntax()
    // 回调/取数 lambda 引用稳定化 (State 捕获模式): 宿主重组传新 lambda 时,
    // LazyColumn item 调用点的捕获引用不变, 可见字段不因父级重组而全部重组合
    val latestEditEntities = rememberUpdatedState(editEntities)
    val stableEditEntities = remember { { tab: Int -> latestEditEntities.value(tab) } }
    val latestOnFieldFocus = rememberUpdatedState(onFieldFocus)
    val stableOnFieldFocus = remember {
        { id: String, entity: EditEntity -> latestOnFieldFocus.value(id, entity) }
    }
    val latestOnEditorActive = rememberUpdatedState(onEditorActive)
    val stableOnEditorActive = remember {
        { editor: CodeEditorState -> latestOnEditorActive.value(editor) }
    }
    // 切 tab / 重建数据时回顶 (对齐 View 版 scrollToPosition(0))
    LaunchedEffect(tab, version) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
    ) {
        // item key = 字段 key: 对齐原 key(version, tab) 整体重建语义 —— 版本号变化时
        // 列表内容整体替换, 编辑器状态由 fieldEditors 容器按版本重建承载 (见 Route);
        // 同 key item 复用组合槽, 值同步走 editorOf 的 setText 检查
        val entities = stableEditEntities(tab)
        items(entities, key = { it.key }) { entity ->
            when (entity.viewType) {
                EditEntity.ViewType.spinner -> SpinnerField(entity)
                else -> {
                    val fieldId = "$tab/${entity.key}"
                    val editor = editorOf(fieldId, entity, fieldEditors)
                    CodeField(
                        // fieldId 带 tab 前缀: 同一 key 在多个 tab 重复出现 (如 name / bookList)
                        fieldId = fieldId,
                        entity = entity,
                        editor = editor,
                        syntax = syntax,
                        activeState = activeEditorState,
                        searchHighlight = searchHighlight,
                        onFieldFocus = stableOnFieldFocus,
                        onEditorActive = stableOnEditorActive,
                    )
                }
            }
        }
    }
}

/** 代码输入行: 共享 [CodeTextField] (全平台一致), 聚焦时把自身编辑器登记为辅助键目标 */
@Composable
private fun CodeField(
    fieldId: String,
    entity: EditEntity,
    editor: CodeEditorState,
    syntax: CodeSyntaxScheme,
    activeState: State<CodeEditorState?>,
    searchHighlight: CodeSearchHighlightState,
    onFieldFocus: (String, EditEntity) -> Unit,
    onEditorActive: (CodeEditorState) -> Unit,
) {
    // 聚焦判定下沉: activeState 引用在调用点不读取, 派生值 (=== 引用比较) 变化才重组
    // 本字段 —— 聚焦切换只重组新旧两字段, 其余可见字段整体跳过
    val isActive by remember(editor, activeState) {
        derivedStateOf { activeState.value === editor }
    }
    // 查找面板防抖协程作用域: 防抖任务挂在字段 scope, 随字段离开组合自动取消
    val searchRefreshScope = rememberCoroutineScope()
    // 字段最大行数 (对齐原版 BookSourceEditAdapter 的 editText.maxLines = sourceEditMaxLine):
    // 默认 Int.MAX_VALUE 不限制 (字段高度 = 内容高度); 用户设置后大字段按配置行数截断、
    // 超出部分字段内部滚动。进入页面时读取一次 (配置变化下次进入生效, 同原版)。
    val editMaxLine = remember { AppConfigProviders.get().sourceEditMaxLine }
    // 回调 lambda 经 rememberUpdatedState 稳定: 父级重组传新 lambda 引用时本字段不重组
    val latestOnEditorActive by rememberUpdatedState(onEditorActive)
    val latestOnFieldFocus by rememberUpdatedState(onFieldFocus)
    // 统一同步点 (对齐原版 CodeView 的 TextWatcher): 输入/undo/redo/辅助键插入/查找替换/
    // 格式化等一切修改路径都经 editor.onChanged 回调, 此处单向同步 entity.value 与查找高亮
    // 刷新, 不存在绕过路径 (旧实现只在 BasicTextField 输入回调里写 entity.value,
    // undo/替换等直改 editor 的路径分叉 → 保存丢修改 + editorOf 回滚光标跳末尾)。
    editor.onChanged = { newValue ->
        entity.value = newValue.text
        // 查找面板开着且本字段激活时, 防抖刷新匹配高亮 (对齐原版 afterTextChanged 的
        // updateSearchHighlightIncremental; 替换等由面板触发的编辑走 target 内部重算)
        if (isActive && searchHighlight.keyword.isNotEmpty()) {
            searchHighlight.refreshDebounced(newValue.text, searchRefreshScope)
        }
    }
    CodeTextField(
        value = editor.textFieldState,
        inputTransformation = editor.inputTransformation,
        syntax = syntax,
        label = rememberString(entity.hint),
        // 对照原版 CodeView: 书源编辑条目开行号 (isLineNumberEnabled=true)
        showLineNumbers = true,
        maxLines = editMaxLine,
        // 对照原版 CodeView: EditText 默认 16sp (原版未设 textSize)
        fontSize = 16.sp,
        // 查找高亮只叠加在聚焦字段上 (原版查找作用于 lastActiveCodeView)
        searchHighlight = if (isActive) searchHighlight else null,
        modifier = Modifier
            .fillMaxWidth()
            // 外围间距由 CodeTextField 组件统一 (左右下各 4dp), 顶部不再额外留白
            .onFocusChanged {
                if (it.isFocused) {
                    latestOnEditorActive(editor)
                    latestOnFieldFocus(fieldId, entity)
                }
            },
    )
}

/**
 * 字段的编辑器状态: 从 [fieldEditors] 容器按 fieldId 取, 组合位置与滚动回收无关
 * (容器由宿主按 sourceVersion 重建: 粘贴源/重新加载后旧编辑器与撤销历史一并丢弃, 对齐原版
 * upSourceView 重建实体; tab 切换不重建容器, 切回时编辑器状态保留, 对齐原版 View 回收复用)。
 * 容器为普通 map 不参与重组, 编辑器内部 value 是 Compose State, 单字段写入只重组该字段。
 *
 * 编辑器是唯一真源: entity.value 只经 editor.onChanged 单向同步 (见 CodeField), 本函数
 * 不再组合期回写 setText —— 旧实现以 entity.value 为准做 `text != value → setText` 回滚,
 * 与 adjustInput/undo/替换等调整路径分叉, 是"回车光标跳末尾/undo 被吞"的根因; 外部整体
 * 改写 (粘贴源/重载) 由 sourceVersion → fieldEditors 容器重建覆盖, 无需此回滚。
 */
@Composable
private fun editorOf(
    fieldId: String,
    entity: EditEntity,
    fieldEditors: MutableMap<String, CodeEditorState>,
): CodeEditorState {
    return fieldEditors.getOrPut(fieldId) { CodeEditorState(entity.value.orEmpty()) }
}

/**
 * 书源编辑字段的查找替换目标 (共享件, 见 code/CodeEditorSearchTarget.kt;
 * 原版 CodeView 自带 find/replace/replaceAll, 下沉后 BookSourceEdit/CodeDialog/JsEdit 共用)。
 */

@Composable
private fun SpinnerField(entity: EditEntity) {
    val colors = AppTheme.colors
    val selections = entity.selections.orEmpty()
    var selectedIndex by remember(entity.value) {
        mutableIntStateOf(selections.indexOfFirst { it.second == entity.value }.coerceAtLeast(0))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.viewHeightXl)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rememberString(entity.hint),
            color = colors.primaryText,
            modifier = Modifier.padding(end = 8.dp),
        )
        DropdownBox(
            // 选项名可能是资源 key (对照 app 端 getString(R.string.text_default)),
            // 非 key 的字面值 (FULL/TEXT/SINGLE) 由 rememberString 原样返回
            options = selections.map { rememberString(it.first) },
            selectedIndex = selectedIndex,
        ) { i ->
            selectedIndex = i
            entity.value = selections.getOrNull(i)?.second
        }
    }
}

/** 复刻 AppCompatSpinner 语义: 当前值 + 下拉箭头, 点开 AppDropdownMenu 单选 */
@Composable
private fun DropdownBox(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clickable { expanded = true }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                options.getOrElse(selectedIndex) { "" },
                color = colors.primaryText,
                fontSize = 14.sp,
            )
            Icon(
                painter = painterResource(Res.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = colors.secondaryText,
            )
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(onClick = { expanded = false; onSelect(i) }) {
                    Text(label, color = colors.primaryText)
                }
            }
        }
    }
}
