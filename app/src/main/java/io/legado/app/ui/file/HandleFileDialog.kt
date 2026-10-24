package io.legado.app.ui.file

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import io.legado.app.App
import io.legado.app.base.ComposeDialog
import io.legado.app.constant.AppLog
import io.legado.app.help.i18n.androidAppString
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.compose.component.AppAlertDialogContent
import io.legado.app.ui.compose.component.AppSelectorList
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.checkWrite
import io.legado.app.utils.externalFiles
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.toastOnUi
import java.io.File

/**
 * 选择方式弹窗: 由 [HandleFileSession] 创建并持有, 系统选择器的结果登记与会话参数都在
 * 会话里跨界面重建存活, 本弹窗本身随宿主 Activity 存亡 (重建后会话重开弹窗)。
 */
class HandleFileDialog(
    activity: AppCompatActivity,
    private val session: HandleFileSession,
    param: HandleFileContract.HandleFileParam,
) : ComposeDialog(activity, applyFilletBackground = false),
    FilePickerDialog.CallBack {

    private val mode = param.mode
    private val title = param.title
    private val allowExtensions = param.allowExtensions

    private val selectList: ArrayList<SelectItem<Int>> =
        buildSelectList().apply { param.otherActions?.let { addAll(it) } }

    init {
        setComposeContent { Content() }
        setOnCancelListener {
            if (!session.isAwaitingSysResult()) {
                session.deliver(null)
            }
        }
    }

    @Composable
    private fun Content() {
        val title = this.title ?: when (mode) {
            HandleFileContract.DIR -> rememberString("select_folder")
            HandleFileContract.IMAGE -> rememberString("select_image")
            else -> rememberString("select_file")
        }
        // 对齐旧 AlertDialog setItems(items, null)：点击不自动关闭，等结果回传后 dismiss
        AppAlertDialogContent(
            onDismissRequest = {
                if (session.isAwaitingSysResult()) dismiss() else session.deliver(null)
            },
            title = title,
        ) {
            AppSelectorList(items = selectList.map { it.title }) { index ->
                handleAction(selectList[index])
            }
        }
    }

    // FilePickerDialog.CallBack: 应用内选择器结果接入现有结果链
    override fun onResult(data: Intent) {
        session.deliver(data.data)
    }

    private fun buildSelectList(): ArrayList<SelectItem<Int>> = when (mode) {
        HandleFileContract.DIR_SYS -> getDirActions(true)
        HandleFileContract.DIR -> getDirActions()
        HandleFileContract.FILE -> getFileActions()
        HandleFileContract.IMAGE -> getImageActions()
        else -> arrayListOf()
    }

    private fun handleAction(item: SelectItem<Int>) {
        when (item.value) {
            HandleFileContract.DIR -> {
                runCatching { session.launchSysTree() }.onFailure {
                    session.sysPickerUnavailable()
                    AppLog.put(androidAppString("open_sys_dir_picker_error"), it, true)
                    checkPermissions {
                        showAppPicker(HandleFileContract.DIR)
                    }
                }
            }

            HandleFileContract.FILE -> {
                runCatching { session.launchSysDoc(typesOfExtensions(allowExtensions)) }
                    .onFailure {
                        session.sysPickerUnavailable()
                        AppLog.put(androidAppString("open_sys_dir_picker_error"), it, true)
                        checkPermissions {
                            showAppPicker(HandleFileContract.FILE)
                        }
                    }
            }

            HandleFileContract.IMAGE -> {
                session.launchSysImage()
            }

            10 -> checkPermissions {
                showAppPicker(HandleFileContract.DIR)
            }

            11 -> checkPermissions {
                showAppPicker(HandleFileContract.FILE)
            }

            112 -> showInputDirectoryDialog()
            else -> {
                val path = item.title
                val uri = if (path.isContentScheme()) path.toUri() else Uri.fromFile(File(path))
                session.deliver(uri)
            }
        }
    }

    private fun showAppPicker(mode: Int) {
        val activity = session.hostActivity ?: return
        FilePickerDialog.show(
            activity,
            mode = mode,
            allowExtensions = if (mode == HandleFileContract.FILE) allowExtensions else null,
            callback = this,
        )
    }

    private fun getDirActions(onlySys: Boolean = false) = if (onlySys) {
        arrayListOf(
            SelectItem(androidAppString("sys_folder_picker"), HandleFileContract.DIR),
            SelectItem(androidAppString("manual_input"), 112)
        )
    } else {
        arrayListOf(
            SelectItem(androidAppString("sys_folder_picker"), HandleFileContract.DIR),
            SelectItem(androidAppString("app_folder_picker"), 10),
            SelectItem(androidAppString("manual_input"), 112)
        )
    }

    private fun getFileActions() = arrayListOf(
        SelectItem(androidAppString("sys_file_picker"), HandleFileContract.FILE),
        SelectItem(androidAppString("app_file_picker"), 11)
    )

    private fun getImageActions() = arrayListOf(
        SelectItem(
            androidAppString("sys_image_picker"),
            HandleFileContract.IMAGE
        )
    ).apply { addAll(getFileActions()) }

    private fun showInputDirectoryDialog() {
        context.alert(androidAppString("manual_input")) {
            val getText = editTextView(hint = androidAppString("enter_directory_path"))
            okButton {
                val inputPath = getText()
                if (inputPath.isBlank()) {
                    context.toastOnUi(androidAppString("empty_directory_input"))
                    return@okButton
                }
                val file = File(inputPath)
                if (file.exists() && file.isDirectory && isExternalStorage(file) && file.checkWrite()) {
                    session.deliver(Uri.fromFile(file))
                } else context.toastOnUi(androidAppString("invalid_directory"))
            }
            cancelButton {
                session.deliver(null)
            }
        }
    }

    private fun isExternalStorage(path: File): Boolean {
        if (path.canonicalPath.startsWith(App.instance.externalFiles.parent!!)) {
            return false
        }
        try {
            if (Environment.isExternalStorageEmulated(path)) {
                return true
            }
        } catch (_: IllegalArgumentException) {
        }
        try {
            if (Environment.isExternalStorageRemovable(path)) {
                return true
            }
        } catch (_: IllegalArgumentException) {
        }
        return false
    }

    private fun checkPermissions(success: (() -> Unit)?) {
        PermissionsCompat.Builder().addPermissions(*Permissions.Group.STORAGE)
            .rationale(androidAppString("tip_perm_request_storage"))
            .onGranted { success?.invoke() }.onDenied {
            session.deliver(null)
        }.onError {
            session.deliver(null)
        }.request()
    }

    private fun typesOfExtensions(allowExtensions: Array<String>?): Array<String> {
        val types = hashSetOf<String>()
        if (allowExtensions.isNullOrEmpty()) types.add("*/*")
        else allowExtensions.forEach {
            when (it) {
                "*" -> types.add("*/*")
                "txt", "xml" -> types.add("text/*")
                else -> {
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
                        ?: "application/octet-stream"
                    types.add(mime)
                }
            }
        }
        return types.toTypedArray()
    }
}
