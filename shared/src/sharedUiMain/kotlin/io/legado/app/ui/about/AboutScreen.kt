package io.legado.app.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.preferenceCategory

/**
 * 关于页 (迁 about.xml)。逐条对齐原条目/key/点击行为。
 * qqGroup 原 isPreferenceVisible=false 恒隐藏，故不渲染；update_log 仅显版本 summary、无点击。
 *
 * 下沉 shared/sharedUiMain: stringResource(R.string.xxx) → rememberString("xxx"),
 * 与 app 端原包名/类名一致, app/desktop 端共用。
 *
 * 三段式 API:
 * - [AboutUiState]: 不可变展示状态 (版本号/URL), 由宿主构造后推入 [AboutScreenModel]
 * - [AboutUiActions]: 交互回调集合, 宿主端实现接口
 * - [AboutScreen]: 纯 Composable 渲染入口, 仅依赖 state + actions
 *
 * @param state    展示状态
 * @param actions  交互回调
 * @param modifier 外部 modifier
 */
@Composable
fun AboutScreen(
    state: AboutUiState,
    actions: AboutUiActions,
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
    val titlePrivacyPolicy = rememberString("privacy_policy")
    val titleLicense = rememberString("license")
    val titleDisclaimer = rememberString("disclaimer")

    PreferenceScreen(modifier = modifier) {
        preference(
            title = titleContributors,
            summary = summaryContributors,
            onClick = { actions.onOpenUrl(state.contributorsUrl) },
        )
        preference(
            title = titleTelegram,
            onClick = { actions.onOpenUrl(state.telegramGroupUrl) },
        )
        preference(
            title = titleUpdateLog,
            summary = state.updateLogSummary,
        )
        preference(
            title = titleCheckUpdate,
            onClick = { actions.onCheckUpdate() },
        )

        preferenceCategory(titleOther)
        preference(
            title = titleCrashLog,
            onClick = { actions.onShowCrashLogs() },
        )
        preference(
            title = titleSaveLog,
            onClick = { actions.onSaveLog() },
        )
        preference(
            title = titleCreateHeapDump,
            onClick = { actions.onCreateHeapDump() },
        )
        preference(
            title = titlePrivacyPolicy,
            onClick = { actions.onShowMdFile(titlePrivacyPolicy, "privacyPolicy.md") },
        )
        preference(
            title = titleLicense,
            onClick = { actions.onShowMdFile(titleLicense, "LICENSE.md") },
        )
        preference(
            title = titleDisclaimer,
            onClick = { actions.onShowMdFile(titleDisclaimer, "disclaimer.md") },
        )
    }
}
