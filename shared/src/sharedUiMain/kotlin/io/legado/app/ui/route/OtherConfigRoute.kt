package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CheckSourceShared
import io.legado.app.ui.book.read.config.PageKeyDialog
import io.legado.app.ui.config.ConfigActionsShared
import io.legado.app.ui.config.OtherConfigScreen
import io.legado.app.ui.config.OtherConfigScreenModel
import io.legado.app.ui.config.OtherConfigUiEvent
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.dialog.TextInputDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import kotlinx.coroutines.launch

/**
 * 其它设置路由内容: 桥接 [OtherConfigScreenModel] 与 [OtherConfigScreen]。
 *
 * 点击型交互 (弹窗/NumberPicker/SAF/Dialog Fragment) 经构造函数 lambda 注入:
 * - UA 编辑/图片缓存/预下载/Web 端口/线程数/自定义翻页按键: 用 shared 端 Compose 弹窗实现
 *   (对照 app 端 alert DSL / showNumberPicker / PageKeyDialog)
 * - 本地密码/SAF 选目录/CheckSourceConfig/DirectLinkUploadConfig: 通过 [PlatformCapabilityProviders] 注入
 *   (对照 app 端 LocalConfig.password / HandleFileContract / showDialogFragment)
 * - 清缓存/收缩数据库: 下沉到 [ConfigActionsShared] (纯 Kotlin, 跨平台)
 * - 清 WebView 数据: 通过 [PlatformCapabilityProviders] 注入 (Android WebView 专属)
 */
@Composable
fun OtherConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val pref = LocalPreferenceStoreProvider.current
    val appConfig = remember { AppConfigProviders.get() }
    val scope = rememberCoroutineScope()
    val platform = remember { PlatformCapabilityProviders.get() }

    // 顶栏标题 (对照 app 端 R.string.other_setting)
    val titleStr = rememberString("other_setting")

    // Summary 格式串 (对照 app 端 getString(R.string.xxx, value))
    val preDownloadFormat = rememberString("pre_download_s")
    val threadCountFormat = rememberString("threads_num")
    val webPortFormat = rememberString("web_port_summary")
    val bitmapCacheFormat = rememberString("bitmap_cache_size_summary")
    val bookTreeUriSStr = rememberString("book_tree_uri_s")

    // 弹窗/Toast 文案 (对照 app 端 R.string.xxx, 预解析供非 Composable lambda 使用)
    val okStr = rememberString("ok")
    val cancelStr = rememberString("cancel")
    val sureDelStr = rememberString("sure_del")
    val clearCacheSuccessStr = rememberString("clear_cache_success")
    val successStr = rememberString("success")

    // 弹窗显示状态 (对照 app 端各 showXxx / onXxx 点击型交互)
    var showUserAgentDialog by remember { mutableStateOf(false) }
    var showBitmapCachePicker by remember { mutableStateOf(false) }
    var showPreDownloadPicker by remember { mutableStateOf(false) }
    var showWebPortPicker by remember { mutableStateOf(false) }
    var showThreadCountPicker by remember { mutableStateOf(false) }
    var showCustomPageKey by remember { mutableStateOf(false) }
    var showLocalPasswordDialog by remember { mutableStateOf(false) }
    var showCleanCacheConfirm by remember { mutableStateOf(false) }
    var showClearWebViewConfirm by remember { mutableStateOf(false) }
    var showShrinkDatabaseConfirm by remember { mutableStateOf(false) }

    // screenModelRef: 解决 screenModel 构造期 lambda 需引用 screenModel 自身的先有鸡先有蛋问题
    var screenModelRef: OtherConfigScreenModel? = null

    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        OtherConfigScreenModel(
            // 平台注入: TextInputDialog → PlatformCapabilities.setLocalPassword
            onLocalPassword = { showLocalPasswordDialog = true },
            onUserAgent = { showUserAgentDialog = true },
            // 平台注入: SAF 选目录 (HandleFileContract.DIR_SYS), 选中后写 pref + 更新 summary
            onBookTreeUri = {
                platform.pickBookTreeUri { uri ->
                    if (uri != null) {
                        pref.putString(PreferKey.defaultBookTreeUri, uri)
                        screenModelRef?.dispatch(OtherConfigUiEvent.UpdateBookTreeUriSummary(uri))
                    }
                }
            },
            // 平台注入: showDialogFragment<CheckSourceConfig>, dismiss 后重读 summary
            onCheckSource = {
                platform.showCheckSourceConfigDialog {
                    screenModelRef?.dispatch(
                        OtherConfigUiEvent.UpdateCheckSourceSummary(CheckSourceShared.summary)
                    )
                }
            },
            // 平台注入: showDialogFragment<DirectLinkUploadConfig>
            onUploadRule = { platform.showDirectLinkUploadConfigDialog() },
            onBitmapCacheSize = { showBitmapCachePicker = true },
            onPreDownloadNum = { showPreDownloadPicker = true },
            onWebPort = { showWebPortPicker = true },
            // 下沉: alert 确认 → ConfigActionsShared.clearCache() (BookHelp.clearCache + cacheDir)
            onCleanCache = { showCleanCacheConfirm = true },
            // 平台注入: alert 确认 → PlatformCapabilities.clearWebViewData (Android WebView 专属)
            onClearWebViewData = { showClearWebViewConfirm = true },
            // 下沉: alert 确认 → ConfigActionsShared.shrinkDatabase() (Room VACUUM)
            onShrinkDatabase = { showShrinkDatabaseConfirm = true },
            onThreadCount = { showThreadCountPicker = true },
            onCustomPageKey = { showCustomPageKey = true },
        )
    }
    screenModelRef = screenModel
    val state by screenModel.state.collectAsState()

    // 对照 app 端 init: 初始化 7 个动态 summary
    LaunchedEffect(Unit) {
        if (state.userAgentSummary.isEmpty()) {
            screenModel.dispatch(
                OtherConfigUiEvent.UpdateUserAgentSummary(pref.getString(PreferKey.userAgent) ?: "")
            )
        }
        if (state.bookTreeUriSummary.isEmpty()) {
            screenModel.dispatch(
                OtherConfigUiEvent.UpdateBookTreeUriSummary(
                    pref.getString(PreferKey.defaultBookTreeUri) ?: bookTreeUriSStr
                )
            )
        }
        if (state.checkSourceSummary.isEmpty()) {
            screenModel.dispatch(OtherConfigUiEvent.UpdateCheckSourceSummary(CheckSourceShared.summary))
        }
        if (state.bitmapCacheSummary.isEmpty()) {
            screenModel.dispatch(
                OtherConfigUiEvent.UpdateBitmapCacheSummary(
                    bitmapCacheFormat.replace("%s", appConfig.bitmapCacheSize.toString())
                )
            )
        }
        if (state.preDownloadSummary.isEmpty()) {
            screenModel.dispatch(
                OtherConfigUiEvent.UpdatePreDownloadSummary(
                    preDownloadFormat.replace("%s", appConfig.preDownloadNum.toString())
                )
            )
        }
        if (state.webPortSummary.isEmpty()) {
            screenModel.dispatch(
                OtherConfigUiEvent.UpdateWebPortSummary(
                    webPortFormat.replace("%s", appConfig.webPort.toString())
                )
            )
        }
        if (state.threadCountSummary.isEmpty()) {
            screenModel.dispatch(
                OtherConfigUiEvent.UpdateThreadCountSummary(
                    threadCountFormat.replace("%s", appConfig.threadCount.toString())
                )
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleStr,
            onBack = { navigator.pop() },
        )
        OtherConfigScreen(
            userAgentSummary = state.userAgentSummary,
            bookTreeUriSummary = state.bookTreeUriSummary,
            checkSourceSummary = state.checkSourceSummary,
            bitmapCacheSummary = state.bitmapCacheSummary,
            preDownloadSummary = state.preDownloadSummary,
            webPortSummary = state.webPortSummary,
            threadCountSummary = state.threadCountSummary,
            onLocalPassword = { screenModel.dispatch(OtherConfigUiEvent.LocalPassword) },
            onUserAgent = { screenModel.dispatch(OtherConfigUiEvent.UserAgent) },
            onBookTreeUri = { screenModel.dispatch(OtherConfigUiEvent.BookTreeUri) },
            onCheckSource = { screenModel.dispatch(OtherConfigUiEvent.CheckSource) },
            onUploadRule = { screenModel.dispatch(OtherConfigUiEvent.UploadRule) },
            onBitmapCacheSize = { screenModel.dispatch(OtherConfigUiEvent.BitmapCacheSize) },
            onPreDownloadNum = { screenModel.dispatch(OtherConfigUiEvent.PreDownloadNum) },
            onWebPort = { screenModel.dispatch(OtherConfigUiEvent.WebPort) },
            onCleanCache = { screenModel.dispatch(OtherConfigUiEvent.CleanCache) },
            onClearWebViewData = { screenModel.dispatch(OtherConfigUiEvent.ClearWebViewData) },
            onShrinkDatabase = { screenModel.dispatch(OtherConfigUiEvent.ShrinkDatabase) },
            onThreadCount = { screenModel.dispatch(OtherConfigUiEvent.ThreadCount) },
            onCustomPageKey = { screenModel.dispatch(OtherConfigUiEvent.CustomPageKey) },
        )
    }

    // User-Agent 编辑对话框 (对照 app 端 showUserAgentDialog: alert + editTextView)
    if (showUserAgentDialog) {
        TextInputDialog(
            title = rememberString("user_agent"),
            initialValue = pref.getString(PreferKey.userAgent) ?: "",
            hint = rememberString("user_agent"),
            onConfirm = { userAgent ->
                if (userAgent.isBlank()) {
                    // 对照 app 端 resetUserAgent: 清空让宿主回退默认
                    pref.putString(PreferKey.userAgent, "")
                } else {
                    pref.putString(PreferKey.userAgent, userAgent)
                }
                screenModel.dispatch(OtherConfigUiEvent.UpdateUserAgentSummary(userAgent))
                showUserAgentDialog = false
            },
            onDismiss = { showUserAgentDialog = false },
        )
    }

    // 图片缓存大小 NumberPicker (对照 app 端 onBitmapCacheSize: 1..1024)
    if (showBitmapCachePicker) {
        NumberPickerDialog(
            title = rememberString("bitmap_cache_size"),
            value = appConfig.bitmapCacheSize,
            range = 1..1024,
            onConfirm = {
                pref.putInt(PreferKey.bitmapCacheSize, it)
                // 对照 app 端 onSharedPreferenceChanged: bitmap_cache_size_summary
                screenModel.dispatch(
                    OtherConfigUiEvent.UpdateBitmapCacheSummary(
                        bitmapCacheFormat.replace("%s", it.toString())
                    )
                )
            },
            onDismiss = { showBitmapCachePicker = false },
        )
    }

    // 预下载数量 NumberPicker (对照 app 端 onPreDownloadNum: 0..9999)
    if (showPreDownloadPicker) {
        NumberPickerDialog(
            title = rememberString("pre_download"),
            value = appConfig.preDownloadNum,
            range = 0..9999,
            onConfirm = {
                pref.putInt(PreferKey.preDownloadNum, it)
                // 对照 app 端 onSharedPreferenceChanged: pre_download_s
                screenModel.dispatch(
                    OtherConfigUiEvent.UpdatePreDownloadSummary(
                        preDownloadFormat.replace("%s", it.toString())
                    )
                )
            },
            onDismiss = { showPreDownloadPicker = false },
        )
    }

    // Web 端口 NumberPicker (对照 app 端 onWebPort: 1024..60000)
    if (showWebPortPicker) {
        NumberPickerDialog(
            title = rememberString("web_port_title"),
            value = appConfig.webPort,
            range = 1024..60000,
            onConfirm = {
                pref.putInt(PreferKey.webPort, it)
                // 对照 app 端 onSharedPreferenceChanged: web_port_summary
                screenModel.dispatch(
                    OtherConfigUiEvent.UpdateWebPortSummary(
                        webPortFormat.replace("%s", it.toString())
                    )
                )
            },
            onDismiss = { showWebPortPicker = false },
        )
    }

    // 并发线程数 NumberPicker (对照 app 端 onThreadCount: 1..999)
    if (showThreadCountPicker) {
        NumberPickerDialog(
            title = rememberString("threads_num_title"),
            value = appConfig.threadCount,
            range = 1..999,
            onConfirm = {
                pref.putInt(PreferKey.threadCount, it)
                // 对照 app 端 onSharedPreferenceChanged: threads_num
                screenModel.dispatch(
                    OtherConfigUiEvent.UpdateThreadCountSummary(
                        threadCountFormat.replace("%s", it.toString())
                    )
                )
            },
            onDismiss = { showThreadCountPicker = false },
        )
    }

    // 自定义翻页按键对话框 (对照 app 端 onCustomPageKey: PageKeyDialog().show)
    if (showCustomPageKey) {
        val keyMappings = remember {
            val prev = pref.getString(PreferKey.prevKeys) ?: ""
            val next = pref.getString(PreferKey.nextKeys) ?: ""
            parsePageKeyMappings(prev, next)
        }
        PageKeyDialog(
            keyMappings = keyMappings,
            onConfirm = { mappings ->
                val (prev, next) = splitPageKeyMappings(mappings)
                pref.putString(PreferKey.prevKeys, prev)
                pref.putString(PreferKey.nextKeys, next)
                showCustomPageKey = false
            },
            onDismiss = { showCustomPageKey = false },
        )
    }

    // 本地密码对话框 (对照 app 端 alertLocalPassword: alert + editTextView + LocalConfig.password =)
    if (showLocalPasswordDialog) {
        TextInputDialog(
            title = rememberString("set_local_password"),
            message = rememberString("set_local_password_summary"),
            hint = "password",
            onConfirm = { password ->
                platform.setLocalPassword(password)
                showLocalPasswordDialog = false
            },
            onDismiss = { showLocalPasswordDialog = false },
        )
    }

    // 清缓存确认对话框 (对照 app 端 clearCache: alert 确认 → viewModel.clearCache)
    if (showCleanCacheConfirm) {
        AppAlertDialog(
            onDismissRequest = { showCleanCacheConfirm = false },
            title = rememberString("clear_cache"),
            message = sureDelStr,
            okButton = AlertButton(text = okStr) {
                showCleanCacheConfirm = false
                scope.launch {
                    ConfigActionsShared.clearCache()
                    Toasters.get().toast(clearCacheSuccessStr)
                }
            },
            cancelButton = AlertButton(text = cancelStr),
        )
    }

    // 清 WebView 数据确认对话框 (对照 app 端 clearWebViewData: alert 确认 → viewModel.clearWebViewData)
    if (showClearWebViewConfirm) {
        AppAlertDialog(
            onDismissRequest = { showClearWebViewConfirm = false },
            title = rememberString("clear_webview_data"),
            message = sureDelStr,
            okButton = AlertButton(text = okStr) {
                showClearWebViewConfirm = false
                platform.clearWebViewData()
            },
            cancelButton = AlertButton(text = cancelStr),
        )
    }

    // 收缩数据库确认对话框 (对照 app 端 shrinkDatabase: alert 确认 → viewModel.shrinkDatabase)
    if (showShrinkDatabaseConfirm) {
        AppAlertDialog(
            onDismissRequest = { showShrinkDatabaseConfirm = false },
            title = rememberString("sure"),
            message = rememberString("shrink_database"),
            okButton = AlertButton(text = okStr) {
                showShrinkDatabaseConfirm = false
                scope.launch {
                    ConfigActionsShared.shrinkDatabase()
                    Toasters.get().toast(successStr)
                }
            },
            cancelButton = AlertButton(text = cancelStr),
        )
    }
}

// 解析 prevKeys/nextKeys 字符串为 PageKeyDialog 需要的 Map<Int, String>
// 与 PageKeyDialog 内部 buildKeyMappings 反向逻辑对齐
private fun parsePageKeyMappings(prevKeys: String, nextKeys: String): Map<Int, String> {
    val map = mutableMapOf<Int, String>()
    prevKeys.split(",").mapNotNull { it.trim().toIntOrNull() }.forEach { map[it] = "prev_page" }
    nextKeys.split(",").mapNotNull { it.trim().toIntOrNull() }.forEach { map[it] = "next_page" }
    return map
}

// 将 Map<Int, String> 反序列化为 prevKeys/nextKeys 字符串写回 prefs
private fun splitPageKeyMappings(mappings: Map<Int, String>): Pair<String, String> {
    val prev = mappings.filter { it.value == "prev_page" }.keys.joinToString(",") { it.toString() }
    val next = mappings.filter { it.value == "next_page" }.keys.joinToString(",") { it.toString() }
    return prev to next
}
