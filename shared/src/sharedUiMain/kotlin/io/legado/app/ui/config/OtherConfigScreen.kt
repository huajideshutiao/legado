package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.listPreference
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add_to_text_context_menu_s
import legado.shared.generated.resources.add_to_text_context_menu_t
import legado.shared.generated.resources.auto_check_update
import legado.shared.generated.resources.bitmap_cache_size
import legado.shared.generated.resources.book_info_delete_alert_summary
import legado.shared.generated.resources.book_info_delete_alert_title
import legado.shared.generated.resources.book_tree_uri_t
import legado.shared.generated.resources.check_source_config
import legado.shared.generated.resources.clear_cache
import legado.shared.generated.resources.clear_cache_summary
import legado.shared.generated.resources.clear_webview_data
import legado.shared.generated.resources.clear_webview_data_summary
import legado.shared.generated.resources.click_book_open_read
import legado.shared.generated.resources.click_book_open_read_summary
import legado.shared.generated.resources.custom_page_key
import legado.shared.generated.resources.default_home_page
import legado.shared.generated.resources.direct_link_upload_rule
import legado.shared.generated.resources.direct_link_upload_rule_summary
import legado.shared.generated.resources.ignore_audio_focus_summary
import legado.shared.generated.resources.ignore_audio_focus_title
import legado.shared.generated.resources.language
import legado.shared.generated.resources.media_button_on_exit_summary
import legado.shared.generated.resources.media_button_on_exit_title
import legado.shared.generated.resources.pre_download
import legado.shared.generated.resources.pref_cronet_summary
import legado.shared.generated.resources.ps_auto_refresh
import legado.shared.generated.resources.pt_auto_refresh
import legado.shared.generated.resources.read_aloud_by_media_button_summary
import legado.shared.generated.resources.read_aloud_by_media_button_title
import legado.shared.generated.resources.record_debug_log
import legado.shared.generated.resources.record_heap_dump_s
import legado.shared.generated.resources.record_heap_dump_t
import legado.shared.generated.resources.record_log
import legado.shared.generated.resources.replace_enable_default_s
import legado.shared.generated.resources.replace_enable_default_t
import legado.shared.generated.resources.set_local_password
import legado.shared.generated.resources.set_local_password_summary
import legado.shared.generated.resources.show_add_to_shelf_alert_summary
import legado.shared.generated.resources.show_add_to_shelf_alert_title
import legado.shared.generated.resources.shrink_database
import legado.shared.generated.resources.shrink_database_summary
import legado.shared.generated.resources.threads_num_title
import legado.shared.generated.resources.update_to_variant_summary
import legado.shared.generated.resources.update_to_variant_title
import legado.shared.generated.resources.user_agent
import legado.shared.generated.resources.web_port_title
import legado.shared.generated.resources.web_service_wake_lock
import legado.shared.generated.resources.web_service_wake_lock_summary
import legado.shared.generated.resources.default_app_variant
import legado.shared.generated.resources.default_app_variant_value
import legado.shared.generated.resources.default_home_page_value
import legado.shared.generated.resources.language_value
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * 其它设置页（迁 pref_config_other.xml）。逐条对齐原条目顺序/key/默认值。
 * 开关/单选写 prefs（key 不变），副作用仍由宿主的 OnSharedPreferenceChangeListener 承接；
 * 动态 summary 与点击型交互（弹窗/NumberPicker/文件选择）由宿主传入。
 *
 * 下沉 shared/sharedUiMain:
 * - stringResource(R.string.xxx) → stringResource(Res.string.xxx)
 * - stringArrayResource(R.array.xxx) → stringArrayResource(Res.array.xxx)
 * - 与 app 端原包名/类名一致, app/desktop 端共用。
 */
@Composable
fun OtherConfigScreen(
    userAgentSummary: String,
    bookTreeUriSummary: String,
    checkSourceSummary: String,
    bitmapCacheSummary: String,
    preDownloadSummary: String,
    webPortSummary: String,
    threadCountSummary: String,
    onLocalPassword: () -> Unit,
    onUserAgent: () -> Unit,
    onBookTreeUri: () -> Unit,
    onCheckSource: () -> Unit,
    onUploadRule: () -> Unit,
    onBitmapCacheSize: () -> Unit,
    onPreDownloadNum: () -> Unit,
    onWebPort: () -> Unit,
    onCleanCache: () -> Unit,
    onClearWebViewData: () -> Unit,
    onShrinkDatabase: () -> Unit,
    onThreadCount: () -> Unit,
    onCustomPageKey: () -> Unit,
) {
    val languageEntries = stringArrayResource(Res.array.language)
    val languageValues = stringArrayResource(Res.array.language_value)
    val homePageEntries = stringArrayResource(Res.array.default_home_page)
    val homePageValues = stringArrayResource(Res.array.default_home_page_value)
    val variantEntries = stringArrayResource(Res.array.default_app_variant)
    val variantValues = stringArrayResource(Res.array.default_app_variant_value)

    val titleLanguage = stringResource(Res.string.language)
    val titleHomePage = stringResource(Res.string.default_home_page)
    val titleAutoRefresh = stringResource(Res.string.pt_auto_refresh)
    val summaryAutoRefresh = stringResource(Res.string.ps_auto_refresh)
    val titleDevFeat = stringResource(Res.string.click_book_open_read)
    val summaryDevFeat = stringResource(Res.string.click_book_open_read_summary)
    val titleLocalPassword = stringResource(Res.string.set_local_password)
    val summaryLocalPassword = stringResource(Res.string.set_local_password_summary)
    val titleUserAgent = stringResource(Res.string.user_agent)
    val titleWebWakeLock = stringResource(Res.string.web_service_wake_lock)
    val summaryWebWakeLock = stringResource(Res.string.web_service_wake_lock_summary)
    val titleBookTree = stringResource(Res.string.book_tree_uri_t)
    val titleCheckSource = stringResource(Res.string.check_source_config)
    val titleUploadRule = stringResource(Res.string.direct_link_upload_rule)
    val summaryUploadRule = stringResource(Res.string.direct_link_upload_rule_summary)
    val summaryCronet = stringResource(Res.string.pref_cronet_summary)
    val titleBitmapCache = stringResource(Res.string.bitmap_cache_size)
    val titlePreDownload = stringResource(Res.string.pre_download)
    val titleReplaceEnable = stringResource(Res.string.replace_enable_default_t)
    val summaryReplaceEnable = stringResource(Res.string.replace_enable_default_s)
    val titleMediaButtonExit = stringResource(Res.string.media_button_on_exit_title)
    val summaryMediaButtonExit = stringResource(Res.string.media_button_on_exit_summary)
    val titleReadAloudMediaButton = stringResource(Res.string.read_aloud_by_media_button_title)
    val summaryReadAloudMediaButton = stringResource(Res.string.read_aloud_by_media_button_summary)
    val titleIgnoreAudioFocus = stringResource(Res.string.ignore_audio_focus_title)
    val summaryIgnoreAudioFocus = stringResource(Res.string.ignore_audio_focus_summary)
    val titleAddToShelfAlert = stringResource(Res.string.show_add_to_shelf_alert_title)
    val summaryAddToShelfAlert = stringResource(Res.string.show_add_to_shelf_alert_summary)
    val titleBookInfoDeleteAlert = stringResource(Res.string.book_info_delete_alert_title)
    val summaryBookInfoDeleteAlert = stringResource(Res.string.book_info_delete_alert_summary)
    val titleUpdateToVariant = stringResource(Res.string.update_to_variant_title)
    val summaryUpdateToVariant = stringResource(Res.string.update_to_variant_summary)
    val titleAutoCheckUpdate = stringResource(Res.string.auto_check_update)
    val titleWebPort = stringResource(Res.string.web_port_title)
    val titleCleanCache = stringResource(Res.string.clear_cache)
    val summaryCleanCache = stringResource(Res.string.clear_cache_summary)
    val titleClearWebView = stringResource(Res.string.clear_webview_data)
    val summaryClearWebView = stringResource(Res.string.clear_webview_data_summary)
    val titleShrinkDatabase = stringResource(Res.string.shrink_database)
    val summaryShrinkDatabase = stringResource(Res.string.shrink_database_summary)
    val titleThreadCount = stringResource(Res.string.threads_num_title)
    val titleProcessText = stringResource(Res.string.add_to_text_context_menu_t)
    val summaryProcessText = stringResource(Res.string.add_to_text_context_menu_s)
    val titleRecordLog = stringResource(Res.string.record_log)
    val summaryRecordLog = stringResource(Res.string.record_debug_log)
    val titleRecordHeapDump = stringResource(Res.string.record_heap_dump_t)
    val summaryRecordHeapDump = stringResource(Res.string.record_heap_dump_s)
    val titleCustomPageKey = stringResource(Res.string.custom_page_key)

    AppTheme {
        PreferenceScreen {
            listPreference(
                prefKey = PreferKey.language,
                title = titleLanguage,
                entries = languageEntries,
                values = languageValues,
                defaultValue = "auto",
            )
            listPreference(
                prefKey = PreferKey.defaultHomePage,
                title = titleHomePage,
                entries = homePageEntries,
                values = homePageValues,
                defaultValue = "bookshelf",
            )
            switchPreference(
                prefKey = PreferKey.autoRefresh,
                title = titleAutoRefresh,
                summary = summaryAutoRefresh,
                defaultValue = false,
            )
            switchPreference(
                prefKey = PreferKey.devFeat,
                title = titleDevFeat,
                summary = summaryDevFeat,
                defaultValue = false,
            )
            preference(
                title = titleLocalPassword,
                summary = summaryLocalPassword,
                onClick = onLocalPassword,
            )
            preference(
                title = titleUserAgent,
                summary = userAgentSummary,
                onClick = onUserAgent,
            )
            switchPreference(
                prefKey = PreferKey.webServiceWakeLock,
                title = titleWebWakeLock,
                summary = summaryWebWakeLock,
                defaultValue = false,
            )
            preference(
                title = titleBookTree,
                summary = bookTreeUriSummary,
                onClick = onBookTreeUri,
            )
            preference(
                title = titleCheckSource,
                summary = checkSourceSummary,
                onClick = onCheckSource,
            )
            preference(
                title = titleUploadRule,
                summary = summaryUploadRule,
                onClick = onUploadRule,
            )
            switchPreference(
                prefKey = PreferKey.cronet,
                title = "Cronet",
                summary = summaryCronet,
                defaultValue = false,
            )
            preference(
                title = titleBitmapCache,
                summary = bitmapCacheSummary,
                onClick = onBitmapCacheSize,
            )
            preference(
                title = titlePreDownload,
                summary = preDownloadSummary,
                onClick = onPreDownloadNum,
            )
            switchPreference(
                prefKey = PreferKey.replaceEnableDefault,
                title = titleReplaceEnable,
                summary = summaryReplaceEnable,
                defaultValue = true,
            )
            switchPreference(
                prefKey = "mediaButtonOnExit",
                title = titleMediaButtonExit,
                summary = summaryMediaButtonExit,
                defaultValue = true,
            )
            switchPreference(
                prefKey = PreferKey.readAloudByMediaButton,
                title = titleReadAloudMediaButton,
                summary = summaryReadAloudMediaButton,
                defaultValue = false,
            )
            switchPreference(
                prefKey = PreferKey.ignoreAudioFocus,
                title = titleIgnoreAudioFocus,
                summary = summaryIgnoreAudioFocus,
                defaultValue = false,
            )
            switchPreference(
                prefKey = PreferKey.showAddToShelfAlert,
                title = titleAddToShelfAlert,
                summary = summaryAddToShelfAlert,
                defaultValue = true,
            )
            switchPreference(
                prefKey = PreferKey.bookInfoDeleteAlert,
                title = titleBookInfoDeleteAlert,
                summary = summaryBookInfoDeleteAlert,
                defaultValue = true,
            )
            listPreference(
                prefKey = PreferKey.updateToVariant,
                title = titleUpdateToVariant,
                summary = summaryUpdateToVariant,
                entries = variantEntries,
                values = variantValues,
                defaultValue = "default_version",
            )
            switchPreference(
                prefKey = PreferKey.autoCheckUpdate,
                title = titleAutoCheckUpdate,
                defaultValue = true,
            )
            preference(
                title = titleWebPort,
                summary = webPortSummary,
                onClick = onWebPort,
            )
            preference(
                title = titleCleanCache,
                summary = summaryCleanCache,
                onClick = onCleanCache,
            )
            preference(
                title = titleClearWebView,
                summary = summaryClearWebView,
                onClick = onClearWebViewData,
            )
            preference(
                title = titleShrinkDatabase,
                summary = summaryShrinkDatabase,
                onClick = onShrinkDatabase,
            )
            preference(
                title = titleThreadCount,
                summary = threadCountSummary,
                onClick = onThreadCount,
            )
            switchPreference(
                prefKey = PreferKey.processText,
                title = titleProcessText,
                summary = summaryProcessText,
                defaultValue = true,
            )
            switchPreference(
                prefKey = PreferKey.recordLog,
                title = titleRecordLog,
                summary = summaryRecordLog,
                defaultValue = false,
            )
            switchPreference(
                prefKey = PreferKey.recordHeapDump,
                title = titleRecordHeapDump,
                summary = summaryRecordHeapDump,
                defaultValue = false,
            )
            preference(
                title = titleCustomPageKey,
                onClick = onCustomPageKey,
            )
        }
    }
}
