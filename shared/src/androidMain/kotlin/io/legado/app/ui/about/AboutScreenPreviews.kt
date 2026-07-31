package io.legado.app.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/** [AboutScreen] 的 @Preview (纯回调型, 无状态依赖)。 */

@Preview
@Composable
fun AboutScreenPreview() = LegadoThemePreview {
    AboutScreen(
        state = AboutUiState(
            version = "3.25.070226",
            updateLogSummary = "3.25.070226 · 修复换源界面崩溃; 新增字典规则管理",
            contributorsUrl = "https://github.com/huajideshutiao/legado/graphs/contributors",
            telegramGroupUrl = "https://t.me/+mT22ceIeiSllM2U1",
        ),
        actions = NoopAboutActions,
    )
}

@Preview
@Composable
fun AboutScreenDarkPreview() = LegadoThemePreview(dark = true) {
    AboutScreen(
        state = AboutUiState(
            version = "3.25.070226",
            updateLogSummary = "3.25.070226 · 修复换源界面崩溃; 新增字典规则管理",
            contributorsUrl = "https://github.com/huajideshutiao/legado/graphs/contributors",
            telegramGroupUrl = "https://t.me/+mT22ceIeiSllM2U1",
        ),
        actions = NoopAboutActions,
    )
}

private object NoopAboutActions : AboutUiActions {
    override fun onShare() {}
    override fun onOpenUrl(url: String) {}
    override fun onCheckUpdate() {}
    override fun onShowCrashLogs() {}
    override fun onSaveLog() {}
    override fun onCreateHeapDump() {}
    override fun onShowMdFile(title: String, fileName: String) {}
}
