package io.legado.desktop.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.room.execSQL
import androidx.room.useWriterConnection
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CheckSourceShared
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.config.OtherConfigScreen
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.dialog.TextInputDialog
import io.legado.app.web.WebServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端"其他设置" Screen 入口 (包装 shared/sharedUiMain 的 [OtherConfigScreen])。
 *
 * # 职责
 *
 * - 在 [OtherConfigScreen] 之上加 [AppTitleBar] (标题"其他设置" + 返回按钮)
 * - 装配 7 个 summary (从 [PreferenceProviders] 读 prefs 值, 如 userAgent/webPort/threadCount)
 * - 装配 4 个 NumberPicker 弹窗: bitmapCacheSize(1..1024) / preDownloadNum(0..9999) /
 *   webPort(1024..60000, 0=禁用) / threadCount(1..999), 确认后写 prefs + 重组 summary
 * - 装配剩余 onClick 回调:
 *   - onCleanCache / onShrinkDatabase 已接入 (复用 shared BookStorageProviders / AppDatabaseProviders,
 *     对照 app 端 ConfigViewModel.clearCache / shrinkDatabase)
 *   - onLocalPassword: 弹 [TextInputDialog] (sharedUiMain 下沉, 写 prefs["password"])
 *   - onUserAgent: 弹 [TextInputDialog] (sharedUiMain 下沉, 写 prefs[PreferKey.userAgent])
 *   - onCheckSource: 弹 [CheckSourceConfigDialog] (desktop 实现, 复用 CheckSourceShared)
 *   - onUploadRule: 弹 [DirectLinkUploadConfigDialog] (desktop 实现)
 *   - onBookTreeUri: no-op (桌面端无 SAF, 平台不适用)
 *   - onClearWebViewData: no-op (桌面端无内置 WebView)
 * - 注入 4 个 DesktopXxxProvider 供 commonMain 的 [AppTheme] / [OtherConfigScreen] 取依赖
 *
 * # 简化项
 *
 * - bookTreeUriSummary: 桌面端无 SAF (Storage Access Framework), 永远为空
 * - onBitmapCacheSize 写 prefs 后不调 ImageProvider.bitmapLruCache.resize (桌面端未下沉)
 * - onThreadCount 写 prefs 后不发 EventBus(PreferKey.threadCount) (桌面端无 WebService 重启副作用)
 * - onWebPort 写 prefs 后不重启 WebService (桌面端无 WebService)
 *
 * @param onBack 返回回调 (切回 SETTINGS 路由, 由 DesktopApp 注入)
 */
@Composable
fun DesktopOtherConfigScreen(onBack: () -> Unit) {
    // 桌面端 Provider 注入: 供 commonMain 的 PreferenceScreen 通过 LocalPreferenceStoreProvider 取依赖
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }
    val prefs = remember { PreferenceProviders.get() }
    // 协程 scope: onCleanCache / onShrinkDatabase 异步落库/清缓存用 (替代 app 端 viewModelScope.execute)
    val scope = rememberCoroutineScope()
    val zeroDisableSuffixLabel = rememberString("zero_disable_suffix")
    val bitmapCacheSizeLabel = rememberString("bitmap_cache_size")
    val preDownloadLabel = rememberString("pre_download")
    val webPortLabel = rememberString("web_port_title")
    val threadCountLabel = rememberString("threads_num_title")

    // 4 个 NumberPickerDialog 当前值 + 显隐状态 (mutableIntStateOf 让 summary 重组)
    // 初始化时从 prefs 读取, 默认值与 app 端 AppConfig 保持一致
    var bitmapCacheSize by remember {
        mutableIntStateOf(prefs.getInt(PreferKey.bitmapCacheSize, 0))
    }
    var preDownloadNum by remember {
        mutableIntStateOf(prefs.getInt(PreferKey.preDownloadNum, 10))
    }
    var webPort by remember { mutableIntStateOf(prefs.getInt(PreferKey.webPort, 0)) }
    var threadCount by remember { mutableIntStateOf(prefs.getInt(PreferKey.threadCount, 16)) }
    var showBitmapCacheDialog by remember { mutableStateOf(false) }
    var showPreDownloadDialog by remember { mutableStateOf(false) }
    var showWebPortDialog by remember { mutableStateOf(false) }
    var showThreadCountDialog by remember { mutableStateOf(false) }
    // 直链上传规则 Dialog 显隐 (接入 DirectLinkUploadConfigDialog, 替代原 TODO 占位)
    var showUploadRuleDialog by remember { mutableStateOf(false) }
    // 本地密码 / UA / 校验设置 Dialog 显隐
    var showLocalPasswordDialog by remember { mutableStateOf(false) }
    var showUserAgentDialog by remember { mutableStateOf(false) }
    var showCheckSourceDialog by remember { mutableStateOf(false) }
    // summary 跟随 Dialog 保存后刷新 (替代 app 端 OnSharedPreferenceChangeListener 回写)
    var userAgentSummary by remember { mutableStateOf(prefs.getString(PreferKey.userAgent)) }
    var checkSourceSummary by remember { mutableStateOf(CheckSourceShared.summary) }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AppTitleBar(
                        title = rememberString("other_setting"),
                        onBack = onBack,
                    )
                    OtherConfigScreen(
                        userAgentSummary = userAgentSummary,
                        bookTreeUriSummary = "", // 桌面端无 SAF, 永远为空
                        checkSourceSummary = checkSourceSummary,
                        bitmapCacheSummary = bitmapCacheSize.toString(),
                        preDownloadSummary = preDownloadNum.toString(),
                        webPortSummary = webPort.toString() + " " + zeroDisableSuffixLabel,
                        threadCountSummary = threadCount.toString(),
                        onLocalPassword = { showLocalPasswordDialog = true },
                        onUserAgent = { showUserAgentDialog = true },
                        onBookTreeUri = { /* 桌面端无 SAF, 不适用 */ },
                        onCheckSource = { showCheckSourceDialog = true },
                        onUploadRule = { showUploadRuleDialog = true },
                        onBitmapCacheSize = { showBitmapCacheDialog = true },
                        onPreDownloadNum = { showPreDownloadDialog = true },
                        onWebPort = { showWebPortDialog = true },
                        onCleanCache = {
                            // 清缓存: 调 BookStorageProviders.clearCache() (JvmBookStorage 实现,
                            // 删除 ~/.legado/book_cache/ 根目录), 对照 app 端 ConfigViewModel.clearCache
                            // → BookHelp.clearCache + FileUtils.delete(cacheDir)
                            // 桌面端无独立 cacheDir, 仅清书籍缓存目录; 与 BookshelfManageScreen
                            // 的"清缓存"批量项一致, 复用 shared BookStorageProviders, 不复制逻辑
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        BookStorageProviders.get().clearCache()
                                    }.onFailure {
                                        AppLog.put("清缓存失败\n${it.localizedMessage}", it)
                                    }
                                }
                                Toasters.get().toast(jvmGetString("clear_cache_success"))
                            }
                        },
                        onClearWebViewData = {
                            // 桌面端无内置 WebView, 不适用 (与 app 端 clearWebViewData 删 webview/hws_webview 目录语义不符)
                        },
                        onShrinkDatabase = {
                            // 收缩数据库: 对照 app 端 ConfigViewModel.shrinkDatabase
                            // 1) bookChapterDao.deleteNotShelfBookChapters() 清孤儿章节
                            // 2) bookDao.deleteNotShelfBook() 清孤儿书籍
                            // 3) appDb.useWriterConnection { it.execSQL("VACUUM") } 收缩 SQLite 文件
                            // shared AppDatabase (extends RoomDatabase) 已下沉 useWriterConnection API,
                            // desktop 端通过 AppDatabaseProviders.get().appDb 复用同一实例, 不复制逻辑
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val appDb = AppDatabaseProviders.get().appDb
                                        appDb.bookChapterDao.deleteNotShelfBookChapters()
                                        appDb.bookDao.deleteNotShelfBook()
                                        appDb.useWriterConnection { it.execSQL("VACUUM") }
                                    }.onFailure {
                                        AppLog.put("收缩数据库失败\n${it.localizedMessage}", it)
                                    }
                                }
                                Toasters.get().toast(jvmGetString("success"))
                            }
                        },
                        onThreadCount = { showThreadCountDialog = true },
                    )
                }
            }

            // 4 个 NumberPickerDialog (shared 共享, 替代 app 端 showNumberPicker)
            // app 端范围: bitmapCacheSize 1..1024 / preDownloadNum 0..9999 / webPort 1024..60000 / threadCount 1..999
            // 桌面端无 ImageProvider.resize / WebService 重启 / EventBus 副作用, 仅写 prefs
            if (showBitmapCacheDialog) {
                NumberPickerDialog(
                    title = bitmapCacheSizeLabel,
                    value = bitmapCacheSize,
                    range = 1..1024,
                    onConfirm = {
                        bitmapCacheSize = it
                        prefs.putInt(PreferKey.bitmapCacheSize, it)
                        showBitmapCacheDialog = false
                    },
                    onDismiss = { showBitmapCacheDialog = false },
                )
            }
            if (showPreDownloadDialog) {
                NumberPickerDialog(
                    title = preDownloadLabel,
                    value = preDownloadNum,
                    range = 0..9999,
                    onConfirm = {
                        preDownloadNum = it
                        prefs.putInt(PreferKey.preDownloadNum, it)
                        showPreDownloadDialog = false
                    },
                    onDismiss = { showPreDownloadDialog = false },
                )
            }
            if (showWebPortDialog) {
                NumberPickerDialog(
                    title = webPortLabel,
                    value = webPort,
                    range = 1024..60000,
                    onConfirm = {
                        webPort = it
                        prefs.putInt(PreferKey.webPort, it)
                        showWebPortDialog = false
                        // 改端口后重启 Web 服务 (对齐 app 端 OtherConfigHost:
                        // if (WebService.isRun) { WebService.stop(activity); WebService.start(activity) })
                        if (WebServerManager.isRun) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    WebServerManager.stop()
                                    WebServerManager.start()
                                }
                            }
                        }
                    },
                    onDismiss = { showWebPortDialog = false },
                )
            }
            if (showThreadCountDialog) {
                NumberPickerDialog(
                    title = threadCountLabel,
                    value = threadCount,
                    range = 1..999,
                    onConfirm = {
                        threadCount = it
                        prefs.putInt(PreferKey.threadCount, it)
                        showThreadCountDialog = false
                    },
                    onDismiss = { showThreadCountDialog = false },
                )
            }
            // 直链上传规则配置 Dialog (替代原 onUploadRule TODO 占位,
            // 复用 shared Compose 组件 + DesktopDirectLinkUpload 配置/上传实现)
            if (showUploadRuleDialog) {
                DirectLinkUploadConfigDialog(onDismiss = { showUploadRuleDialog = false })
            }
            // 本地密码设置 Dialog (下沉 sharedUiMain TextInputDialog)
            if (showLocalPasswordDialog) {
                TextInputDialog(
                    title = rememberString("set_local_password"),
                    message = rememberString("set_local_password_summary"),
                    hint = "password",
                    onConfirm = { text ->
                        // 空串清除密码 (对照 app 端 LocalConfig.password set: value.isNullOrEmpty() → remove)
                        prefs.putString("password", text.takeIf { it.isNotBlank() })
                    },
                    onDismiss = { showLocalPasswordDialog = false },
                )
            }
            // User-Agent 编辑 Dialog (下沉 sharedUiMain TextInputDialog)
            if (showUserAgentDialog) {
                val titleUserAgent = rememberString("user_agent")
                TextInputDialog(
                    title = titleUserAgent,
                    initialValue = userAgentSummary,
                    hint = titleUserAgent,
                    onConfirm = { text ->
                        // 空串清除自定义 UA (对照 app 端 showUserAgentDialog: blank → resetUserAgent)
                        prefs.putString(PreferKey.userAgent, text.takeIf { it.isNotBlank() })
                        userAgentSummary = prefs.getString(PreferKey.userAgent)
                    },
                    onDismiss = { showUserAgentDialog = false },
                )
            }
            // 校验设置 Dialog (desktop 实现, 复用 CheckSourceShared)
            if (showCheckSourceDialog) {
                CheckSourceConfigDialog(
                    onDismiss = {
                        showCheckSourceDialog = false
                        checkSourceSummary = CheckSourceShared.summary
                    },
                )
            }
        }
    }
}
