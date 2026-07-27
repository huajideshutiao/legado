package io.legado.app.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.preferenceCategory

/**
 * 关于页（迁 about.xml）。逐条对齐原条目/key/点击行为。
 * qqGroup 原 isPreferenceVisible=false 恒隐藏，故不渲染；update_log 仅显版本 summary、无点击。
 * 各动作（链接/检查更新/md 弹窗/日志/堆转储）由宿主 Activity 回调承接。
 *
 * 下沉 shared/sharedUiMain: stringResource(R.string.xxx) → rememberString("xxx"),
 * 与 app 端原包名/类名一致, app/desktop 端共用。
 */
@Composable
fun AboutScreen(
    updateLogSummary: String,
    onContributors: () -> Unit,
    onTelegramGroup: () -> Unit,
    onCheckUpdate: () -> Unit,
    onCrashLog: () -> Unit,
    onSaveLog: () -> Unit,
    onCreateHeapDump: () -> Unit,
    onLicense: () -> Unit,
    onDisclaimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleContributors = rememberString("contributors")
    val summaryContributors = rememberString("contributors_summary")
    val titleTelegram = rememberString("join_telegram_group")
    val titleUpdateLog = rememberString("update_log")
    val titleCheckUpdate = rememberString("check_update")
    val titleOther = rememberString("other")
    val titleCrashLog = rememberString("crash_log")
    val titleSaveLog = rememberString("save_log")
    val titleCreateHeapDump = rememberString("create_heap_dump")
    val titleLicense = rememberString("license")
    val titleDisclaimer = rememberString("disclaimer")

    PreferenceScreen(modifier = modifier) {
        preference(
            title = titleContributors,
            summary = summaryContributors,
            onClick = onContributors,
        )
        preference(
            title = titleTelegram,
            onClick = onTelegramGroup,
        )
        preference(
            title = titleUpdateLog,
            summary = updateLogSummary,
        )
        preference(
            title = titleCheckUpdate,
            onClick = onCheckUpdate,
        )

        preferenceCategory(titleOther)
        preference(
            title = titleCrashLog,
            onClick = onCrashLog,
        )
        preference(
            title = titleSaveLog,
            onClick = onSaveLog,
        )
        preference(
            title = titleCreateHeapDump,
            onClick = onCreateHeapDump,
        )
        preference(
            title = titleLicense,
            onClick = onLicense,
        )
        preference(
            title = titleDisclaimer,
            onClick = onDisclaimer,
        )
    }
}
