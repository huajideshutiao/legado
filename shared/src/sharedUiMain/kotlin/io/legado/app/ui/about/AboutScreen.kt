package io.legado.app.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.preferenceCategory
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.check_update
import legado.shared.generated.resources.contributors
import legado.shared.generated.resources.contributors_summary
import legado.shared.generated.resources.crash_log
import legado.shared.generated.resources.create_heap_dump
import legado.shared.generated.resources.disclaimer
import legado.shared.generated.resources.join_telegram_group
import legado.shared.generated.resources.license
import legado.shared.generated.resources.other
import legado.shared.generated.resources.privacy_policy
import legado.shared.generated.resources.save_log
import legado.shared.generated.resources.update_log
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 关于页 (迁 about.xml)。逐条对齐原条目/key/点击行为。
 * qqGroup 原 isPreferenceVisible=false 恒隐藏，故不渲染；update_log 仅显版本 summary、无点击。
 *
 * 下沉 shared/sharedUiMain: stringResource(R.string.xxx) → stringResource(Res.string.xxx),
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
    val titleContributors = stringResource(Res.string.contributors)
    val summaryContributors = stringResource(Res.string.contributors_summary)
    val titleTelegram = stringResource(Res.string.join_telegram_group)
    val titleUpdateLog = stringResource(Res.string.update_log)
    val titleCheckUpdate = stringResource(Res.string.check_update)
    val titleOther = stringResource(Res.string.other)
    val titleCrashLog = stringResource(Res.string.crash_log)
    val titleSaveLog = stringResource(Res.string.save_log)
    val titleCreateHeapDump = stringResource(Res.string.create_heap_dump)
    val titlePrivacyPolicy = stringResource(Res.string.privacy_policy)
    val titleLicense = stringResource(Res.string.license)
    val titleDisclaimer = stringResource(Res.string.disclaimer)

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
        if (state.showCheckUpdate) {
            preference(
                title = titleCheckUpdate,
                enabled = !state.checkingUpdate,
                onClick = { actions.onCheckUpdate() },
            )
        }

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

// ===== @Preview 合并自 androidMain 的 about/AboutScreenPreviews.kt =====

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
