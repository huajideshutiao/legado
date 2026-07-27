package io.legado.app.ui.config

import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.view.postDelayed
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.reloadCachedPref
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.CheckSource
import io.legado.app.model.ImageProvider
import io.legado.app.service.WebService
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.utils.LogUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.postEvent
import io.legado.app.utils.restart
import io.legado.app.utils.showDialogFragment
import splitties.init.appCtx

/**
 * 其它设置宿主（原 OtherConfigFragment 壳上浮）。
 * 开关/单选写 prefs 后仍走 OnSharedPreferenceChangeListener 承接原副作用（key/默认值/联动逐条对齐）；
 * 动态 summary 用 Compose state 承接；点击型交互（弹窗/NumberPicker/文件选择）逐字保留。
 */
class OtherConfigHost(activity: ConfigActivity) : ConfigHost(activity),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val viewModel get() = activity.viewModel
    private val packageManager = appCtx.packageManager
    private val componentName = ComponentName(
        appCtx,
        "io.legado.app.ui.association.ProcessTextActivity"
    )
    private val localBookTreeSelect by lazy {
        activity.registerHandleFile { result ->
            result.uri?.let { treeUri ->
                AppConfig.defaultBookTreeUri = treeUri.toString()
            }
        }
    }

    private var userAgentSummary by mutableStateOf("")
    private var bookTreeUriSummary by mutableStateOf("")
    private var checkSourceSummary by mutableStateOf("")
    private var bitmapCacheSummary by mutableStateOf("")
    private var preDownloadSummary by mutableStateOf("")
    private var webPortSummary by mutableStateOf("")
    private var threadCountSummary by mutableStateOf("")

    init {
        // 同步开关初值到组件实际启用态（复刻 onCreatePreferences 首行）
        AppConfig.processText = isProcessTextEnabled()
        userAgentSummary = AppConfig.userAgent
        preDownloadSummary =
            activity.getString(R.string.pre_download_s, AppConfig.preDownloadNum.toString())
        threadCountSummary =
            activity.getString(R.string.threads_num, AppConfig.threadCount.toString())
        webPortSummary =
            activity.getString(R.string.web_port_summary, AppConfig.webPort.toString())
        bookTreeUriSummary =
            AppConfig.defaultBookTreeUri ?: activity.getString(R.string.book_tree_uri_s)
        checkSourceSummary = CheckSource.summary
        bitmapCacheSummary = activity.getString(
            R.string.bitmap_cache_size_summary, AppConfig.bitmapCacheSize.toString()
        )
        activity.defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    @Composable
    override fun Content() {
        Column(Modifier.fillMaxSize()) {
            AppTitleBar(
                title = stringResource(R.string.other_setting),
                onBack = { activity.finish() },
            )
            Box(Modifier.weight(1f)) {
                OtherConfigScreen(
                    userAgentSummary = userAgentSummary,
                    bookTreeUriSummary = bookTreeUriSummary,
                    checkSourceSummary = checkSourceSummary,
                    bitmapCacheSummary = bitmapCacheSummary,
                    preDownloadSummary = preDownloadSummary,
                    webPortSummary = webPortSummary,
                    threadCountSummary = threadCountSummary,
                    onLocalPassword = ::alertLocalPassword,
                    onUserAgent = ::showUserAgentDialog,
                    onBookTreeUri = {
                        localBookTreeSelect.launch {
                            title = activity.getString(R.string.select_book_folder)
                            mode = HandleFileContract.DIR_SYS
                        }
                    },
                    onCheckSource = { activity.showDialogFragment<CheckSourceConfig>() },
                    onUploadRule = { activity.showDialogFragment<DirectLinkUploadConfig>() },
                    onBitmapCacheSize = {
                        showNumberPicker(
                            activity,
                            titleResId = R.string.bitmap_cache_size,
                            max = 1024, min = 1, value = AppConfig.bitmapCacheSize
                        ) {
                            AppConfig.bitmapCacheSize = it
                            ImageProvider.bitmapLruCache.resize(ImageProvider.cacheSize)
                        }
                    },
                    onPreDownloadNum = {
                        showNumberPicker(
                            activity,
                            titleResId = R.string.pre_download,
                            max = 9999, min = 0, value = AppConfig.preDownloadNum
                        ) {
                            AppConfig.preDownloadNum = it
                        }
                    },
                    onWebPort = {
                        showNumberPicker(
                            activity,
                            titleResId = R.string.web_port_title,
                            max = 60000, min = 1024, value = AppConfig.webPort
                        ) {
                            AppConfig.webPort = it
                        }
                    },
                    onCleanCache = ::clearCache,
                    onClearWebViewData = ::clearWebViewData,
                    onShrinkDatabase = ::shrinkDatabase,
                    onThreadCount = {
                        showNumberPicker(
                            activity,
                            titleResId = R.string.threads_num_title,
                            max = 999, min = 1, value = AppConfig.threadCount
                        ) {
                            AppConfig.threadCount = it
                        }
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        activity.defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.preDownloadNum -> {
                preDownloadSummary =
                    activity.getString(R.string.pre_download_s, AppConfig.preDownloadNum.toString())
            }

            PreferKey.threadCount -> {
                threadCountSummary =
                    activity.getString(R.string.threads_num, AppConfig.threadCount.toString())
                postEvent(PreferKey.threadCount, "")
            }

            PreferKey.webPort -> {
                webPortSummary =
                    activity.getString(R.string.web_port_summary, AppConfig.webPort.toString())
                if (WebService.isRun) {
                    WebService.stop(activity)
                    WebService.start(activity)
                }
            }

            PreferKey.defaultBookTreeUri -> {
                bookTreeUriSummary =
                    AppConfig.defaultBookTreeUri ?: activity.getString(R.string.book_tree_uri_s)
            }

            PreferKey.recordLog -> {
                reloadCachedPref(PreferKey.recordLog)
                LogUtils.upLevel()
                LogUtils.logDeviceInfo()
                AppFreezeMonitor.init(appCtx)
                DispatchersMonitor.init()
            }

            PreferKey.processText -> sharedPreferences?.let {
                setProcessTextEnable(it.getBoolean(key, true))
            }

            PreferKey.language -> activity.window.decorView.postDelayed(1000) {
                appCtx.restart()
            }

            PreferKey.userAgent -> activity.window.decorView.post {
                userAgentSummary = AppConfig.userAgent
            }

            PreferKey.checkSource -> activity.window.decorView.post {
                checkSourceSummary = CheckSource.summary
            }

            PreferKey.bitmapCacheSize -> {
                bitmapCacheSummary = activity.getString(
                    R.string.bitmap_cache_size_summary,
                    AppConfig.bitmapCacheSize.toString()
                )
            }
        }
    }

    private fun showUserAgentDialog() {
        activity.alert(activity.getString(R.string.user_agent)) {
            val getText = editTextView(
                hint = activity.getString(R.string.user_agent),
                text = AppConfig.userAgent,
            )
            okButton {
                val userAgent = getText()
                if (userAgent.isBlank()) {
                    AppConfig.resetUserAgent()
                } else {
                    AppConfig.userAgent = userAgent
                }
            }
            cancelButton()
        }
    }

    private fun clearCache() {
        activity.alert(
            titleResource = R.string.clear_cache,
            messageResource = R.string.sure_del
        ) {
            okButton {
                viewModel.clearCache()
            }
            noButton()
        }
    }

    private fun shrinkDatabase() {
        activity.alert(R.string.sure, R.string.shrink_database) {
            okButton {
                viewModel.shrinkDatabase()
            }
            noButton()
        }
    }

    private fun clearWebViewData() {
        activity.alert(R.string.clear_webview_data, R.string.sure_del) {
            okButton {
                viewModel.clearWebViewData()
            }
            noButton()
        }
    }

    private fun isProcessTextEnabled(): Boolean {
        return packageManager.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun setProcessTextEnable(enable: Boolean) {
        if (enable) {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
        } else {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun alertLocalPassword() {
        activity.alert(R.string.set_local_password, R.string.set_local_password_summary) {
            val getText = editTextView(hint = "password")
            okButton {
                LocalConfig.password = getText()
            }
            cancelButton()
        }
    }

}
