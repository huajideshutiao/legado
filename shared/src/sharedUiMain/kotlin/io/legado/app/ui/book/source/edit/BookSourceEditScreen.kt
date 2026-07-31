package io.legado.app.ui.book.source.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.code.CodeEditorState
import io.legado.app.ui.compose.component.code.CodeSyntaxScheme
import io.legado.app.ui.compose.component.code.CodeTextField
import io.legado.app.ui.compose.component.code.KeyboardToolbar
import io.legado.app.ui.compose.component.code.KeyboardToolbarState
import io.legado.app.ui.compose.component.code.KeyboardToolbarTarget
import io.legado.app.ui.compose.component.code.insertAtCursor
import io.legado.app.ui.compose.component.code.rememberCodeEditorState
import io.legado.app.ui.compose.component.code.rememberFullCodeSyntax
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.widget.text.EditEntity
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
 * 书源编辑 Screen (KMP 版, 替代 app 端 `io.legado.app.ui.book.source.edit.BookSourceEditScreen`)。
 *
 * 下沉改动 (对照 app 端原版 11 个 @Composable):
 * - 去掉对 `BookSourceEditActivity` 的直接依赖, 改为通过 [BookSourceEditState] +
 *   [BookSourceEditCallbacks] + [editEntities] 传入状态与回调, 解耦 Composable 与 Android Activity
 * - 字符串资源 `stringResource(R.string.xxx)` → `stringResource(Res.string.xxx)` (key-based, 跨平台)
 * - 图标资源 `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")` (key-based, 跨平台)
 * - 颜色资源 `colorResource(R.color.xxx)` → `rememberColor("xxx")` (key-based, 跨平台)
 * - 数组资源 `stringArrayResource(R.array.xxx)` → `stringArrayResource(Res.array.xxx)` (key-based, 跨平台)
 * - CodeView (Android 专属语法高亮控件) → 共享 [CodeTextField] (等宽 + 语法高亮 VisualTransformation),
 *   全平台同一实现, 无平台注入
 * - KeyboardToolbar (原依赖 CodeView + appDb.keyboardAssistsDao) → 共享
 *   [io.legado.app.ui.compose.component.code.KeyboardToolbar], 内部直连 keyboardAssistsDao
 * - 去掉 `WindowInsets.ime/navigationBars` (Android 专属 inset 概念, 见 ReplaceEditScreen
 *   同款决策), 宿主端在包装层通过 [modifier] 传入 `windowInsetsPadding`
 *
 * ## ResourceProvider key 需求清单 (供 ResourceProvider.kt 维护者补全)
 *
 * ### String key (string)
 * - `edit_book_source`       顶部标题
 * - `action_save`            保存按钮 contentDescription
 * - `debug_source`           调试按钮 contentDescription
 * - `login`                  登录菜单项
 * - `search`                 搜索菜单项
 * - `cookie`                 清除 Cookie 菜单项
 * - `copy_source`            复制源菜单项
 * - `paste_source`           粘贴源菜单项
 * - `auto_indent`            自动缩进菜单项
 * - `set_source_variable`    设置源变量菜单项
 * - `str_share`              分享源字符串菜单项
 * - `book_type`              书源类型标签
 * - `is_enable`              启用复选框
 * - `auto_save_cookie`       CookieJar 复选框
 * - `enable_dangerous_api`   危险 API 复选框
 * - `enable_review`          段评复选框
 * - `discovery`              发现复选框
 * - `explore_style`          发现样式标签
 * - `explore_cols`           发现列数标签
 * - `source_tab_base`        Tab: 基本
 * - `source_tab_search`      Tab: 搜索
 * - `source_tab_find`        Tab: 发现
 * - `source_tab_info`        Tab: 详情
 * - `source_tab_toc`         Tab: 目录
 * - `source_tab_content`     Tab: 正文
 * - `source_tab_review`      Tab: 段评
 *
 * ### Painter key (drawable)
 * - `ic_save`                保存图标
 * - `ic_bug_report`          调试图标
 * - `ic_arrow_drop_down`     下拉箭头
 *
 * ### Color key (color)
 * - `bg_divider_line`        Tab 下方分割线颜色
 *
 * ### StringArray key (string-array)
 * - `book_type`              书源类型选项 (文本/音频/图片/文件/视频/RSS)
 * - `explore_item_style`     发现样式选项 (默认/视频)
 *
 * ## L3 不可下沉项 (通过回调注入, 由宿主实现)
 *
 * - **KeyboardAssistsConfig**: 辅助键配置弹窗是平台专属 (app 端 DialogFragment),
 *   通过 [onShowKeyboardConfig] 回调注入
 *
 * @param state           顶部表单状态 (书源类型/各启用开关/tab/版本号)
 * @param callbacks       事件回调 (菜单动作/状态变更)
 * @param editEntities    按 tab 返回当前页的 [EditEntity] 列表 (宿主持有可变列表, 供 getSource 读取)
 * @param onFieldFocus    字段获得焦点回调 `(fieldId, entity)`, 供宿主定位自动缩进目标
 * @param onFieldTextChange 字段文本变化回调 `(fieldId, text)`, 宿主可据此维护文本快照
 * @param fieldTextOverride 宿主外部改写字段文本时的取值 (如自动缩进), 返回 null 用实体自身值
 * @param onShowKeyboardConfig 辅助键配置入口 (app 端 `showDialogFragment<KeyboardAssistsConfig>()`)
 * @param modifier        外部 modifier (app 端可附加 `windowInsetsPadding(ime ∪ navigationBars)`)
 */
@Composable
fun BookSourceEditScreen(
    state: BookSourceEditState,
    callbacks: BookSourceEditCallbacks,
    editEntities: (Int) -> List<EditEntity>,
    modifier: Modifier = Modifier,
    onFieldFocus: (String, EditEntity) -> Unit = { _, _ -> },
    onFieldTextChange: (String, String) -> Unit = { _, _ -> },
    fieldTextOverride: (String) -> String? = { null },
    onShowKeyboardConfig: () -> Unit = {},
) {
    // 焦点字段的编辑器状态 (对照 app 端 lastActiveCodeView): 辅助键插入/撤销/重做/查找替换的目标
    var activeEditor by remember { mutableStateOf<CodeEditorState?>(null) }
    val keyboardState = remember { KeyboardToolbarState() }
    val focusManager = LocalFocusManager.current
    Column(modifier.fillMaxSize()) {
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
        EditFields(
            state = state,
            editEntities = editEntities,
            onFieldFocus = onFieldFocus,
            onFieldTextChange = onFieldTextChange,
            fieldTextOverride = fieldTextOverride,
            onEditorActive = { activeEditor = it },
            modifier = Modifier.weight(1f),
        )
        KeyboardToolbar(
            state = keyboardState,
            onSendText = { activeEditor?.insertAtCursor(it) },
            onUndo = { activeEditor?.undo() },
            onRedo = { activeEditor?.redo() },
            onShowConfig = onShowKeyboardConfig,
            target = {
                activeEditor?.let { CodeEditorSearchTarget(it) { focusManager.clearFocus() } }
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
    onFieldFocus: (String, EditEntity) -> Unit,
    onFieldTextChange: (String, String) -> Unit,
    fieldTextOverride: (String) -> String?,
    onEditorActive: (CodeEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val tab = state.currentTab
    val version = state.sourceVersion
    // 原版 BookSourceEditAdapter 对每个字段三连 addLegado/addJs/addJsonPattern, 不分组
    val syntax = rememberFullCodeSyntax()
    // 切 tab / 重建数据时回顶 (对齐 View 版 scrollToPosition(0))
    LaunchedEffect(tab, version) { scrollState.scrollTo(0) }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
    ) {
        key(version, tab) {
            editEntities(tab).forEach { entity ->
                key(entity.key) {
                    when (entity.viewType) {
                        EditEntity.ViewType.spinner -> SpinnerField(entity)
                        else -> CodeField(
                            // fieldId 带 tab 前缀: 同一 key 在多个 tab 重复出现 (如 name / bookList)
                            fieldId = "$tab/${entity.key}",
                            entity = entity,
                            syntax = syntax,
                            onFieldFocus = onFieldFocus,
                            onFieldTextChange = onFieldTextChange,
                            fieldTextOverride = fieldTextOverride,
                            onEditorActive = onEditorActive,
                        )
                    }
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
    syntax: CodeSyntaxScheme,
    onFieldFocus: (String, EditEntity) -> Unit,
    onFieldTextChange: (String, String) -> Unit,
    fieldTextOverride: (String) -> String?,
    onEditorActive: (CodeEditorState) -> Unit,
) {
    val editor = rememberCodeEditorState(entity.value.orEmpty())
    // 外部改写 (自动缩进/粘贴源) 同步回编辑器
    val override = fieldTextOverride(fieldId)
    if (override != null && override != editor.value.text) editor.setText(override)
    CodeTextField(
        value = editor.value,
        onValueChange = {
            editor.onValueChange(it)
            entity.value = it.text
            onFieldTextChange(fieldId, it.text)
        },
        syntax = syntax,
        label = rememberString(entity.hint),
        // 对照原版 CodeView: 书源编辑条目开行号 (isLineNumberEnabled=true)
        showLineNumbers = true,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (it.isFocused) {
                    onEditorActive(editor)
                    onFieldFocus(fieldId, entity)
                }
            },
    )
}

/**
 * 把 [CodeEditorState] 适配成辅助键条的查找替换目标 (对齐原 CodeView 的 find/replace/replaceAll)。
 * 匹配定位靠选区高亮 (Compose 无 BackgroundColorSpan 等价物, 选中即视觉高亮)。
 */
private class CodeEditorSearchTarget(
    private val editor: CodeEditorState,
    private val onClearFocus: () -> Unit,
) : KeyboardToolbarTarget {

    /** 最近一次 [matches] 编译出的正则, replaceAll 复用 */
    private var lastRegex: Regex? = null

    override fun clearFocus() = onClearFocus()

    override fun find(
        keyword: String,
        useRegex: Boolean,
        matchCase: Boolean,
        wholeWord: Boolean,
        forward: Boolean?,
    ) {
        val ranges = matches(keyword, useRegex, matchCase, wholeWord)
        if (ranges.isEmpty()) return
        val cursor = editor.value.selection.min
        // forward=null (输入防抖触发) 按原版当作向上定位: 光标前最后一个匹配
        val target = if (forward == true) {
            ranges.firstOrNull { it.first > cursor } ?: ranges.first()
        } else {
            ranges.lastOrNull { it.second <= cursor } ?: ranges.last()
        }
        editor.onValueChange(
            editor.value.copy(selection = TextRange(target.first, target.second))
        )
    }

    override fun replace(
        keyword: String,
        useRegex: Boolean,
        matchCase: Boolean,
        wholeWord: Boolean,
        replacement: String,
    ) {
        val selection = editor.value.selection
        val ranges = matches(keyword, useRegex, matchCase, wholeWord)
        // 当前选区正好落在某个匹配上才替换, 否则先定位 (对齐原版 needFind 分支)
        val hit = ranges.any { it.first == selection.min && it.second == selection.max }
        if (!hit || selection.collapsed) {
            find(keyword, useRegex, matchCase, wholeWord, forward = true)
            return
        }
        editor.onValueChange(editor.value.insertAtCursor(replacement))
        find(keyword, useRegex, matchCase, wholeWord, forward = true)
    }

    override fun replaceAll(replacement: String) {
        val regex = lastRegex ?: return
        val text = editor.value.text
        // lambda 形式的返回值按字面量使用, 与原版 Editable.replace 一致 (不解释 $1)
        val replaced = regex.replace(text) { replacement }
        if (replaced != text) {
            editor.onValueChange(
                editor.value.copy(
                    text = replaced,
                    selection = TextRange(editor.value.selection.min.coerceAtMost(replaced.length)),
                )
            )
        }
    }

    /** 构造匹配区间 (对齐原版 getSearchPattern: 非正则走 quote, 全词加 \b, 忽略大小写走 flag) */
    private fun matches(
        keyword: String,
        useRegex: Boolean,
        matchCase: Boolean,
        wholeWord: Boolean,
    ): List<Pair<Int, Int>> {
        if (keyword.isEmpty()) {
            lastRegex = null
            return emptyList()
        }
        var pattern = if (useRegex) keyword else Regex.escape(keyword)
        if (wholeWord) pattern = "\\b$pattern\\b"
        val regex = try {
            if (matchCase) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            null
        }
        lastRegex = regex ?: return emptyList()
        return regex.findAll(editor.value.text).map { it.range.first to it.range.last + 1 }.toList()
    }
}

@Composable
private fun SpinnerField(entity: EditEntity) {
    val colors = AppTheme.colors
    val selections = entity.selections.orEmpty()
    var selectedIndex by remember {
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
