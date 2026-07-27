package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 端其它设置页入口 (包装 shared/sharedUiMain 的 [OtherConfigScreen])。
 *
 * 阻塞点: bookTreeUri 需 SAF 文档选取器 (iOS 端 stub)。
 *
 * @param onBack 返回回调
 */
@Composable
fun IosOtherConfigScreen(
    onBack: () -> Unit,
) {
    // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val localPasswordText = rememberString("ios_local_password_not_implemented")
    val userAgentText = rememberString("ios_user_agent_not_implemented")
    val bookTreeUriText = rememberString("ios_book_tree_uri_not_implemented")
    val checkSourceText = rememberString("ios_check_source_not_implemented")
    val uploadRuleText = rememberString("ios_upload_rule_not_implemented")
    val bitmapCacheText = rememberString("ios_bitmap_cache_not_implemented")
    val preDownloadNumText = rememberString("ios_pre_download_num_not_implemented")
    val webPortText = rememberString("ios_web_port_not_implemented")
    val cleanCacheText = rememberString("ios_clean_cache_not_implemented")
    val clearWebviewDataText = rememberString("ios_clear_webview_data_not_supported")
    val shrinkDatabaseText = rememberString("ios_shrink_database_not_implemented")
    val threadCountText = rememberString("ios_thread_count_not_implemented")
    val customPageKeyText = rememberString("ios_custom_page_key_not_implemented")
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("other_setting"),
            onBack = onBack,
        )
        // KP-iOS: SAF/NumberPicker/WebView 相关动作 stub
        OtherConfigScreen(
            userAgentSummary = "",
            bookTreeUriSummary = "",
            checkSourceSummary = "",
            bitmapCacheSummary = "",
            preDownloadSummary = "",
            webPortSummary = "",
            threadCountSummary = "",
            onLocalPassword = {
                Toasters.get().toast(localPasswordText)
            },
            onUserAgent = {
                Toasters.get().toast(userAgentText)
            },
            onBookTreeUri = {
                // TODO: iOS 端 SAF 等价文档选取器, KP6+ 接入
                Toasters.get().toast(bookTreeUriText)
            },
            onCheckSource = {
                Toasters.get().toast(checkSourceText)
            },
            onUploadRule = {
                Toasters.get().toast(uploadRuleText)
            },
            onBitmapCacheSize = {
                Toasters.get().toast(bitmapCacheText)
            },
            onPreDownloadNum = {
                Toasters.get().toast(preDownloadNumText)
            },
            onWebPort = {
                Toasters.get().toast(webPortText)
            },
            onCleanCache = {
                Toasters.get().toast(cleanCacheText)
            },
            onClearWebViewData = {
                // iOS 端无 WebView 数据概念
                Toasters.get().toast(clearWebviewDataText)
            },
            onShrinkDatabase = {
                Toasters.get().toast(shrinkDatabaseText)
            },
            onThreadCount = {
                Toasters.get().toast(threadCountText)
            },
            onCustomPageKey = {
                Toasters.get().toast(customPageKeyText)
            },
        )
    }
}
