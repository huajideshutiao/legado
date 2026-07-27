package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.preferenceCategory
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 朗读设置（迁 pref_config_aloud.xml）。逐条对齐原条目顺序/key/默认值。
 * pauseReadAloudWhilePhoneCalls 联动 ignoreAudioFocus（enabled），
 * 朗读引擎 summary 动态（SpeakEngineDialog 回调刷新），事件广播由宿主承接。
 *
 * 下沉 shared/sharedUiMain 后:
 * - `stringResource(R.string.xxx)` → `rememberString("xxx")` (key-based, 跨平台)
 * - PreferenceScreen/preference/preferenceCategory/switchPreference 走 shared/sharedUiMain 的
 *   io.legado.app.ui.compose.preference 包, 内部通过 LocalPreferenceStoreProvider 读写 prefs
 * - 注意: 原 "systemMediaControlCompatibilityChange"/"mediaButtonPerNext" 为硬编码 key
 *   (不在 PreferKey 中), 保留原字面量字符串
 */
@Composable
fun ReadAloudConfigScreen(
    pausePhoneCallsEnabled: Boolean,
    speakEngineSummary: String,
    onTtsEngine: () -> Unit,
    onSysTtsConfig: () -> Unit,
) {
    val titleAloudConfig = rememberString("aloud_config")
    val titleIgnoreAudioFocus = rememberString("ignore_audio_focus_title")
    val summaryIgnoreAudioFocus = rememberString("ignore_audio_focus_summary")
    val titlePausePhoneCalls = rememberString("pause_read_aloud_while_phone_calls_title")
    val summaryPausePhoneCalls = rememberString("pause_read_aloud_while_phone_calls_summary")
    val titleWakeLock = rememberString("read_aloud_wake_lock")
    val summaryWakeLock = rememberString("read_aloud_wake_lock_summary")
    val titleMediaControlCompat = rememberString("system_media_control_compatibility_change")
    val summaryMediaControlCompat =
        rememberString("system_media_control_compatibility_change_summary")
    val titleMediaButtonPerNext = rememberString("pref_media_button_per_next")
    val summaryMediaButtonPerNext = rememberString("pref_media_button_per_next_summary")
    val titleReadAloudByPage = rememberString("read_aloud_by_page")
    val summaryReadAloudByPage = rememberString("read_aloud_by_page_summary")
    val titleStreamReadAloud = rememberString("stream_read_aloud_audio")
    val summaryStreamReadAloud = rememberString("stream_read_aloud_audio_summary")
    val titleSpeakEngine = rememberString("speak_engine")
    val titleSysTtsConfig = rememberString("sys_tts_config")
    val summarySysTtsConfig = rememberString("sys_tts_config_summary")

    AppTheme {
        PreferenceScreen {
            preferenceCategory(titleAloudConfig)
            switchPreference(
                prefKey = PreferKey.ignoreAudioFocus,
                title = titleIgnoreAudioFocus,
                summary = summaryIgnoreAudioFocus,
                defaultValue = false,
            )
            switchPreference(
                prefKey = PreferKey.pauseReadAloudWhilePhoneCalls,
                title = titlePausePhoneCalls,
                summary = summaryPausePhoneCalls,
                defaultValue = false,
                enabled = pausePhoneCallsEnabled,
            )
            switchPreference(
                prefKey = PreferKey.readAloudWakeLock,
                title = titleWakeLock,
                summary = summaryWakeLock,
                defaultValue = false,
            )
            switchPreference(
                prefKey = "systemMediaControlCompatibilityChange",
                title = titleMediaControlCompat,
                summary = summaryMediaControlCompat,
                defaultValue = false,
            )
            switchPreference(
                prefKey = "mediaButtonPerNext",
                title = titleMediaButtonPerNext,
                summary = summaryMediaButtonPerNext,
                defaultValue = false,
            )
            switchPreference(
                prefKey = PreferKey.readAloudByPage,
                title = titleReadAloudByPage,
                summary = summaryReadAloudByPage,
                defaultValue = false,
            )
            switchPreference(
                prefKey = PreferKey.streamReadAloudAudio,
                title = titleStreamReadAloud,
                summary = summaryStreamReadAloud,
                defaultValue = false,
            )
            preference(
                title = titleSpeakEngine,
                summary = speakEngineSummary,
                onClick = onTtsEngine,
            )
            preference(
                title = titleSysTtsConfig,
                summary = summarySysTtsConfig,
                onClick = onSysTtsConfig,
            )
        }
    }
}
