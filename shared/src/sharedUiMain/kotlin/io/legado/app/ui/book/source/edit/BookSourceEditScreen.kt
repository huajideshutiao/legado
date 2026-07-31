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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
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
import legado.shared.generated.resources.explore_item_style
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
 * - CodeView (Android 专属语法高亮控件) → [codeEditorSlot] 注入:
 *   app 端用 `AndroidView { createEditField(ctx, entity) }` (CodeView) 实现,
 *   desktop 端用普通 `OutlinedTextField` 实现
 * - KeyboardToolbar (依赖 CodeView + appDb.keyboardAssistsDao) → [bottomBar] 注入:
 *   app 端用 `KeyboardToolbar` 实现, desktop 端可省略或自实现
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
 * ## L3 不可下沉项 (通过 slot 注入, 由 app 端实现)
 *
 * - **KeyboardToolbar**: 依赖 `CodeView` (Android 专属语法高亮控件) +
 *   `appDb.keyboardAssistsDao` (Android Room DAO), 通过 [bottomBar] slot 注入
 * - **CodeView 渲染**: `AndroidView` + `CodeView` 是 Android 专属控件,
 *   通过 [codeEditorSlot] slot 注入, app 端用 CodeView 实现, desktop 端用 TextField 实现
 * - **KeyboardAssistsConfig**: `DialogFragment` 是 Android 专属, 通过 [bottomBar] 内的
 *   `onShowConfig` 回调由 app 端 `showDialogFragment<KeyboardAssistsConfig>()` 实现
 *
 * @param state           顶部表单状态 (书源类型/各启用开关/tab/版本号)
 * @param callbacks       事件回调 (菜单动作/状态变更)
 * @param editEntities    按 tab 返回当前页的 [EditEntity] 列表 (宿主持有可变列表, 供 getSource 读取)
 * @param codeEditorSlot  CodeView 注入槽: `(entity, modifier) -> Unit`,
 *                        app 端用 `AndroidView { createEditField(ctx, entity) }`,
 *                        desktop 端用 `OutlinedTextField`
 * @param bottomBar       底部栏注入槽: app 端用 `KeyboardToolbar`, desktop 端可省略
 * @param modifier        外部 modifier (app 端可附加 `windowInsetsPadding(ime ∪ navigationBars)`)
 */
@Composable
fun BookSourceEditScreen(
    state: BookSourceEditState,
    callbacks: BookSourceEditCallbacks,
    editEntities: (Int) -> List<EditEntity>,
    codeEditorSlot: @Composable (EditEntity, Modifier) -> Unit,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        EditFields(state, editEntities, codeEditorSlot, Modifier.weight(1f))
        bottomBar()
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
        // 「帮助」二级菜单 (对齐 app 端 source_edit.xml 的嵌套 <menu>)
        var showHelpSubmenu by remember { mutableStateOf(false) }
        MenuItem(stringResource(Res.string.help)) { showHelpSubmenu = true }
        Box {
            AppDropdownMenu(
                expanded = showHelpSubmenu,
                onDismissRequest = { showHelpSubmenu = false },
                modifier = Modifier.offset(x = 200.dp), // 偏移到父菜单右侧
            ) {
                MenuItem(stringResource(Res.string.book_source_tutorial)) {
                    dismiss(); showHelpSubmenu = false; callbacks.onHelp("ruleHelp")
                }
                MenuItem(stringResource(Res.string.js_tutorial)) {
                    dismiss(); showHelpSubmenu = false; callbacks.onHelp("jsHelp")
                }
                MenuItem(stringResource(Res.string.regex_tutorial)) {
                    dismiss(); showHelpSubmenu = false; callbacks.onHelp("regexHelp")
                }
            }
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
            .height(40.dp)
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
    codeEditorSlot: @Composable (EditEntity, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val tab = state.currentTab
    val version = state.sourceVersion
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
                        else -> CodeField(entity, codeEditorSlot)
                    }
                }
            }
        }
    }
}

/** CodeView 输入行: 通过 [codeEditorSlot] 注入 (app 端 AndroidView+CodeView, desktop 端 TextField) */
@Composable
private fun CodeField(
    entity: EditEntity,
    codeEditorSlot: @Composable (EditEntity, Modifier) -> Unit,
) {
    codeEditorSlot(entity, Modifier.fillMaxWidth())
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
            .height(48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entity.hint,
            color = colors.primaryText,
            modifier = Modifier.padding(end = 8.dp),
        )
        DropdownBox(
            options = selections.map { it.first },
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
