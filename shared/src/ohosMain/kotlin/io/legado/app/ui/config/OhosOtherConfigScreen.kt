package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.dialog.NumberPickerDialog

/**
 * 鸿蒙端其它设置页入口 (包装 shared/sharedUiMain 的 [OtherConfigScreen])。
 *
 * 实现模式参考 iOS 端 [IosOtherConfigScreen]: 复用 sharedUiMain 跨平台 Composable,
 * 避免复制代码; 顶栏用 sharedUiMain 的 [AppTitleBar] (项目锁 MD2 视觉, 不用 material3 TopAppBar)。
 *
 * 阻塞点: bookTreeUri 需 SAF 文档选取器 (鸿蒙端 stub)。
 *
 * @param onBack 返回回调
 */
@Composable
fun OhosOtherConfigScreen(
    onBack: () -> Unit,
) {
    // 鸿蒙端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val localPasswordText = rememberString("ohos_local_password_not_implemented")
    val userAgentText = rememberString("ohos_user_agent_not_implemented")
    val bookTreeUriText = rememberString("ohos_book_tree_uri_not_implemented")
    val checkSourceText = rememberString("ohos_check_source_not_implemented")
    val uploadRuleText = rememberString("ohos_upload_rule_not_implemented")
    val cleanCacheText = rememberString("ohos_clean_cache_not_implemented")
    val clearWebviewDataText = rememberString("ohos_clear_webview_data_not_supported")
    val shrinkDatabaseText = rememberString("ohos_shrink_database_not_implemented")
    val customPageKeyText = rememberString("ohos_custom_page_key_not_implemented")
    val bitmapCacheSizeLabel = rememberString("bitmap_cache_size")
    val preDownloadLabel = rememberString("pre_download")
    val webPortLabel = rememberString("web_port_title")
    val threadCountLabel = rememberString("threads_num_title")
    val mbSuffix = "MB"

    // 4 个 NumberPicker 当前值 + 显隐状态 (mutableIntStateOf 让 summary 重组)
    val prefs = remember { PreferenceProviders.get() }
    var bitmapCacheSize by remember {
        mutableIntStateOf(prefs.getInt(PreferKey.bitmapCacheSize, 50))
    }
    var preDownloadNum by remember {
        mutableIntStateOf(prefs.getInt(PreferKey.preDownloadNum, 10))
    }
    var webPort by remember { mutableIntStateOf(prefs.getInt(PreferKey.webPort, 1122)) }
    var threadCount by remember { mutableIntStateOf(prefs.getInt(PreferKey.threadCount, 16)) }
    var showBitmapCacheDialog by remember { mutableStateOf(false) }
    var showPreDownloadDialog by remember { mutableStateOf(false) }
    var showWebPortDialog by remember { mutableStateOf(false) }
    var showThreadCountDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("other_setting"),
            onBack = onBack,
        )
        // 鸿蒙端: SAF/WebView 相关动作 stub; bitmapCacheSize/preDownloadNum/webPort/threadCount NumberPicker
        OtherConfigScreen(
            userAgentSummary = "",
            bookTreeUriSummary = "",
            checkSourceSummary = "",
            bitmapCacheSummary = bitmapCacheSize.toString() + " " + mbSuffix,
            preDownloadSummary = preDownloadNum.toString(),
            webPortSummary = webPort.toString(),
            threadCountSummary = threadCount.toString(),
            onLocalPassword = {
                Toasters.get().toast(localPasswordText)
            },
            onUserAgent = {
                Toasters.get().toast(userAgentText)
            },
            onBookTreeUri = {
                // TODO: 鸿蒙端 SAF 等价文档选取器, 后续接入
                Toasters.get().toast(bookTreeUriText)
            },
            onCheckSource = {
                Toasters.get().toast(checkSourceText)
            },
            onUploadRule = {
                Toasters.get().toast(uploadRuleText)
            },
            onBitmapCacheSize = { showBitmapCacheDialog = true },
            onPreDownloadNum = { showPreDownloadDialog = true },
            onWebPort = { showWebPortDialog = true },
            onCleanCache = {
                Toasters.get().toast(cleanCacheText)
            },
            onClearWebViewData = {
                // 鸿蒙端无 WebView 数据概念
                Toasters.get().toast(clearWebviewDataText)
            },
            onShrinkDatabase = {
                Toasters.get().toast(shrinkDatabaseText)
            },
            onThreadCount = { showThreadCountDialog = true },
            onCustomPageKey = {
                Toasters.get().toast(customPageKeyText)
            },
        )
    }

    // bitmapCacheSize NumberPicker (范围 10..500 MB, 默认对齐 app AppConfig = 50)
    if (showBitmapCacheDialog) {
        NumberPickerDialog(
            title = bitmapCacheSizeLabel,
            value = bitmapCacheSize,
            range = 10..500,
            onConfirm = {
                bitmapCacheSize = it
                prefs.putInt(PreferKey.bitmapCacheSize, it)
                showBitmapCacheDialog = false
            },
            onDismiss = { showBitmapCacheDialog = false },
        )
    }
    // preDownloadNum NumberPicker (范围 1..20, 默认对齐 app AppConfig = 10)
    if (showPreDownloadDialog) {
        NumberPickerDialog(
            title = preDownloadLabel,
            value = preDownloadNum,
            range = 1..20,
            onConfirm = {
                preDownloadNum = it
                prefs.putInt(PreferKey.preDownloadNum, it)
                showPreDownloadDialog = false
            },
            onDismiss = { showPreDownloadDialog = false },
        )
    }
    // webPort NumberPicker (范围 1024..65535, 默认 1122)
    if (showWebPortDialog) {
        NumberPickerDialog(
            title = webPortLabel,
            value = webPort,
            range = 1024..65535,
            onConfirm = {
                webPort = it
                prefs.putInt(PreferKey.webPort, it)
                showWebPortDialog = false
            },
            onDismiss = { showWebPortDialog = false },
        )
    }
    // threadCount NumberPicker (范围 1..32, 默认 16)
    if (showThreadCountDialog) {
        NumberPickerDialog(
            title = threadCountLabel,
            value = threadCount,
            range = 1..32,
            onConfirm = {
                threadCount = it
                prefs.putInt(PreferKey.threadCount, it)
                showThreadCountDialog = false
            },
            onDismiss = { showThreadCountDialog = false },
        )
    }
}
