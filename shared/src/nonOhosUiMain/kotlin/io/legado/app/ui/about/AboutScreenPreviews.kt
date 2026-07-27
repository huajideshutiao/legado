package io.legado.app.ui.about

import androidx.compose.runtime.Composable
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview

/** [AboutScreen] 的 @Preview (纯回调型, 无状态依赖)。 */

@AppPreview
@Composable
fun AboutScreenPreview() = LegadoThemePreview {
    AboutScreen(
        updateLogSummary = "3.25.070226 · 修复换源界面崩溃; 新增字典规则管理",
        onContributors = {},
        onTelegramGroup = {},
        onCheckUpdate = {},
        onCrashLog = {},
        onSaveLog = {},
        onCreateHeapDump = {},
        onLicense = {},
        onDisclaimer = {},
    )
}

@AppPreview
@Composable
fun AboutScreenDarkPreview() = LegadoThemePreview(dark = true) {
    AboutScreen(
        updateLogSummary = "3.25.070226 · 修复换源界面崩溃; 新增字典规则管理",
        onContributors = {},
        onTelegramGroup = {},
        onCheckUpdate = {},
        onCrashLog = {},
        onSaveLog = {},
        onCreateHeapDump = {},
        onLicense = {},
        onDisclaimer = {},
    )
}
