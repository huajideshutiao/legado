package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.HelpVersion
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.LocalConfigShared
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.model.SharedJsScope
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.edit.BookSourceEditCallbacks
import io.legado.app.ui.book.source.edit.BookSourceEditScreen
import io.legado.app.ui.book.source.edit.BookSourceEditScreenModel
import io.legado.app.ui.book.source.edit.BookSourceEditState
import io.legado.app.ui.book.source.edit.BookSourceEditUiEvent
import io.legado.app.ui.book.source.edit.BookSourceEditViewModelShared
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.widget.dialog.HelpDialog
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.CodeFormatter
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.enable_dangerous_api
import legado.shared.generated.resources.enable_dangerous_api_confirm
import legado.shared.generated.resources.exit
import legado.shared.generated.resources.exit_no_save
import legado.shared.generated.resources.no
import legado.shared.generated.resources.non_null_name_url
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.yes
import org.jetbrains.compose.resources.stringResource

/**
 * AppRoute.BookSourceEdit 路由下沉入口: 桥接 [BookSourceEditScreenModel] 状态与 [BookSourceEditScreen] 渲染。
 *
 * sourceUrl 取自路由, dispatch Init 触发书源加载; 保存/调试/搜索通过导航器跳转。
 * 实体列表由 [BookSourceEditScreenModel.editEntities] 提供; 代码字段与键盘辅助条由 Screen
 * 内部的共享 CodeTextField / KeyboardToolbar 直接渲染, 无平台注入。
 *
 * 对照 app 端 `BookSourceEditActivity`: 所有菜单动作 (save/debug/login/search/clearCookie/
 * copySource/pasteSource/autoIndent/setSourceVariable/shareSourceStr/help) 与 header 状态变更
 * 均通过 [BookSourceEditCallbacks] 接入; 自动缩进走已下沉的 [CodeFormatter]; 平台专属行为
 * (登录弹窗/源变量弹窗/剪贴板/分享) 通过 [PlatformCapabilityProviders] 委托;
 * 危险 API 确认对话框用 shared [AppAlertDialog]。
 */
@Composable
fun BookSourceEditRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.BookSourceEdit
    val sourceUrl = route.sourceUrl

    val nonNullNameUrlMessage = stringResource(Res.string.non_null_name_url)
    val strEnableDangerousApi = stringResource(Res.string.enable_dangerous_api)
    val strEnableDangerousApiConfirm = stringResource(Res.string.enable_dangerous_api_confirm)
    val strOk = stringResource(Res.string.ok)
    val strCancel = stringResource(Res.string.cancel)
    // 退出确认对话框文案 (对照 app 端 finish() 内 alert)
    val strExit = stringResource(Res.string.exit)
    val strExitNoSave = stringResource(Res.string.exit_no_save)
    val strYes = stringResource(Res.string.yes)
    val strNo = stringResource(Res.string.no)

    // Compose 剪贴板管理器 (KMP 可用, 替代 app 端 getClipText; 对照 ReplaceEditRoute)
    val clipboardManager = LocalClipboardManager.current
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        BookSourceEditScreenModel { scope ->
            BookSourceEditViewModelShared(
                scope = scope,
                // 剪贴板: Compose LocalClipboardManager (替代 app 端 getClipText)
                clipTextProvider = { clipboardManager.getText()?.text },
                // SourceConfig 已下沉 commonMain, 直接调用 (替代 app 端 SourceConfig.removeSource)
                sourceConfigRemover = { url -> SourceConfig.removeSource(url) },
                // CookieStoreProviders 已下沉 commonMain (替代 app 端 CookieStore.removeCookie)
                cookieRemover = { url -> CookieStoreProviders.get()?.removeCookie(url) },
                nonNullNameUrlMessage = { nonNullNameUrlMessage },
            )
        }
    }

    val editState = remember { BookSourceEditState() }

    // 字段文本快照: EditEntity.value 不是 Compose State, 自动缩进这类外部改写要靠它触发重组
    val fieldTexts = remember(editState.sourceVersion) { mutableStateMapOf<String, String>() }
    // 最后获得焦点的字段 (对照 app 端 lastActiveCodeView), 自动缩进作用于它
    val activeField =
        remember(editState.sourceVersion) { mutableStateOf<Pair<String, EditEntity>?>(null) }

    // 对话框状态 (对照 app 端 Activity 内 showHelp / alert 调用)
    var helpFileName by remember { mutableStateOf<String?>(null) }
    var showDangerousApiConfirm by remember { mutableStateOf(false) }
    // 退出确认对话框 (对照 app 端 finish() 内 alert: source 变更未保存时询问)
    var showExitConfirm by remember { mutableStateOf(false) }

    // 初始化: 加载书源后填充 header 表单 (对照 app 端 upSourceView header 部分)
    LaunchedEffect(sourceUrl) {
        // 空 url = 新建 (对照 app 端 intent 无 sourceUrl extra 时 getStringExtra 返回 null)
        screenModel.dispatch(BookSourceEditUiEvent.Init(sourceUrl.ifBlank { null }) {
            screenModel.bookSource?.let { bs ->
                applySourceToEditState(bs, editState)
            }
        })
    }

    // 首次打开规则帮助引导 (对照 app 端 onPostCreate: !LocalConfig.ruleHelpVersionIsLast)
    LaunchedEffect(Unit) {
        val prefs = PreferenceProviders.get()
        val isLastHelp = LocalConfigShared.isLastVersion(
            lastVersion = HelpVersion.ruleHelp,
            versionKey = LocalConfigKeys.ruleHelpVersion,
            getInt = prefs::getInt,
            getBoolean = prefs::getBoolean,
            putInt = prefs::putInt,
        )
        if (!isLastHelp) helpFileName = "ruleHelp"
    }

    // 退出请求 (对照 app 端 finish() 覆写): source 变更未保存时弹确认, 未变更直接退出。
    // 标题栏箭头 / 系统返回键 / 桌面 ESC 三条退出路径共用, 与原版 finish() 覆盖范围一致。
    val requestExit: () -> Unit = remember(navigator, screenModel) {
        {
            val source = screenModel.getSource(editState)
            val original = screenModel.bookSource ?: BookSource()
            if (!source.equal(original)) {
                showExitConfirm = true
            } else {
                navigator.pop()
            }
        }
    }

    // 确认框已弹出时不再重复拦截; 非栈顶时也不拦截 (栈内页面全程留在 Composition)
    val backStack by navigator.backStack.collectAsState()
    val isTopEntry = backStack.lastOrNull()?.id == entry.id
    AppBackHandler(enabled = isTopEntry && !showExitConfirm, onBack = requestExit)

    val callbacks = remember(navigator, screenModel, fieldTexts, activeField) {
        BookSourceEditCallbacks(
            onBack = requestExit,
            onSave = {
                // 对照 app 端 saveSource: setResult(RESULT_OK, origin) + finish()
                val source = screenModel.getSource(editState)
                screenModel.dispatch(BookSourceEditUiEvent.Save(source) {
                    navigator.pop(RouteResultPayload.BookSourceEdit(source.bookSourceUrl))
                })
            },
            onDebug = {
                // 对照 app 端 debugSource: viewModel.save(getSource()) { startActivity<BookSourceDebugActivity> }
                val source = screenModel.getSource(editState)
                screenModel.dispatch(BookSourceEditUiEvent.Save(source) { saved ->
                    navigator.push(AppRoute.BookSourceDebug(saved.bookSourceUrl))
                })
            },
            onLogin = {
                // 对照 app 端 login: viewModel.save(getSource()) { source.showLoginDialog(this) }
                val source = screenModel.getSource(editState)
                screenModel.dispatch(BookSourceEditUiEvent.Save(source) { saved ->
                    PlatformCapabilityProviders.getOrNull()?.showBookSourceLogin(saved)
                })
            },
            onSearch = {
                // 对照 app 端 searchSource: viewModel.save(getSource()) { startActivity<SearchActivity> { putExtra("searchScope", ...) } }
                val source = screenModel.getSource(editState)
                screenModel.dispatch(BookSourceEditUiEvent.Save(source) { saved ->
                    SearchScope(saved).save()
                    navigator.push(AppRoute.Search())
                })
            },
            onClearCookie = {
                // 对照 app 端 clearCookie: viewModel.clearCookie(getSource().bookSourceUrl)
                val source = screenModel.getSource(editState)
                screenModel.dispatch(BookSourceEditUiEvent.ClearCookie(source.bookSourceUrl))
            },
            onCopySource = {
                // 对照 app 端 copySource: sendToClip(GSON.toJson(getSource()))
                val source = screenModel.getSource(editState)
                PlatformCapabilityProviders.getOrNull()?.copyToClipboard(GSON.toJson(source))
            },
            onPasteSource = {
                // 对照 app 端 pasteSource: viewModel.pasteSource { upSourceView(it) }
                // ScreenModel 内部已调 upSourceView 重建实体列表, 这里同步 header 表单
                screenModel.dispatch(BookSourceEditUiEvent.PasteSource { source ->
                    applySourceToEditState(source, editState)
                })
            },
            onAutoIndent = {
                // 对照 app 端 autoIndent: getActiveCodeView()?.reFormat(), 作用于最后获得焦点的字段
                activeField.value?.let { (fieldId, entity) ->
                    val old = fieldTexts[fieldId] ?: entity.value.orEmpty()
                    val formatted = CodeFormatter.reFormat(old)
                    if (formatted != old) {
                        fieldTexts[fieldId] = formatted
                        entity.value = formatted
                    }
                }
            },
            onSetSourceVariable = {
                // 对照 app 端 setSourceVariable: viewModel.save(getSource()) { source.showSourceVariableDialog(this) }
                val source = screenModel.getSource(editState)
                screenModel.dispatch(BookSourceEditUiEvent.Save(source) { saved ->
                    PlatformCapabilityProviders.getOrNull()?.showBookSourceVariableDialog(saved)
                })
            },
            onShareSourceStr = {
                // 对照 app 端 shareSourceStr: share(GSON.toJson(getSource()))
                val source = screenModel.getSource(editState)
                PlatformCapabilityProviders.getOrNull()?.shareText(GSON.toJson(source))
            },
            onHelp = { fileName ->
                // 对照 app 端 help: showHelp(fileName)
                helpFileName = fileName
            },
            hasLogin = {
                // 对照 app 端 hasLogin: getSource().hasLogin()
                screenModel.getSource(editState).hasLogin()
            },
            onBookSourceTypeChange = { editState.bookSourceTypeIndex = it },
            onEnabledChange = { editState.enabled = it },
            onEnabledCookieJarChange = { editState.enabledCookieJar = it },
            onEnableDangerousApiClick = { isChecked ->
                // 对照 app 端 onDangerousApiClick
                editState.enableDangerousApi = isChecked
                val originalEnabled = screenModel.bookSource?.enableDangerousApi == true
                if (isChecked != originalEnabled) {
                    SharedJsScope.remove(screenModel.bookSource?.jsLib)
                }
                if (isChecked) {
                    showDangerousApiConfirm = true
                }
            },
            onEnabledReviewChange = { editState.enabledReview = it },
            onEnabledExploreChange = { editState.enabledExplore = it },
            onExploreStyleChange = { editState.exploreStyleIndex = it },
            onExploreColsChange = { editState.exploreColsIndex = it },
            onTabChange = { editState.currentTab = it },
        )
    }

    BookSourceEditScreen(
        state = editState,
        callbacks = callbacks,
        editEntities = { tab -> screenModel.editEntities(tab) },
        onFieldFocus = { fieldId, entity -> activeField.value = fieldId to entity },
        onFieldTextChange = { fieldId, text -> fieldTexts[fieldId] = text },
        fieldTextOverride = { fieldId -> fieldTexts[fieldId] },
    )

    // 帮助对话框 (对照 app 端 showHelp(fileName))
    helpFileName?.let { fileName ->
        HelpDialog(fileName) { helpFileName = null }
    }

    // 危险 API 确认对话框 (对照 app 端 onDangerousApiClick 内 alert)
    // dismissOnClick=false: 按钮点击不触发 onDismissRequest, 手动关闭; onDismissRequest 仅在外部点击/返回时触发
    if (showDangerousApiConfirm) {
        AppAlertDialog(
            onDismissRequest = {
                // 对照 app 端 onCancelled: 外部点击/返回时回退
                editState.enableDangerousApi = false
                showDangerousApiConfirm = false
            },
            title = strEnableDangerousApi,
            message = strEnableDangerousApiConfirm,
            okButton = AlertButton(text = strOk, dismissOnClick = false) {
                // 对照 app 端 positiveButton: 保留勾选, 关闭对话框
                showDangerousApiConfirm = false
            },
            cancelButton = AlertButton(text = strCancel, dismissOnClick = false) {
                // 对照 app 端 negativeButton: 回退并关闭
                editState.enableDangerousApi = false
                showDangerousApiConfirm = false
            },
        )
    }

    // 退出确认对话框 (对照 app 端 finish() 内 alert)
    // source 变更未保存时询问: 是=继续编辑(留), 否=退出
    // dismissOnClick=false: 按钮点击不触发 onDismissRequest, 手动关闭
    if (showExitConfirm) {
        AppAlertDialog(
            onDismissRequest = {
                // 对照 app 端 onCancelled (未定义): 外部点击/返回时仅关闭对话框, 不退出
                showExitConfirm = false
            },
            title = strExit,
            message = strExitNoSave,
            okButton = AlertButton(text = strYes, dismissOnClick = false) {
                // 对照 app 端 positiveButton(是): 继续编辑, 仅关闭对话框
                showExitConfirm = false
            },
            cancelButton = AlertButton(text = strNo, dismissOnClick = false) {
                // 对照 app 端 negativeButton(否): 退出
                showExitConfirm = false
                navigator.pop()
            },
        )
    }
}

// 书源类型转 tab 下标 (对照 app 端 bookSourceTypeToIndex)
private fun bookSourceTypeToIndex(type: Int): Int = when (type) {
    BookSourceType.rss -> 5
    BookSourceType.video -> 4
    BookSourceType.file -> 3
    BookSourceType.image -> 2
    BookSourceType.audio -> 1
    else -> 0
}

// 从 BookSource 同步 header 表单状态 (对照 app 端 upSourceView header 部分)
// 实体列表由 ScreenModel.upSourceView 重建, 这里仅同步 editState 的 header 字段 + sourceVersion++
private fun applySourceToEditState(bs: BookSource, editState: BookSourceEditState) {
    editState.bookSourceTypeIndex = bookSourceTypeToIndex(bs.bookSourceType)
    editState.enabled = bs.enabled
    editState.enabledCookieJar = bs.enabledCookieJar == true
    editState.enableDangerousApi = bs.enableDangerousApi == true
    editState.enabledExplore = bs.enabledExplore
    editState.enabledReview = bs.enabledReview
    editState.exploreStyleIndex = if (BookSource.exploreStyleIsVideo(bs.exploreStyle)) 1 else 0
    editState.exploreColsIndex = BookSource.exploreStyleCols(bs.exploreStyle).coerceIn(0, 6)
    editState.sourceVersion++
}
