package io.legado.app.ui.book.read.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadConfigProviders
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [ReadConfigPanel.kt] / [PageViewComposable.kt] / [PageContentCanvas.kt] 的 @Preview。
 *
 * 上述 Composable 通过 [LocalReadConfigProviders] 注入 ReadBookConfigShared / ReadTipConfigShared,
 * 用 [LocalPreferenceStoreProvider] (LegadoThemePreview 提供的 stub) 构造一份内存版
 * [ReadConfigProviders] 注入即可在 Preview 期渲染。
 */

/**
 * 包装 [LegadoThemePreview], 在其基础上注入 [LocalReadConfigProviders] (走 stub prefs)。
 */
@Composable
private fun LegadoReadConfigPreview(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    LegadoThemePreview(dark = dark) {
        val prefs = LocalPreferenceStoreProvider.current
        val providers = ReadConfigProviders(prefs)
        CompositionLocalProvider(LocalReadConfigProviders provides providers) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

// ===== ReadConfigPanel =====

@Preview
@Composable
fun ReadConfigPanelPreview() = LegadoReadConfigPreview {
    ReadConfigPanel(
        onDismissRequest = {},
        onPageAnimChange = {},
    )
}

@Preview
@Composable
fun ReadConfigPanelDarkPreview() = LegadoReadConfigPreview(dark = true) {
    ReadConfigPanel(
        onDismissRequest = {},
        onPageAnimChange = {},
    )
}

// ===== PageViewComposable =====

@Preview
@Composable
fun PageViewComposableEmptyPreview() = LegadoReadConfigPreview {
    // textPage=null 时显示加载占位 (背景色取自 stub ReadBookConfig)
    PageViewComposable(
        textPage = null,
        batteryLevel = 75,
    )
}

@Preview
@Composable
fun PageViewComposableWithEmptyPagePreview() = LegadoReadConfigPreview {
    // 用 TextPage.emptyTextPage (无文字行, 仅显示 tip 占位)
    PageViewComposable(
        textPage = TextPage.emptyTextPage,
        batteryLevel = 50,
    )
}

@Preview
@Composable
fun PageViewComposableNoBatteryPreview() = LegadoReadConfigPreview {
    PageViewComposable(
        textPage = TextPage.emptyTextPage,
        batteryLevel = -1,
    )
}

// ===== PageContentCanvas =====

@Preview
@Composable
fun PageContentCanvasEmptyPreview() = LegadoReadConfigPreview {
    // 用 emptyTextPage (无文字行), 仅渲染空 Canvas, 验证测量/绘制链路不崩
    PageContentCanvas(
        textPage = TextPage.emptyTextPage,
        modifier = Modifier.fillMaxSize(),
    )
}
