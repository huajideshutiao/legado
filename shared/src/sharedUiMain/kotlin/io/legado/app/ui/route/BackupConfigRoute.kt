package io.legado.app.ui.route

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.HelpVersion
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.LocalConfigProviders
import io.legado.app.help.config.LocalConfigShared
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.storage.BackupConfigShared
import io.legado.app.help.storage.BackupShared
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.help.storage.RestoreShared
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.config.BackupConfigScreen
import io.legado.app.ui.config.BackupConfigScreenModel
import io.legado.app.ui.config.BackupConfigUiEvent
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.widget.dialog.HelpDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.backup_restore
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.help
import legado.shared.generated.resources.ic_help
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.restore
import legado.shared.generated.resources.restore_ignore
import legado.shared.generated.resources.select_backup_path
import legado.shared.generated.resources.select_restore_file
import legado.shared.generated.resources.web_dav_account_s
import legado.shared.generated.resources.web_dav_pw_s
import legado.shared.generated.resources.web_dav_url_s
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * BackupConfig 路由下沉函数: 装配 shared [BackupConfigScreenModel] + [BackupConfigScreen]。
 *
 * 点击型交互经构造函数 lambda 注入:
 * - 从本地 zip 恢复: 用 [PlatformServiceProviders] 文件选择器 + [RestoreShared] 实现
 *   (对照 app 端 restoreDoc.launch + viewModel.restore)
 * - 备份忽略项: 用 [AppAlertDialog] 多选列表 + [BackupConfigShared] 实现
 *   (对照 app 端 backupIgnore alert multiChoiceItems)
 * - 选择备份路径: [PlatformServiceProviders.files.pickDirectory] + 写 PreferKey.backupPath
 *   (对照 app 端 selectBackupPath.launch + AppConfig.backupPath = uri.toString())
 * - 备份: [BackupShared.backupLocked] + [WaitDialog] (对照 app 端 BackupConfigHost.backup + WaitDialog)
 * - 恢复: [AppWebDavShared.upConfig] + [AppWebDavShared.getBackupNames] + [AppSelectorDialog] +
 *   [AppWebDavShared.restoreWebDav] + [WaitDialog]; WebDav 无备份时弹 [AppAlertDialog] 回退本地
 *   (对照 app 端 BackupConfigHost.restore + viewModel.loadBackupNames + selector + restoreWebDav)
 */
@Composable
fun BackupConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val pref = LocalPreferenceStoreProvider.current
    val appConfig = remember { AppConfigProviders.get() }

    // Summary 占位符 (对照 app 端 getString(R.string.xxx))
    val webDavUrlSStr = stringResource(Res.string.web_dav_url_s)
    val webDavAccountSStr = stringResource(Res.string.web_dav_account_s)
    val webDavPwSStr = stringResource(Res.string.web_dav_pw_s)
    val selectBackupPathStr = stringResource(Res.string.select_backup_path)
    // 顶栏标题 (对照 app 端 R.string.backup_restore)
    val titleStr = stringResource(Res.string.backup_restore)
    // 动作/对话框文案 (对照 app 端 R.string.restore/select_restore_file/ok/cancel)
    val restoreStr = stringResource(Res.string.restore)
    val selectRestoreFileStr = stringResource(Res.string.select_restore_file)
    val okStr = stringResource(Res.string.ok)
    val cancelStr = stringResource(Res.string.cancel)
    // 备份成功/失败 toast (对照 app 端 R.string.backup_success / backup_fail)
    val backupSuccessStr = stringResource(Res.string.backup_success)
    val backupFailStr = stringResource(Res.string.backup_fail)
    // 恢复列表加载等待文案 (对照 app 端 R.string.loading)
    val loadingStr = stringResource(Res.string.loading)

    // 帮助对话框 (对照 app 端 menu_help + 首次进入 showHelp("webDavHelp"))
    var showHelpDialog by remember { mutableStateOf(false) }
    var showRestoreIgnore by remember { mutableStateOf(false) }
    // WaitDialog 状态 + 后台任务 Job (取消用), 对照 app 端 WaitDialog.from(activity) + onCancelListener
    var waitDialogMessage by remember { mutableStateOf<String?>(null) }
    var backupRestoreJob by remember { mutableStateOf<Job?>(null) }
    // WebDav 备份列表 selector 状态 (对照 app 端 activity.selector)
    var showRestoreSelector by remember { mutableStateOf(false) }
    var backupNames by remember { mutableStateOf<List<String>>(emptyList()) }
    // WebDav 无备份时回退本地 alert (对照 app 端 activity.alert "WebDav无备份文件")
    var showNoBackupAlert by remember { mutableStateOf(false) }

    // lateinit 让 onBackup/onSelectBackupPath lambda 内可引用 screenModel.dispatch
    // (factory 内部 lambda 延迟执行, 实际调用时 screenModel 已赋值)
    lateinit var screenModel: BackupConfigScreenModel

    // 启动备份协程: WaitDialog 显示 backup 文案, 完成后自动隐藏, Job 暂存供 WaitDialog 取消
    // (对照 app 端 WaitDialog.from(activity).setText("备份") + viewModel.backup)
    // 协程跑在 screenModel.scope (生命周期与 ScreenModel 绑定, 而非组合), 修复组合销毁后
    // 延迟回调 scope.launch 抛 ForgottenCoroutineScopeException
    val startBackup: (String, Boolean) -> Unit = { backupPath, uploadToWebDav ->
        backupRestoreJob = screenModel.scope.launch {
            // 对照 app 端 ConfigViewModel.backup: "备份中…" → backupLocked → 成功 toast 备份成功
            waitDialogMessage = "备份中…"
            try {
                withContext(IoDispatcher) {
                    // 对照 app 端 WebDav 配置变更即刷新 authorization (upWebDavConfig):
                    // KMP 无 prefs 变更承接, 备份前显式 upConfig 刷新, 失败不阻断本地备份
                    runCatching { AppWebDavShared.upConfig() }
                        .onFailure {
                            AppLog.put("BackupConfigRoute onBackup upConfig 失败\n${it.message}", it)
                        }
                }
                withContext(IoDispatcher) {
                    BackupShared.backupLocked(backupPath, uploadToWebDav)
                }
                // 对照 app 端 onSuccess { toastOnUi(R.string.backup_success) }
                Toasters.get().toast(backupSuccessStr)
            } catch (e: CancellationException) {
                // 取消不当作失败上报 (对照 app 端 Coroutine dispatchCallback 的 isActive 守卫)
                throw e
            } catch (e: Exception) {
                // 对照 app 端 onError { AppLog.put("备份出错\n...") + toastOnUi(R.string.backup_fail, ...) }
                AppLog.put("备份出错\n${e.message}", e)
                Toasters.get().toast(backupFailStr.format(e.message))
            } finally {
                waitDialogMessage = null
            }
        }
    }

    // 从本地 zip 恢复: 先 SAF 选文件 (弹出期间不亮等待态), 选完才亮"恢复"转圈;
    // Job 暂存供 WaitDialog 取消。两入口共用, 对照 app 端 restoreFromLocal()
    // (restoreDoc.launch 回调里才调 viewModel.restore(uri) → WaitDialog)
    val startRestoreFromLocal: () -> Unit = {
        val services = PlatformServiceProviders.getOrNull()
        if (services != null) {
            backupRestoreJob = screenModel.scope.launch {
                val path = withContext(IoDispatcher) {
                    services.files.pickFile(FileFilter(extensions = listOf("zip")))
                } ?: return@launch
                waitDialogMessage = "恢复中…"
                try {
                    // 对照 app 端 viewModel.restore(uri) → Restore.restore: 失败由内部 runCatching
                    // AppLog+toast 上报 (RestoreShared 内已对齐原版"恢复备份出错"), 本层不重复上报
                    withContext(IoDispatcher) { RestoreShared.restoreFromZip(path) }
                } finally {
                    waitDialogMessage = null
                }
            }
        }
    }

    screenModel = screenModelStore.getOrCreateTyped(entry) {
        BackupConfigScreenModel(
            // 备份: 读 backupPath, 空则用平台默认落地目录 (桌面=文档目录), 平台要求选目录时先 SAF 选;
            // 非空直接 BackupShared.backupLocked + WaitDialog
            // (对照 app 端 BackupConfigHost.backup + viewModel.backup + WaitDialog)
            onBackup = { uploadToWebDav ->
                val services = PlatformServiceProviders.getOrNull()
                if (services != null) {
                    val backupPath = pref.getString(PreferKey.backupPath)
                    val defaultDir = DataStorageProviders.getOrNull()?.defaultBackupDir
                    if (backupPath.isNullOrBlank() && defaultDir != null) {
                        // 桌面/iOS: 有平台惯例目录, 不打扰用户 (不写 prefs, 设置项仍显示"未设置")
                        startBackup(defaultDir, uploadToWebDav)
                    } else if (backupPath.isNullOrBlank()) {
                        // 没设路径: SAF 选目录, 选完写 prefs + 立即备份 (对照 app 端 backupDir.launch)
                        screenModel.scope.launch {
                            val path = withContext(IoDispatcher) { services.files.pickDirectory() }
                            if (path != null) {
                                pref.putString(PreferKey.backupPath, path)
                                screenModel.dispatch(
                                    BackupConfigUiEvent.UpdateBackupPathSummary(path)
                                )
                                startBackup(path, uploadToWebDav)
                            }
                        }
                    } else {
                        // 对照 app 端 backup(): 已设路径先 checkWrite, 不可写则重新选目录再备份
                        screenModel.scope.launch {
                            val canWrite = withContext(IoDispatcher) {
                                services.files.checkWrite(backupPath)
                            }
                            if (canWrite) {
                                startBackup(backupPath, uploadToWebDav)
                            } else {
                                // 对照 app 端 backupDir.launch(): 重新选目录, 选完写 prefs + 立即备份
                                val path = withContext(IoDispatcher) {
                                    services.files.pickDirectory()
                                }
                                if (path != null) {
                                    pref.putString(PreferKey.backupPath, path)
                                    screenModel.dispatch(
                                        BackupConfigUiEvent.UpdateBackupPathSummary(path)
                                    )
                                    startBackup(path, uploadToWebDav)
                                }
                            }
                        }
                    }
                }
            },
            // 恢复: upConfig + getBackupNames, 有则 AppSelectorDialog 选 → restoreWebDav; 无则 alert 回退本地
            // (对照 app 端 BackupConfigHost.restore + viewModel.loadBackupNames + selector + restoreWebDav)
            onRestore = {
                val services = PlatformServiceProviders.getOrNull()
                if (services != null) {
                    backupRestoreJob = screenModel.scope.launch {
                        // 对照 app 端 loadBackupNames: 列表加载显示 R.string.loading
                        waitDialogMessage = loadingStr
                        try {
                            withContext(IoDispatcher) {
                                runCatching { AppWebDavShared.upConfig() }
                                    .onFailure {
                                        AppLog.put(
                                            "BackupConfigRoute onRestore upConfig 失败\n${it.message}",
                                            it
                                        )
                                    }
                            }
                            // 对照 app 端 loadBackupNames onSuccess/onError:
                            // 拉取失败 toast "WebDavError\n{msg}" 后仍按空列表走"无备份"回退本地;
                            // 坚果云列表 >700 条时 toast 清理提示
                            var names: List<String> = emptyList()
                            var errorMsg: String? = null
                            var tooManyHint: String? = null
                            withContext(IoDispatcher) {
                                try {
                                    names = AppWebDavShared.getBackupNames()
                                    if (AppWebDavShared.isJianGuoYun && names.size > 700) {
                                        tooManyHint = "由于坚果云限制列出文件数量，部分备份可能未显示，请及时清理旧备份"
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    AppLog.put("获取WebDav备份列表出错\n${e.message}", e)
                                    errorMsg = "WebDavError\n${e.message}"
                                }
                            }
                            waitDialogMessage = null
                            errorMsg?.let { Toasters.get().toast(it) }
                            tooManyHint?.let { Toasters.get().toast(it) }
                            if (names.isNotEmpty()) {
                                backupNames = names
                                showRestoreSelector = true
                            } else {
                                showNoBackupAlert = true
                            }
                        } finally {
                            // 正常完成/取消/异常均清 WaitDialog
                            waitDialogMessage = null
                        }
                    }
                }
            },
            onRestoreFromLocal = {
                // 对照 app 端 restoreDoc.launch (HandleFileContract.FILE + allowExtensions=zip)
                startRestoreFromLocal()
            },
            // SAF 选目录 + 写 prefs + 更新 summary
            // (对照 app 端 selectBackupPath.launch + AppConfig.backupPath = uri.toString())
            onSelectBackupPath = {
                val services = PlatformServiceProviders.getOrNull()
                if (services != null) {
                    screenModel.scope.launch {
                        val path = withContext(IoDispatcher) { services.files.pickDirectory() }
                        if (path != null) {
                            pref.putString(PreferKey.backupPath, path)
                            screenModel.dispatch(BackupConfigUiEvent.UpdateBackupPathSummary(path))
                        }
                    }
                }
            },
            onRestoreIgnore = { showRestoreIgnore = true },
        )
    }
    val state by screenModel.state.collectAsState()

    // 对照 app 端 init: 初始化 6 个动态 summary
    LaunchedEffect(Unit) {
        // 对照 app 端 onViewCreated: 首次进入弹 webDavHelp (LocalConfig.backupHelpVersionIsLast)
        // 版本标记存 "local" prefs (LocalConfigStore), 与 app 端 LocalConfig 同存储, 老用户升级不重复弹
        val local = LocalConfigProviders.get()
        val isLastHelp = LocalConfigShared.isLastVersion(
            lastVersion = HelpVersion.backupHelp,
            versionKey = LocalConfigKeys.backupHelpVersion,
            firstOpenKey = LocalConfigKeys.firstBackup,
            getInt = local::getInt,
            getBoolean = local::getBoolean,
            putInt = local::putInt,
        )
        if (!isLastHelp) showHelpDialog = true
        if (state.webDavUrlSummary.isEmpty()) {
            screenModel.dispatch(
                BackupConfigUiEvent.UpdateWebDavUrlSummary(
                    appConfig.webDavUrl.ifBlank { webDavUrlSStr }
                )
            )
        }
        if (state.webDavAccountSummary.isEmpty()) {
            screenModel.dispatch(
                BackupConfigUiEvent.UpdateWebDavAccountSummary(
                    appConfig.webDavAccount.ifBlank { webDavAccountSStr }
                )
            )
        }
        if (state.webDavPasswordSummary.isEmpty()) {
            val pw = appConfig.webDavPassword
            screenModel.dispatch(
                BackupConfigUiEvent.UpdateWebDavPasswordSummary(
                    if (pw.isEmpty()) webDavPwSStr else "*".repeat(pw.length)
                )
            )
        }
        if (state.webDavDirSummary.isEmpty()) {
            screenModel.dispatch(
                BackupConfigUiEvent.UpdateWebDavDirSummary(appConfig.webDavDir)
            )
        }
        if (state.webDavDeviceNameSummary.isEmpty()) {
            screenModel.dispatch(
                BackupConfigUiEvent.UpdateWebDavDeviceNameSummary(appConfig.webDavDeviceName)
            )
        }
        if (state.backupPathSummary.isEmpty()) {
            // 未设置时显示平台默认落地目录 (桌面=文档目录), 让用户知道备份实际存在哪
            screenModel.dispatch(
                BackupConfigUiEvent.UpdateBackupPathSummary(
                    pref.getString(PreferKey.backupPath)?.takeIf { it.isNotBlank() }
                        ?: DataStorageProviders.getOrNull()?.defaultBackupDir
                        ?: selectBackupPathStr
                )
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleStr,
            onBack = { navigator.pop() },
            actions = {
                // 对照 app 端 menu_help → showHelp("webDavHelp")
                IconButton(onClick = { showHelpDialog = true }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_help),
                        contentDescription = stringResource(Res.string.help),
                        tint = AppTheme.colors.primaryText,
                    )
                }
            },
        )
        BackupConfigScreen(
            webDavUrlSummary = state.webDavUrlSummary,
            webDavAccountSummary = state.webDavAccountSummary,
            webDavPasswordSummary = state.webDavPasswordSummary,
            webDavDirSummary = state.webDavDirSummary,
            webDavDeviceNameSummary = state.webDavDeviceNameSummary,
            backupPathSummary = state.backupPathSummary,
            onBackupPath = { screenModel.dispatch(BackupConfigUiEvent.SelectBackupPath) },
            onWebDavBackup = { screenModel.dispatch(BackupConfigUiEvent.Backup) },
            onWebDavBackupLong = { screenModel.dispatch(BackupConfigUiEvent.BackupLocal) },
            onWebDavRestore = { screenModel.dispatch(BackupConfigUiEvent.Restore) },
            onWebDavRestoreLong = { screenModel.dispatch(BackupConfigUiEvent.RestoreFromLocal) },
            onRestoreIgnore = { screenModel.dispatch(BackupConfigUiEvent.RestoreIgnore) },
        )
    }

    // 备份忽略项多选对话框 (对照 app 端 backupIgnore: alert multiChoiceItems)
    if (showRestoreIgnore) {
        val titles = remember { BackupConfigShared.ignoreTitle.toList() }
        val keys = remember { BackupConfigShared.ignoreKeys.toList() }
        // 对照 app 端 checkedItems = BooleanArray(ignoreKeys.size) { ignoreConfig[keys[it]] ?: false }
        // 用 mutableStateListOf 让勾选状态变化能触发 Checkbox 重组
        val checkedStates = remember {
            mutableStateListOf<Boolean>().apply {
                repeat(keys.size) { add(BackupConfigShared.ignoreConfig[keys[it]] ?: false) }
            }
        }
        AppAlertDialog(
            onDismissRequest = {
                // 对照 app 端 onDismiss { saveIgnoreConfig() }
                BackupConfigShared.saveIgnoreConfig()
                showRestoreIgnore = false
            },
            title = stringResource(Res.string.restore_ignore),
            okButton = AlertButton(text = stringResource(Res.string.ok)) {
                BackupConfigShared.saveIgnoreConfig()
                showRestoreIgnore = false
            },
        ) {
            // 多选列表 (对照 app 端 multiChoiceItems 交互; 样式为 Compose 近似: 24/8 padding + 12 间距 + toggleable Checkbox)
            Column {
                titles.forEachIndexed { index, title ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checkedStates[index],
                                role = Role.Checkbox,
                                onValueChange = {
                                    checkedStates[index] = it
                                    BackupConfigShared.ignoreConfig[keys[index]] = it
                                },
                            )
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppCheckbox(
                            checked = checkedStates[index],
                            onCheckedChange = null,
                        )
                        Text(
                            text = title,
                            color = AppTheme.colors.primaryText,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    // 备份/恢复进度对话框 (对照 app 端 WaitDialog.from(activity).setText + onCancelListener)
    waitDialogMessage?.let { msg ->
        WaitDialog(
            visible = true,
            message = msg,
            onDismissRequest = {
                // 对照 app 端 onCancelListener = { viewModel.cancelBackupRestore() }
                backupRestoreJob?.cancel()
                waitDialogMessage = null
            },
        )
    }

    // WebDav 备份列表 selector (对照 app 端 activity.selector(title, items))
    if (showRestoreSelector) {
        AppSelectorDialog(
            onDismissRequest = { showRestoreSelector = false },
            title = selectRestoreFileStr,
            items = backupNames,
            onItemSelected = { index ->
                val name = backupNames.getOrNull(index)
                if (name != null) {
                    showRestoreSelector = false
                    backupRestoreJob = screenModel.scope.launch {
                        waitDialogMessage = "恢复中…"
                        try {
                            withContext(IoDispatcher) { AppWebDavShared.restoreWebDav(name) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 对照 app 端 onError { AppLog.put("WebDav恢复出错\n...") + toastOnUi("WebDav恢复出错\n...") }
                            AppLog.put("WebDav恢复出错\n${e.message}", e)
                            Toasters.get().toast("WebDav恢复出错\n${e.message}")
                        } finally {
                            waitDialogMessage = null
                        }
                    }
                }
            },
        )
    }

    // WebDav 无备份 alert (对照 app 端 activity.alert "WebDav无备份文件, 将从本地备份恢复")
    if (showNoBackupAlert) {
        AppAlertDialog(
            onDismissRequest = { showNoBackupAlert = false },
            title = restoreStr,
            okButton = AlertButton(text = okStr) {
                showNoBackupAlert = false
                // 回退本地: 同 onRestoreFromLocal (对照 app 端 alert okButton { restoreFromLocal() })
                startRestoreFromLocal()
            },
            cancelButton = AlertButton(text = cancelStr),
        ) {
            // 对照 app 端 setMessage("WebDav无备份文件\n将从本地备份恢复。")
            Text(
                text = "WebDav无备份文件\n将从本地备份恢复。",
                color = AppTheme.colors.primaryText,
            )
        }
    }

    // 帮助对话框 (对照 app 端 showHelp("webDavHelp"): menu_help + 首次进入)
    if (showHelpDialog) {
        HelpDialog(fileName = "webDavHelp", onDismiss = { showHelpDialog = false })
    }
}
