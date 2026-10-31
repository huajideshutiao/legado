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
 * 选择方式弹窗: 由所属请求 ([HandleFileSession.Request]) 创建并持有,
 * 随请求收口与宿主销毁关闭; 弹窗内发起的手工输入子弹窗与权限请求同随请求收口。
 */
internal class HandleFileDialog(
    private val activity: AppCompatActivity,
    private val request: HandleFileSession.Request,
) : ComposeDialog(activity, applyFilletBackground = false),
    FilePickerDialog.CallBack {

    private val mode = request.param.mode
    private val title = request.param.title
    private val allowExtensions = request.param.allowExtensions

    private val selectList: ArrayList<SelectItem<Int>> =
        buildSelectList().apply { request.param.otherActions?.let { addAll(it) } }

    /** 本弹窗发起的权限请求: 弹窗关闭 (请求收口/宿主销毁) 时撤销。 */
    private var permissions: PermissionsCompat? = null

    init {
        request.own(this)
        setComposeContent { Content() }
        setOnCancelListener {
            if (!request.awaitingSys) {
                request.deliver(null)
            }
        }
        setOnDismissListener {
            permissions?.cancel()
            permissions = null
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
                if (request.awaitingSys) dismiss() else request.deliver(null)
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
        request.deliver(data.data)
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
                request.launchSysTree { e ->
                    AppLog.put(androidAppString("open_sys_dir_picker_error"), e, true)
                    checkPermissions {
                        showAppPicker(HandleFileContract.DIR)
                    }
                }
            }

            HandleFileContract.FILE -> {
                request.launchSysDoc(typesOfExtensions(allowExtensions)) { e ->
                    AppLog.put(androidAppString("open_sys_dir_picker_error"), e, true)
                    checkPermissions {
                        showAppPicker(HandleFileContract.FILE)
                    }
                }
            }

            HandleFileContract.IMAGE -> {
                request.launchSysImage()
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
                request.deliver(uri)
            }
        }
    }

    private fun showAppPicker(mode: Int) {
        request.own(
            FilePickerDialog.show(
                activity,
                mode = mode,
                allowExtensions = if (mode == HandleFileContract.FILE) allowExtensions else null,
                callback = this,
            )
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
        request.own(
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
                        request.deliver(Uri.fromFile(file))
                    } else context.toastOnUi(androidAppString("invalid_directory"))
                }
                cancelButton {
                    request.deliver(null)
                }
            }
        )
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

    private fun checkPermissions(success: () -> Unit) {
        // 请求已终结资格/本UI已解绑: 不再起新权限 (旧宿主解绑后同样不再起)
        if (!request.isActive) return
        // 新权限请求前先撤销上一条未终结的请求, 避免同一弹窗叠加多个回调
        permissions?.cancel()
        permissions = PermissionsCompat.Builder().addPermissions(*Permissions.Group.STORAGE)
            .rationale(androidAppString("tip_perm_request_storage"))
            .onGranted {
                // 请求可能在权限回传前已取消/收口: 不再拉起应用内选择器
                if (request.isActive) success()
            }
            .onDenied {
                if (request.isActive) request.deliver(null)
            }
            .onError { e ->
                // 启动权限页失败/系统异常: 真实失败终结, 不冒充取消
                if (request.isActive) request.deliverError(e)
            }.request()
    }

    private fun typesOfExtensions(allowExtensions: Array<String>): Array<String> {
        val types = hashSetOf<String>()
        if (allowExtensions.isEmpty()) types.add("*/*")
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
