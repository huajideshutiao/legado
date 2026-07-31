package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [OtherConfigScreen.kt] 中 [OtherConfigScreen] 的 @Preview。
 *
 * OtherConfigScreen 仅用 rememberString/rememberStringArray 取 i18n 资源, 无 AppConfigProviders 依赖,
 * 由 [LegadoThemePreview] 提供的 CompositionLocal stub 即可渲染。
 * jvm Preview 端未命中 key 时 rememberString 返回 key 本身, 部分文案为 key 字符串。
 */

@Preview
@Composable
fun OtherConfigScreenPreview() = LegadoThemePreview {
    OtherConfigScreen(
        userAgentSummary = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36",
        bookTreeUriSummary = "未设置",
        checkSourceSummary = "未校验",
        bitmapCacheSummary = "128 MB",
        preDownloadSummary = "10 章",
        webPortSummary = "1122",
        threadCountSummary = "8",
        onLocalPassword = {},
        onUserAgent = {},
        onBookTreeUri = {},
        onCheckSource = {},
        onUploadRule = {},
        onBitmapCacheSize = {},
        onPreDownloadNum = {},
        onWebPort = {},
        onCleanCache = {},
        onClearWebViewData = {},
        onShrinkDatabase = {},
        onThreadCount = {},
        onCustomPageKey = {},
    )
}

@Preview
@Composable
fun OtherConfigScreenDarkPreview() = LegadoThemePreview(dark = true) {
    OtherConfigScreen(
        userAgentSummary = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36",
        bookTreeUriSummary = "未设置",
        checkSourceSummary = "未校验",
        bitmapCacheSummary = "128 MB",
        preDownloadSummary = "10 章",
        webPortSummary = "1122",
        threadCountSummary = "8",
        onLocalPassword = {},
        onUserAgent = {},
        onBookTreeUri = {},
        onCheckSource = {},
        onUploadRule = {},
        onBitmapCacheSize = {},
        onPreDownloadNum = {},
        onWebPort = {},
        onCleanCache = {},
        onClearWebViewData = {},
        onShrinkDatabase = {},
        onThreadCount = {},
        onCustomPageKey = {},
    )
}

@Preview
@Composable
fun OtherConfigScreenEmptySummaryPreview() = LegadoThemePreview {
    // 未配置各项时的空 summary 态
    OtherConfigScreen(
        userAgentSummary = "",
        bookTreeUriSummary = "",
        checkSourceSummary = "",
        bitmapCacheSummary = "",
        preDownloadSummary = "",
        webPortSummary = "",
        threadCountSummary = "",
        onLocalPassword = {},
        onUserAgent = {},
        onBookTreeUri = {},
        onCheckSource = {},
        onUploadRule = {},
        onBitmapCacheSize = {},
        onPreDownloadNum = {},
        onWebPort = {},
        onCleanCache = {},
        onClearWebViewData = {},
        onShrinkDatabase = {},
        onThreadCount = {},
        onCustomPageKey = {},
    )
}
