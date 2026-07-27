package io.legado.app.ui.config

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.BackupConfig
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.checkWrite
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.showHelp
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份设置宿主（原 BackupConfigFragment 壳上浮）。
 * WebDav 各项写 prefs 后仍走 OnSharedPreferenceChangeListener 承接原副作用（刷 summary + upWebDavConfig）；
 * 菜单/权限/文件选择 launcher/WaitDialog 逐字保留。
 */
class BackupConfigHost(activity: ConfigActivity) : ConfigHost(activity),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val viewModel get() = activity.viewModel

    private var webDavUrlSummary by mutableStateOf("")
    private var webDavAccountSummary by mutableStateOf("")
    private var webDavPasswordSummary by mutableStateOf("")
    private var webDavDirSummary by mutableStateOf("")
    private var webDavDeviceNameSummary by mutableStateOf("")
    private var backupPathSummary by mutableStateOf("")

    private val selectBackupPath by lazy {
        activity.registerHandleFile { result ->
            result.uri?.let { uri ->
                if (uri.isContentScheme()) {
                    AppConfig.backupPath = uri.toString()
                } else {
                    AppConfig.backupPath = uri.path
                }
            }
        }
    }
    private val backupDir by lazy {
        activity.registerHandleFile { result ->
            result.uri?.let { uri ->
                if (uri.isContentScheme()) {
                    AppConfig.backupPath = uri.toString()
                    backup(uri.toString())
                } else {
                    uri.path?.let { path ->
                        AppConfig.backupPath = path
                        backup(path)
                    }
                }
            }
        }
    }
    private val restoreDoc by lazy {
        activity.registerHandleFile { result ->
            result.uri?.let { uri ->
                viewModel.restore(uri)
            }
        }
    }

    init {
        webDavUrlSummary = urlSummary(AppConfig.webDavUrl)
        webDavAccountSummary = accountSummary(AppConfig.webDavAccount)
        webDavPasswordSummary = passwordSummary(AppConfig.webDavPassword)
        webDavDirSummary = dirSummary(AppConfig.webDavDir)
        webDavDeviceNameSummary = AppConfig.webDavDeviceName ?: ""
        backupPathSummary = AppConfig.backupPath
            ?: activity.getString(R.string.select_backup_path)
        activity.defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        if (!LocalConfig.backupHelpVersionIsLast) {
            activity.showHelp("webDavHelp")
        }
        viewModel.backupRestoreState.observe(activity) { msg ->
            if (msg != null) {
                WaitDialog.from(activity)
                    .setText(msg)
                    .apply {
                        onCancelListener = { viewModel.cancelBackupRestore() }
                    }
                    .show()
            } else {
                WaitDialog.dismiss(activity)
            }
        }
    }

    @Composable
    override fun Content() {
        Column(Modifier.fillMaxSize()) {
            AppTitleBar(
                title = stringResource(R.string.backup_restore),
                onBack = { activity.finish() },
                actions = {
                    IconButton(onClick = { activity.showHelp("webDavHelp") }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_help),
                            contentDescription = stringResource(R.string.help),
                            tint = AppTheme.colors.primaryText,
                        )
                    }
                },
            )
            Box(Modifier.weight(1f)) {
                BackupConfigScreen(
                    webDavUrlSummary = webDavUrlSummary,
                    webDavAccountSummary = webDavAccountSummary,
                    webDavPasswordSummary = webDavPasswordSummary,
                    webDavDirSummary = webDavDirSummary,
                    webDavDeviceNameSummary = webDavDeviceNameSummary,
                    backupPathSummary = backupPathSummary,
                    onBackupPath = { selectBackupPath.launch() },
                    onWebDavBackup = { backup() },
                    // 长按备份按钮：只备份到本地，不上传到 WebDav
                    onWebDavBackupLong = { backup(uploadToWebDav = false) },
                    onWebDavRestore = { restore() },
                    onWebDavRestoreLong = { restoreFromLocal() },
                    onRestoreIgnore = ::backupIgnore,
                )
            }
        }
    }

    override fun onDestroy() {
        activity.defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        WaitDialog.dismiss(activity)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.backupPath -> backupPathSummary =
                AppConfig.backupPath ?: activity.getString(R.string.select_backup_path)

            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir -> activity.window.decorView.post {
                val value = when (key) {
                    PreferKey.webDavUrl -> AppConfig.webDavUrl
                    PreferKey.webDavAccount -> AppConfig.webDavAccount
                    PreferKey.webDavPassword -> AppConfig.webDavPassword
                    else -> AppConfig.webDavDir
                }
                upWebDavSummary(key, value)
                viewModel.upWebDavConfig()
            }

            PreferKey.webDavDeviceName ->
                webDavDeviceNameSummary = AppConfig.webDavDeviceName ?: ""
        }
    }

    private fun upWebDavSummary(key: String, value: String?) {
        when (key) {
            PreferKey.webDavUrl -> webDavUrlSummary = urlSummary(value)
            PreferKey.webDavAccount -> webDavAccountSummary = accountSummary(value)
            PreferKey.webDavPassword -> webDavPasswordSummary = passwordSummary(value)
            PreferKey.webDavDir -> webDavDirSummary = dirSummary(value)
        }
    }

    private fun urlSummary(value: String?): String =
        if (value == null) activity.getString(R.string.web_dav_url_s) else value

    private fun accountSummary(value: String?): String =
        if (value.isNullOrBlank()) activity.getString(R.string.web_dav_account_s) else value

    private fun passwordSummary(value: String?): String =
        if (value.isNullOrEmpty()) activity.getString(R.string.web_dav_pw_s) else "*".repeat(value.length)

    private fun dirSummary(value: String?): String = value ?: "legado"

    /**
     * 备份忽略设置
     */
    private fun backupIgnore() {
        val checkedItems = BooleanArray(BackupConfig.ignoreKeys.size) {
            BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[it]] ?: false
        }
        activity.alert(R.string.restore_ignore) {
            multiChoiceItems(BackupConfig.ignoreTitle, checkedItems) { _, which, isChecked ->
                BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[which]] = isChecked
            }
            onDismiss {
                BackupConfig.saveIgnoreConfig()
            }
        }
    }

    fun backup(uploadToWebDav: Boolean = true) {
        val backupPath = AppConfig.backupPath
        if (backupPath.isNullOrEmpty()) {
            // 如果没有设置备份路径，需要先选择路径
            // 此时无法区分是否上传到 WebDav，默认按用户选择的路径处理
            backupDir.launch()
        } else {
            if (backupPath.isContentScheme()) {
                activity.lifecycleScope.launch {
                    val canWrite = withContext(IO) {
                        FileDoc.fromDir(backupPath).checkWrite()
                    }
                    if (canWrite) {
                        backup(backupPath, uploadToWebDav)
                    } else {
                        backupDir.launch()
                    }
                }
            } else {
                backupUsePermission(backupPath, uploadToWebDav)
            }
        }
    }

    private fun backup(backupPath: String, uploadToWebDav: Boolean = true) {
        viewModel.backup(backupPath, uploadToWebDav)
    }

    private fun backupUsePermission(path: String, uploadToWebDav: Boolean = true) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                backup(path, uploadToWebDav)
            }
            .request()
    }

    fun restore() {
        viewModel.loadBackupNames { names ->
            if (names.isNotEmpty()) {
                activity.selector(
                    title = activity.getString(R.string.select_restore_file),
                    items = names
                ) { _, index ->
                    if ((index in 0 until names.size)) {
                        activity.window.decorView.post {
                            viewModel.restoreWebDav(names[index])
                        }
                    }
                }
            } else {
                activity.alert {
                    setTitle(R.string.restore)
                    setMessage("WebDav无备份文件\n将从本地备份恢复。")
                    okButton {
                        restoreFromLocal()
                    }
                    cancelButton()
                }
            }
        }
    }

    private fun restoreFromLocal() {
        restoreDoc.launch {
            title = activity.getString(R.string.select_restore_file)
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

}
