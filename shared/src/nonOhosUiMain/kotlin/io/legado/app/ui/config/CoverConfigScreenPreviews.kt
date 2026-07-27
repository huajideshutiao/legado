package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [CoverConfigScreen.kt] 中 [CoverConfigScreen] 的 @Preview。
 *
 * CoverConfigScreen 用 LocalPreferenceStoreProvider.current.getBoolean 读封面显示开关,
 * 由 [LegadoThemePreview] 提供的 StubPreferenceStore 兜底 (返回 defaultValue)。
 * rememberString 在 jvm Preview 端未命中 key 时返回 key 本身, 部分文案为 key 字符串。
 */

@AppPreview
@Composable
fun CoverConfigScreenPreview() = LegadoThemePreview {
    CoverConfigScreen(
        onDefaultCover = {},
        onCoverHeight = {},
        coverHeightSummary = "120",
        dayCoverSummary = "3 张",
        nightCoverSummary = "3 张",
        onRefreshCover = {},
    )
}

@AppPreview
@Composable
fun CoverConfigScreenDarkPreview() = LegadoThemePreview(dark = true) {
    CoverConfigScreen(
        onDefaultCover = {},
        onCoverHeight = {},
        coverHeightSummary = "120",
        dayCoverSummary = "3 张",
        nightCoverSummary = "3 张",
        onRefreshCover = {},
    )
}

@AppPreview
@Composable
fun CoverConfigScreenNoCustomCoverPreview() = LegadoThemePreview {
    // 未自定义封面时的空数量态
    CoverConfigScreen(
        onDefaultCover = {},
        onCoverHeight = {},
        coverHeightSummary = "160",
        dayCoverSummary = "0 张",
        nightCoverSummary = "0 张",
        onRefreshCover = {},
    )
}
