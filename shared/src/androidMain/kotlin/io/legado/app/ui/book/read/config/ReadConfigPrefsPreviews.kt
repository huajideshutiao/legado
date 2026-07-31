package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [PaddingConfigScreen.kt] / [TipConfigScreen.kt] / [ReadAloudConfigScreen.kt] / [MoreConfigScreen.kt] 的 @Preview。
 *
 * Controller 接口用内存 stub 实现, 字段返回常见默认值。
 * ReadAloudConfigScreen / MoreConfigScreen 走 PreferenceScreen, 内部通过
 * LocalPreferenceStoreProvider (LegadoThemePreview 已注入 stub) 读写 prefs, 无需额外注入。
 */

// ===== PaddingConfigScreen =====

/** Preview 期 [PaddingConfigController] stub, 所有字段返回常见默认值 (与 ReadBookConfig 默认对齐)。 */
private class PreviewPaddingConfigController : PaddingConfigController {
    override var showHeaderLine: Boolean = true
    override var showFooterLine: Boolean = false
    override var headerPaddingTop: Int = 0
    override var headerPaddingBottom: Int = 0
    override var headerPaddingLeft: Int = 16
    override var headerPaddingRight: Int = 16
    override var paddingTop: Int = 0
    override var paddingBottom: Int = 0
    override var paddingLeft: Int = 16
    override var paddingRight: Int = 16
    override var footerPaddingTop: Int = 0
    override var footerPaddingBottom: Int = 0
    override var footerPaddingLeft: Int = 16
    override var footerPaddingRight: Int = 16
}

/** Preview 期 onPostConfig noop。 */
private val noopPostConfig: (List<ReadConfigChange>) -> Unit = {}

@Preview
@Composable
fun PaddingConfigScreenPreview() = LegadoThemePreview {
    PaddingConfigScreen(
        controller = PreviewPaddingConfigController(),
        onPostConfig = noopPostConfig,
    )
}

@Preview
@Composable
fun PaddingConfigScreenDarkPreview() = LegadoThemePreview(dark = true) {
    PaddingConfigScreen(
        controller = PreviewPaddingConfigController(),
        onPostConfig = noopPostConfig,
    )
}

// ===== TipConfigScreen =====

/** Preview 期 [TipConfigController] stub, 字段返回常见默认值。 */
private class PreviewTipConfigController : TipConfigController {
    override var titleMode: Int = 1
    override var titleSize: Int = 4
    override var titleTop: Int = 0
    override var titleBottom: Int = 0
    override var headerMode: Int = 1
    override var footerMode: Int = 0
    override var tipHeaderLeft: Int = 0
    override var tipHeaderMiddle: Int = 1
    override var tipHeaderRight: Int = 2
    override var tipFooterLeft: Int = 3
    override var tipFooterMiddle: Int = 4
    override var tipFooterRight: Int = 5
    override var tipColor: Int = 0
    override var tipDividerColor: Int = -1
}

@Preview
@Composable
fun TipConfigScreenPreview() = LegadoThemePreview {
    TipConfigScreen(
        controller = PreviewTipConfigController(),
        onBack = {},
        onPostConfig = noopPostConfig,
    )
}

@Preview
@Composable
fun TipConfigScreenDarkPreview() = LegadoThemePreview(dark = true) {
    TipConfigScreen(
        controller = PreviewTipConfigController(),
        onBack = {},
        onPostConfig = noopPostConfig,
    )
}

// ===== ReadAloudConfigScreen =====

@Preview
@Composable
fun ReadAloudConfigScreenPreview() = LegadoThemePreview {
    ReadAloudConfigScreen(
        pausePhoneCallsEnabled = true,
        speakEngineSummary = "系统默认 TTS",
        onTtsEngine = {},
        onSysTtsConfig = {},
    )
}

@Preview
@Composable
fun ReadAloudConfigScreenNoPausePreview() = LegadoThemePreview {
    // 拒接来电暂停不可用 (ignoreAudioFocus 未开)
    ReadAloudConfigScreen(
        pausePhoneCallsEnabled = false,
        speakEngineSummary = "未配置",
        onTtsEngine = {},
        onSysTtsConfig = {},
    )
}

@Preview
@Composable
fun ReadAloudConfigScreenDarkPreview() = LegadoThemePreview(dark = true) {
    ReadAloudConfigScreen(
        pausePhoneCallsEnabled = true,
        speakEngineSummary = "阅读朗读引擎",
        onTtsEngine = {},
        onSysTtsConfig = {},
    )
}

// ===== MoreConfigScreen =====

@Preview
@Composable
fun MoreConfigScreenPreview() = LegadoThemePreview {
    MoreConfigScreen(
        pageTouchSlopSummary = "默认 (16)",
        onPageTouchSlop = {},
        onClickRegionalConfig = {},
    )
}

@Preview
@Composable
fun MoreConfigScreenCustomizedPreview() = LegadoThemePreview {
    MoreConfigScreen(
        pageTouchSlopSummary = "已自定义 (24)",
        onPageTouchSlop = {},
        onClickRegionalConfig = {},
    )
}

@Preview
@Composable
fun MoreConfigScreenDarkPreview() = LegadoThemePreview(dark = true) {
    MoreConfigScreen(
        pageTouchSlopSummary = "默认 (16)",
        onPageTouchSlop = {},
        onClickRegionalConfig = {},
    )
}
