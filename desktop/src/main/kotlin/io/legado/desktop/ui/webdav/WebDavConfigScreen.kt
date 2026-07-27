package io.legado.desktop.ui.webdav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.storage.BackupConfigShared
import io.legado.app.help.storage.BackupShared
import io.legado.app.help.storage.RestoreShared
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.config.BackupConfigScreen
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import io.legado.desktop.ui.component.FileDialogs

// # 新增的 string key 需求 (在桌面端补 i18n 时需在 jvmMain StringResourcesJvm.kt 补 actual)
//
// 以下 4 个 key 由 shared BackupConfigScreen 内部 rememberString("xxx") 消费,
// 桌面端未注册时 rememberString 返回 key 本身 (不影响功能, 仅文案略差):
//   - web_dav_set            (WebDav 配置 分类标题)
//   - web_dav_url_s           (URL 为空时 summary 占位)
//   - web_dav_account_s       (账户为空时 summary 占位)
//   - web_dav_pw_s            (密码为空时 summary 占位)
//   - select_backup_path      (backupPath 未设置时 summary 占位)
//
// 其余 key (web_dav_url/web_dav_account/web_dav_pw/sub_dir/webdav_device_name/
// sync_book_progress_t/s/plus_t/plus_s/backup_restore/backup_path/backup/
// backup_summary/restore/restore_summary/restore_ignore/restore_ignore_summary/
// only_latest_backup_t/s/auto_check_new_backup_t/s) shared BackupConfigScreen
// 内部已通过 rememberString 引用, 桌面端补 i18n 时一并补 actual 即可。
//
// 约束: shared/src/sharedUiMain/.../ResourceProvider.kt 不动, 故仅在此注释列出需求。

/**
 * 桌面端 WebDav 配置 / 备份恢复 Screen (薄壳, 替换原重写版)。
 *
 * # 背景
 *
 * 旧 desktop `WebDavConfigScreen` 用 OutlinedTextField + Button 重写了 "4 输入框 + 3 按钮"
 * (URL/账户/密码/设备名 + 立即备份/立即恢复), 与 shared/sharedUiMain
 * [io.legado.app.ui.config.BackupConfigScreen] 完全重叠且更弱: 缺 syncBookProgress /
 * syncBookProgressPlus / onlyLatestBackup / autoCheckNewBackup 4 个开关, 也无 backupPath
 * / restoreIgnore 入口。本薄壳直接复用 shared 版, 仅保留:
 *
 * 1. desktop 平台 Provider 注入 (ThemeStore/AppConfig/EventBus/PreferenceStore) —
 *    shared [io.legado.app.ui.compose.theme.AppTheme] / [BackupConfigScreen] 通过
 *    LocalXxx 取依赖, 必须由薄壳在外层 [CompositionLocalProvider] 注入
 * 2. 6 个 summary 装配 (从 [PreferenceProviders] 读, 备份 / 恢复后刷新)
 * 3. 6 个 callbacks 装配 (走下沉 [AppWebDavShared] / [BackupShared] / [RestoreShared])
 * 4. 底部状态行 (对齐 app 端 WaitDialog, 显示备份/恢复进度消息 + loading 圈)
 *
 * # 模式参考
 *
 * - [io.legado.desktop.ui.booksource.BookSourceScreen] (Provider 注入 + callbacks 装配)
 * - [io.legado.desktop.ui.replace.ReplaceRuleScreen] (Provider 注入 + 路由回调)
 *
 * # 路由调用
 *
 * `DesktopApp.kt` 的 `DesktopRoute.WEBDAV -> WebDavConfigScreen()` 直接调用本薄壳,
 * 入口名与旧重写版一致 (无需改 DesktopApp 的 import / 调用)。本薄壳内部再调用
 * shared [BackupConfigScreen], 完成 UI 下沉复用。
 *
 * # callbacks 实现
 *
 * - onBackupPath: 弹 JFileChooser 选目录写回 PreferKey.backupPath (对齐 app 端 selectBackupPath)
 * - onWebDavBackup / onWebDavBackupLong / onWebDavRestore: 走下沉 [BackupShared] / [AppWebDavShared]
 * - onWebDavRestoreLong: 弹 JFileChooser 选 zip → [RestoreShared.restoreFromZip] (对齐 app 端 restoreFromLocal)
 * - onRestoreIgnore: 弹 [AppAlertDialog] + [AppCheckbox] 列出 [BackupConfigShared.ignoreTitle] (对齐 app 端 BackupConfigHost.backupIgnore 的 alert + multiChoiceItems), 关闭时 [BackupConfigShared.saveIgnoreConfig]
 */
@Composable
fun WebDavConfigScreen() {
    // 注入 desktop 平台 Provider (shared BackupConfigScreen 内部 AppTheme 依赖 LocalXxx)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { RefreshingPreferenceStoreProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            BackupConfigScreenContent()
        }
    }
}

/**
 * 薄壳内容: shared [BackupConfigScreen] + 底部状态行 (进度消息 + loading 圈, 对齐 app 端 WaitDialog)。
 */
@Composable
private fun BackupConfigScreenContent() {
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferenceProviders.get() }
    // 取薄壳外层注入的 LocalPreferenceStoreProvider 实例 (与 BackupConfigScreen 内部
    // editTextPreference/switchPreference 写值的同一对象), 用于 syncLocalToRegistry()
    val prefStore = LocalPreferenceStoreProvider.current
    val refreshingPrefStore = prefStore as? RefreshingPreferenceStoreProvider
    val preferenceRevision = refreshingPrefStore?.revision ?: 0
    val changedPreferenceKey = refreshingPrefStore?.lastChangedKey

    // 文案标签 (rememberString 是 @Composable, 顶层缓存后供各 lambda / 状态机引用,
    // 避免 statusText / 异常消息硬编码中文)
    val pendingOperationLabel = rememberString("pending_operation")
    val backupSuccessUploadedLabel = rememberString("backup_success_uploaded")
    val backupFailedLabel = rememberString("backup_failed")
    val localBackupSuccessLabel = rememberString("local_backup_success")
    val localBackupFailedLabel = rememberString("local_backup_failed")
    val webDavNoBackupLabel = rememberString("web_dav_no_backup")
    val restoreSuccessLabel = rememberString("restore_success")
    val restoreFailedLabel = rememberString("restore_failed")
    // 恢复对话框文案 (对齐 app 端 BackupConfigHost.restore() 用到的 R.string.select_restore_file / R.string.restore / R.string.ok / R.string.cancel)
    val selectRestoreFileLabel = rememberString("select_restore_file")
    val restoreLabel = rememberString("restore")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    // 备份忽略对话框标题 (对齐 app 端 R.string.restore_ignore = "恢复忽略列表")
    val restoreIgnoreLabel = rememberString("restore_ignore")
    val webDavUrlEmptyLabel = rememberString("web_dav_url_s")
    val webDavAccountEmptyLabel = rememberString("web_dav_account_s")
    val webDavPasswordEmptyLabel = rememberString("web_dav_pw_s")
    val selectBackupPathLabel = rememberString("select_backup_path")
    val loadingLabel = rememberString("loading")
    // app 端 BackupConfigHost.restore() 硬编码 message "WebDav无备份文件\n将从本地备份恢复。"
    // 桌面端前半句走 i18n (webDavNoBackupLabel), 后半句走 i18n (restore_from_local_prompt)
    val restoreFromLocalPromptLabel = webDavNoBackupLabel + "\n" + rememberString("restore_from_local_prompt")

    // # 存储后端已统一 + 冗余 sync
    //
    // DesktopPreferenceStoreProvider 现已内部委托 PreferenceProviders
    // (java.util.prefs 注册表), 与 BackupShared.backupLocked / AppWebDavShared.upConfig
    // 走的后端同源, UI 输入的 WebDav 配置 BackupShared 可直接读到。
    // 本函数把 5 个 WebDav string key 从 prefStore 复写到 prefs, 二者同一存储,
    // 属历史遗留的无害冗余。
    fun syncLocalToRegistry() {
        listOf(
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir,
            PreferKey.webDavDeviceName,
        ).forEach { key ->
            // prefStore.getString 返回 null 表示用户未在 UI 设过此 key, 跳过避免覆盖注册表已有值
            prefStore.getString(key, null)?.let { value ->
                prefs.putString(key, value)
            }
        }
    }

    // 6 个 summary: 从 prefs 读当前值, 空值用 string key 占位 (与 app 端 BackupConfigHost.urlSummary 对齐)
    // rememberString 在 desktop 端对未注册 key 返回 key 本身, 故直接传 key 串即可
    var webDavUrlSummary by remember {
        mutableStateOf(prefs.getString(PreferKey.webDavUrl).ifBlank { webDavUrlEmptyLabel })
    }
    var webDavAccountSummary by remember {
        mutableStateOf(prefs.getString(PreferKey.webDavAccount).ifBlank { webDavAccountEmptyLabel })
    }
    var webDavPasswordSummary by remember {
        val pw = prefs.getString(PreferKey.webDavPassword)
        mutableStateOf(if (pw.isBlank()) webDavPasswordEmptyLabel else "*".repeat(pw.length))
    }
    var webDavDirSummary by remember {
        mutableStateOf(prefs.getString(PreferKey.webDavDir, "legado"))
    }
    var webDavDeviceNameSummary by remember {
        mutableStateOf(prefs.getString(PreferKey.webDavDeviceName, ""))
    }
    var backupPathSummary by remember {
        mutableStateOf(prefs.getString(PreferKey.backupPath).ifBlank { selectBackupPathLabel })
    }

    // 状态文本 (备份/恢复操作进度消息, 底部状态行展示, 对齐 app 端 WaitDialog 语义)
    var statusText by remember { mutableStateOf(pendingOperationLabel) }
    // 操作进行中标志 (备份/恢复执行时显示 loading 圈)
    var isLoading by remember { mutableStateOf(false) }

    // 恢复对话框状态 (对齐 app 端 BackupConfigHost.restore():
    // WebDav 有备份 → 弹选择列表; 无备份 → 弹"从本地恢复"确认对话框)
    // 桌面端用户点"恢复"即可看到选择, 不依赖长按手势 (combinedClickable 桌面端长按不直观)
    var showRestoreSelector by remember { mutableStateOf(false) }
    var restoreNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var showRestoreFromLocalDialog by remember { mutableStateOf(false) }

    // 备份忽略对话框状态 (对齐 app 端 BackupConfigHost.backupIgnore:
    // alert + multiChoiceItems 显示 BackupConfig.ignoreTitle 列表, onDismiss 时 saveIgnoreConfig)
    // ignoreChecked 为 UI 临时镜像, 用户勾选直接写 BackupConfigShared.ignoreConfig, 关闭时统一持久化
    var showIgnoreDialog by remember { mutableStateOf(false) }
    var ignoreChecked by remember {
        mutableStateOf(BackupConfigShared.ignoreKeys.map { BackupConfigShared.ignoreConfig[it] ?: false })
    }

    // 操作完成后重新读 prefs 刷 summary (桌面端 java.util.prefs 无 listener, 手动刷新)
    fun refreshSummaries() {
        webDavUrlSummary = prefs.getString(PreferKey.webDavUrl).ifBlank { webDavUrlEmptyLabel }
        webDavAccountSummary = prefs.getString(PreferKey.webDavAccount).ifBlank { webDavAccountEmptyLabel }
        val pw = prefs.getString(PreferKey.webDavPassword)
        webDavPasswordSummary = if (pw.isBlank()) webDavPasswordEmptyLabel else "*".repeat(pw.length)
        webDavDirSummary = prefs.getString(PreferKey.webDavDir, "legado")
        webDavDeviceNameSummary = prefs.getString(PreferKey.webDavDeviceName, "")
        backupPathSummary = prefs.getString(PreferKey.backupPath).ifBlank { selectBackupPathLabel }
    }

    // java.util.prefs 没有 Compose listener；由注入的 provider 在每次设置写入后递增 revision。
    LaunchedEffect(preferenceRevision) {
        refreshSummaries()
        if (changedPreferenceKey in webDavConfigKeys) {
            runCatching { AppWebDavShared.upConfig() }
                .onFailure {
                    refreshSummaries()
                    AppLog.put("WebDav config failed\n${it.localizedMessage}", it)
                }
        }
    }

    fun backup(uploadToWebDav: Boolean) {
        if (isLoading) return
        scope.launch {
            var destinationPath = prefs.getString(PreferKey.backupPath).takeIf { it.isNotBlank() }
            if (destinationPath == null) {
                destinationPath = withContext(Dispatchers.IO) { pickDirectory() }?.absolutePath
                if (destinationPath == null) return@launch
                prefs.putString(PreferKey.backupPath, destinationPath)
                refreshSummaries()
            }

            isLoading = true
            statusText = pendingOperationLabel
            syncLocalToRegistry()
            runCatching {
                withContext(Dispatchers.IO) {
                    if (uploadToWebDav) AppWebDavShared.upConfig()
                    BackupShared.backupLocked(destinationPath, uploadToWebDav)
                }
            }.onSuccess { localZipPath ->
                statusText = if (uploadToWebDav) {
                    "$backupSuccessUploadedLabel: $localZipPath"
                } else {
                    "$localBackupSuccessLabel: $localZipPath"
                }
            }.onFailure {
                statusText = if (uploadToWebDav) {
                    backupFailedLabel.format(it.localizedMessage)
                } else {
                    localBackupFailedLabel.format(it.localizedMessage)
                }
                val logKey = if (uploadToWebDav) {
                    "webdav_backup_failed"
                } else {
                    "webdav_local_backup_failed"
                }
                AppLog.put(jvmGetString(logKey) + "\n" + it.localizedMessage, it)
            }
            isLoading = false
        }
    }

    // 本地恢复: 弹 JFileChooser 选 zip → 解压到 backupPath → RestoreShared.restoreLocked
    // (对齐 app 端 BackupConfigHost.restoreFromLocal() + viewModel.restore(uri))
    // 供 onWebDavRestoreLong (长按恢复) 与"无备份时确认对话框 okButton"共用,
    // 桌面端用户既可长按"恢复"项, 也可在 WebDav 无备份时点"恢复"经确认对话框触发本地恢复
    fun restoreFromLocal() {
        if (isLoading) return
        scope.launch {
            val zipFile = withContext(Dispatchers.IO) {
                pickZipFile()
            }
            if (zipFile == null) return@launch
            isLoading = true
            statusText = pendingOperationLabel
            runCatching {
                // 复用下沉的 RestoreShared.restoreFromZip (清空 backupPath + 解压 + restoreLocked, 与 app 端 Restore.restore 一致)
                withContext(Dispatchers.IO) {
                    RestoreShared.restoreFromZip(zipFile.absolutePath)
                }
            }.onSuccess {
                statusText = restoreSuccessLabel
                refreshSummaries()
            }.onFailure {
                statusText = restoreFailedLabel.format(it.localizedMessage)
                AppLog.put(jvmGetString("restore_from_local_failed") + "\n" + it.localizedMessage, it)
            }
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // shared BackupConfigScreen (UI 全部交给 shared, 薄壳只装配 summary + callbacks)
        Box(Modifier.weight(1f)) {
            BackupConfigScreen(
                webDavUrlSummary = webDavUrlSummary,
                webDavAccountSummary = webDavAccountSummary,
                webDavPasswordSummary = webDavPasswordSummary,
                webDavDirSummary = webDavDirSummary,
                webDavDeviceNameSummary = webDavDeviceNameSummary,
                backupPathSummary = backupPathSummary,
                editDialogWidthFraction = 0.8f,
                // 备份路径选择: 弹 JFileChooser 选目录 (对齐 app 端 selectBackupPath.launch)
                onBackupPath = {
                    if (isLoading) return@BackupConfigScreen
                    scope.launch {
                        val selectedDir = withContext(Dispatchers.IO) {
                            pickDirectory()
                        }
                        if (selectedDir != null) {
                            prefs.putString(PreferKey.backupPath, selectedDir.absolutePath)
                            refreshSummaries()
                        }
                    }
                },
                onWebDavBackup = { backup(uploadToWebDav = true) },
                // 长按备份按钮: 只备份到本地, 不上传到 WebDav
                onWebDavBackupLong = { backup(uploadToWebDav = false) },
                onWebDavRestore = {
                    if (isLoading) return@BackupConfigScreen
                    syncLocalToRegistry()
                    isLoading = true
                    statusText = loadingLabel
                    scope.launch {
                        val namesResult = runCatching {
                            withContext(Dispatchers.IO) {
                                AppWebDavShared.upConfig()
                                AppWebDavShared.getBackupNames()
                            }
                        }.onFailure {
                            statusText = restoreFailedLabel.format(it.localizedMessage)
                            AppLog.put(jvmGetString("get_webdav_backup_list_failed") + "\n" + it.localizedMessage, it)
                        }
                        val names = namesResult.getOrNull().orEmpty()
                        if (names.isNotEmpty()) {
                            restoreNames = names
                            showRestoreSelector = true
                            statusText = pendingOperationLabel
                        } else {
                            showRestoreFromLocalDialog = true
                            if (namesResult.isSuccess) statusText = webDavNoBackupLabel
                        }
                        isLoading = false
                    }
                },
                onWebDavRestoreLong = {
                    if (isLoading) return@BackupConfigScreen
                    restoreFromLocal()
                },
                onRestoreIgnore = {
                    ignoreChecked = BackupConfigShared.ignoreKeys.map {
                        BackupConfigShared.ignoreConfig[it] ?: false
                    }
                    showIgnoreDialog = true
                },
            )
        }

        // 底部状态行 (对齐 app 端 WaitDialog 语义, 显示备份/恢复进度消息 + loading 圈)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = statusText,
                // 14sp/20sp/0.25 对应原 M3 bodyMedium
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        // 恢复选择列表 (对齐 app 端 BackupConfigHost.restore() 的 selector:
        // 列出 WebDav 备份名, 用户选中后调 AppWebDavShared.restoreWebDav 恢复该备份)
        // 宽度 0.8 窗口宽度 (对齐用户反馈: 备份相关对话框宽度应占 0.8 窗口宽度)
        if (showRestoreSelector) {
            AppSelectorDialog(
                onDismissRequest = { showRestoreSelector = false },
                title = selectRestoreFileLabel,
                items = restoreNames,
                onItemSelected = { index ->
                    if (index in restoreNames.indices) {
                        isLoading = true
                        statusText = pendingOperationLabel
                        scope.launch {
                            runCatching {
                                // 选中后从 WebDav 恢复该备份 (对齐 app 端 viewModel.restoreWebDav(names[index]))
                                AppWebDavShared.restoreWebDav(restoreNames[index])
                            }.onSuccess {
                                statusText = restoreSuccessLabel
                                // restoreLocked 写回 prefs (含 webDavPassword 等), 刷 summary 保持一致
                                refreshSummaries()
                            }.onFailure {
                                statusText = restoreFailedLabel.format(it.localizedMessage)
                                AppLog.put(jvmGetString("webdav_restore_failed") + "\n" + it.localizedMessage, it)
                            }
                            isLoading = false
                        }
                    }
                },
                widthFraction = 0.8f,
            )
        }

        // WebDav 无备份时"从本地恢复"确认对话框 (对齐 app 端 BackupConfigHost.restore() 的 alert:
        // 标题 "恢复", message "WebDav无备份文件\n将从本地备份恢复。", okButton 触发 restoreFromLocal)
        // 宽度 0.8 窗口宽度 (对齐用户反馈: 备份相关对话框宽度应占 0.8 窗口宽度)
        if (showRestoreFromLocalDialog) {
            AppAlertDialog(
                onDismissRequest = { showRestoreFromLocalDialog = false },
                title = restoreLabel,
                message = restoreFromLocalPromptLabel,
                widthFraction = 0.8f,
                okButton = AlertButton(text = okLabel) {
                    // 确认后走本地恢复 (对齐 app 端 restoreFromLocal())
                    restoreFromLocal()
                },
                cancelButton = AlertButton(text = cancelLabel),
            )
        }

        // 备份忽略设置对话框 (对齐 app 端 BackupConfigHost.backupIgnore:
        // alert 标题 "恢复忽略列表" + multiChoiceItems(BackupConfig.ignoreTitle, checkedItems),
        // onDismiss 时 saveIgnoreConfig; 桌面端用 AppAlertDialog content 槽 + AppCheckbox 复刻多选)
        // 宽度 0.8 窗口宽度 (对齐其他备份相关对话框)
        if (showIgnoreDialog) {
            AppAlertDialog(
                onDismissRequest = {
                    // 对齐 app 端 onDismiss { saveIgnoreConfig() }: 关闭时持久化 ignoreConfig
                    BackupConfigShared.saveIgnoreConfig()
                    showIgnoreDialog = false
                },
                title = restoreIgnoreLabel,
                widthFraction = 0.8f,
                okButton = AlertButton(text = okLabel) {
                    // okButton 默认 dismissOnClick=true, 触发 onDismissRequest (其中含 saveIgnoreConfig)
                },
                cancelButton = AlertButton(text = cancelLabel),
            ) {
                // multiChoiceItems 正文: 列出 BackupConfigShared.ignoreTitle, 勾选写 ignoreConfig
                // (ignoreKeys 与 ignoreTitle 长度不匹配是 app 端既有数据问题,
                //  zip 安全配对避免越界, 行为与 app 端 multiChoiceItems(ignoreTitle, checkedItems) 一致)
                Column {
                    BackupConfigShared.ignoreKeys
                        .zip(BackupConfigShared.ignoreTitle)
                        .forEachIndexed { index, (key, title) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val newChecked = !ignoreChecked.getOrElse(index) { false }
                                        BackupConfigShared.ignoreConfig[key] = newChecked
                                        ignoreChecked = ignoreChecked.toMutableList()
                                            .also { it[index] = newChecked }
                                    }
                                    .padding(horizontal = 24.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppCheckbox(
                                    checked = ignoreChecked.getOrElse(index) { false },
                                    onCheckedChange = { isChecked ->
                                        BackupConfigShared.ignoreConfig[key] = isChecked
                                        ignoreChecked = ignoreChecked.toMutableList()
                                            .also { it[index] = isChecked }
                                    },
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = title,
                                    color = AppTheme.colors.primaryText,
                                    fontSize = 15.sp,
                                )
                            }
                        }
                }
            }
        }
    }
}

/**
 * 弹 JFileChooser 选目录 (备份路径设置用, 对齐 app 端 selectBackupPath.launch)。
 *
 * 必须在 IO 线程调用 (JFileChooser.showOpenDialog 阻塞当前线程直到用户选择/取消)。
 * 返回 null 表示用户取消。
 */
private fun pickDirectory(): File? = FileDialogs.pickDirectory()

/**
 * 弹 JFileChooser 选 zip 文件 (本地恢复用, 对齐 app 端 restoreFromLocal)。
 *
 * 必须在 IO 线程调用 (JFileChooser.showOpenDialog 阻塞当前线程直到用户选择/取消)。
 * 返回 null 表示用户取消。
 */
private fun pickZipFile(): File? =
    FileDialogs.pickOpenFile(extensions = listOf("zip"), extensionDesc = "ZIP (*.zip)")

private class RefreshingPreferenceStoreProvider : PreferenceStoreProvider {
    private val delegate = DesktopPreferenceStoreProvider()

    var revision by mutableIntStateOf(0)
        private set
    var lastChangedKey by mutableStateOf<String?>(null)
        private set

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        delegate.getBoolean(key, defValue)

    override fun putBoolean(key: String, value: Boolean) {
        delegate.putBoolean(key, value)
        lastChangedKey = key
        revision++
    }

    override fun getInt(key: String, defValue: Int): Int = delegate.getInt(key, defValue)

    override fun putInt(key: String, value: Int) {
        delegate.putInt(key, value)
        lastChangedKey = key
        revision++
    }

    override fun getString(key: String, defValue: String?): String? =
        delegate.getString(key, defValue)

    override fun putString(key: String, value: String?) {
        delegate.putString(key, value)
        lastChangedKey = key
        revision++
    }
}

private val webDavConfigKeys = setOf(
    PreferKey.webDavUrl,
    PreferKey.webDavAccount,
    PreferKey.webDavPassword,
    PreferKey.webDavDir,
)
