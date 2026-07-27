package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.rememberStringArray
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.listPreference
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 其它设置页（迁 pref_config_other.xml）。逐条对齐原条目顺序/key/默认值。
 * 开关/单选写 prefs（key 不变），副作用仍由宿主的 OnSharedPreferenceChangeListener 承接；
 * 动态 summary 与点击型交互（弹窗/NumberPicker/文件选择）由宿主传入。
 *
 * 下沉 shared/sharedUiMain:
 * - stringResource(R.string.xxx) → rememberString("xxx")
 * - stringArrayResource(R.array.xxx) → rememberStringArray("xxx")
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
) {
    val languageEntries = rememberStringArray("language")
    val languageValues = rememberStringArray("language_value")
    val homePageEntries = rememberStringArray("default_home_page")
    val homePageValues = rememberStringArray("default_home_page_value")
    val variantEntries = rememberStringArray("default_app_variant")
    val variantValues = rememberStringArray("default_app_variant_value")

    val titleLanguage = rememberString("language")
    val titleHomePage = rememberString("default_home_page")
    val titleAutoRefresh = rememberString("pt_auto_refresh")
    val summaryAutoRefresh = rememberString("ps_auto_refresh")
    val titleDevFeat = rememberString("click_book_open_read")
    val summaryDevFeat = rememberString("click_book_open_read_summary")
    val titleLocalPassword = rememberString("set_local_password")
    val summaryLocalPassword = rememberString("set_local_password_summary")
    val titleUserAgent = rememberString("user_agent")
    val titleWebWakeLock = rememberString("web_service_wake_lock")
    val summaryWebWakeLock = rememberString("web_service_wake_lock_summary")
    val titleBookTree = rememberString("book_tree_uri_t")
    val titleCheckSource = rememberString("check_source_config")
    val titleUploadRule = rememberString("direct_link_upload_rule")
    val summaryUploadRule = rememberString("direct_link_upload_rule_summary")
    val summaryCronet = rememberString("pref_cronet_summary")
    val titleBitmapCache = rememberString("bitmap_cache_size")
    val titlePreDownload = rememberString("pre_download")
    val titleReplaceEnable = rememberString("replace_enable_default_t")
    val summaryReplaceEnable = rememberString("replace_enable_default_s")
    val titleMediaButtonExit = rememberString("media_button_on_exit_title")
    val summaryMediaButtonExit = rememberString("media_button_on_exit_summary")
    val titleReadAloudMediaButton = rememberString("read_aloud_by_media_button_title")
    val summaryReadAloudMediaButton = rememberString("read_aloud_by_media_button_summary")
    val titleIgnoreAudioFocus = rememberString("ignore_audio_focus_title")
    val summaryIgnoreAudioFocus = rememberString("ignore_audio_focus_summary")
    val titleAddToShelfAlert = rememberString("show_add_to_shelf_alert_title")
    val summaryAddToShelfAlert = rememberString("show_add_to_shelf_alert_summary")
    val titleBookInfoDeleteAlert = rememberString("book_info_delete_alert_title")
    val summaryBookInfoDeleteAlert = rememberString("book_info_delete_alert_summary")
    val titleUpdateToVariant = rememberString("update_to_variant_title")
    val summaryUpdateToVariant = rememberString("update_to_variant_summary")
    val titleAutoCheckUpdate = rememberString("auto_check_update")
    val titleWebPort = rememberString("web_port_title")
    val titleCleanCache = rememberString("clear_cache")
    val summaryCleanCache = rememberString("clear_cache_summary")
    val titleClearWebView = rememberString("clear_webview_data")
    val summaryClearWebView = rememberString("clear_webview_data_summary")
    val titleShrinkDatabase = rememberString("shrink_database")
    val summaryShrinkDatabase = rememberString("shrink_database_summary")
    val titleThreadCount = rememberString("threads_num_title")
    val titleProcessText = rememberString("add_to_text_context_menu_t")
    val summaryProcessText = rememberString("add_to_text_context_menu_s")
    val titleRecordLog = rememberString("record_log")
    val summaryRecordLog = rememberString("record_debug_log")
    val titleRecordHeapDump = rememberString("record_heap_dump_t")
    val summaryRecordHeapDump = rememberString("record_heap_dump_s")

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
        }
    }
}
