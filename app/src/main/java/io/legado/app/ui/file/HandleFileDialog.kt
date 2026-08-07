package io.legado.app.ui.file

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.i18n.androidAppString
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.compose.component.AppAlertDialogContent
import io.legado.app.ui.compose.component.AppSelectorList
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.SelectImageContract
import io.legado.app.utils.checkWrite
import io.legado.app.utils.externalFiles
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File

class HandleFileDialog : BaseComposeDialogFragment() {

    // 正文用 AppAlertDialogContent 自带 Surface 圆角，宿主不再叠 filletBackground
    override val applyFilletBackground: Boolean = false

    companion object {
        fun show(
            fragmentManager: FragmentManager,
            mode: Int = 0,
            title: String? = null,
            allowExtensions: Array<String>? = null,
            otherActions: ArrayList<SelectItem<Int>>? = null,
            fileData: HandleFileContract.FileData? = null,
            value: String? = null,
            requestCode: Int = 0
        ) {
            val dialog = HandleFileDialog().apply {
                arguments = Bundle().apply {
                    putInt("mode", mode)
                    putString("title", title)
                    putStringArray("allowExtensions", allowExtensions)
                    putSerializable("otherActions", otherActions)
                    putSerializable("fileData", fileData)
                    putString("value", value)
                    putInt("requestCode", requestCode)
                }
            }
            dialog.show(fragmentManager, "handleFileDialog")
        }
    }

    private var mode = 0
    private var requestCode: Int = 0
    private var isLaunchingResult = false
    private var allowExtensions: Array<String>? = null
    private var selectList: ArrayList<SelectItem<Int>> = arrayListOf()
    private val viewModel by viewModels<HandleFileViewModel>()

    private val selectDocTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            isLaunchingResult = false
            uri?.let {
                if (uri.isContentScheme()) {
                    val modeFlags =
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    requireContext().contentResolver.takePersistableUriPermission(uri, modeFlags)
                }
                onResult(uri)
            } ?: onResult(null)
        }

    private val selectDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            isLaunchingResult = false
            uri?.let {
                if (it.isContentScheme()) {
                    val modeFlags =
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    requireContext().contentResolver.takePersistableUriPermission(it, modeFlags)
                }
                onResult(it)
            } ?: onResult(null)
        }

    private val selectImage = registerForActivityResult(SelectImageContract()) {
        isLaunchingResult = false
        it.uri?.let { uri ->
            onResult(uri)
        } ?: onResult(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = arguments?.getInt("mode") ?: 0
        requestCode = arguments?.getInt("requestCode") ?: 0
        allowExtensions = arguments?.getStringArray("allowExtensions")

        viewModel.errorLiveData.observe(this) {
            toastOnUi(it)
            dismiss()
        }

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val otherActions =
            arguments?.getSerializable("otherActions") as? ArrayList<SelectItem<Int>>

        selectList = buildSelectList()
        otherActions?.let { selectList.addAll(it) }
    }

    @Composable
    override fun Content() {
        val title = arguments?.getString("title") ?: when (mode) {
            HandleFileContract.EXPORT -> rememberString("export")
            HandleFileContract.DIR -> rememberString("select_folder")
            HandleFileContract.IMAGE -> rememberString("select_image")
            else -> rememberString("select_file")
        }
        // 对齐旧 AlertDialog setItems(items, null)：点击不自动关闭，等结果回传后 dismiss
        AppAlertDialogContent(
            onDismissRequest = { dismiss() },
            title = title,
        ) {
            AppSelectorList(items = selectList.map { it.title }) { index ->
                handleAction(selectList[index])
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        if (!isLaunchingResult) {
            onResult(null)
        }
    }

    private fun buildSelectList(): ArrayList<SelectItem<Int>> = when (mode) {
        HandleFileContract.DIR_SYS -> getDirActions(true)
        HandleFileContract.DIR -> getDirActions()
        HandleFileContract.FILE -> getFileActions()
        HandleFileContract.EXPORT -> arrayListOf(
            SelectItem(
                androidAppString("upload_url"),
                111
            )
        ).apply {
            addAll(getDirActions())
        }

        HandleFileContract.IMAGE -> getImageActions()
        else -> arrayListOf()
    }

    private fun handleAction(item: SelectItem<Int>) {
        when (item.value) {
            HandleFileContract.DIR -> {
                isLaunchingResult = true
                kotlin.runCatching { selectDocTree.launch(null) }.onFailure {
                    isLaunchingResult = false
                    AppLog.put(androidAppString("open_sys_dir_picker_error"), it, true)
                    checkPermissions {
                        FilePickerDialog.show(childFragmentManager, mode = HandleFileContract.DIR)
                    }
                }
            }

            HandleFileContract.FILE -> {
                isLaunchingResult = true
                kotlin.runCatching { selectDoc.launch(typesOfExtensions(allowExtensions)) }
                    .onFailure {
                        isLaunchingResult = false
                        AppLog.put(androidAppString("open_sys_dir_picker_error"), it, true)
                        checkPermissions {
                            FilePickerDialog.show(
                                childFragmentManager,
                                mode = HandleFileContract.FILE,
                                allowExtensions = allowExtensions
                            )
                        }
                    }
            }

            HandleFileContract.IMAGE -> {
                isLaunchingResult = true
                selectImage.launch()
            }

            10 -> checkPermissions {
                FilePickerDialog.show(childFragmentManager, mode = HandleFileContract.DIR)
            }

            11 -> checkPermissions {
                FilePickerDialog.show(
                    childFragmentManager,
                    mode = HandleFileContract.FILE,
                    allowExtensions = allowExtensions
                )
            }

            111 -> getFileData()?.let { fileData ->
                viewModel.upload(fileData.name, fileData.data, fileData.type) { url ->
                    deliverResult(url.toUri())
                }
            }

            112 -> showInputDirectoryDialog()
            else -> {
                val path = item.title
                val uri = if (path.isContentScheme()) path.toUri() else Uri.fromFile(File(path))
                onResult(uri)
            }
        }
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
        alert(androidAppString("manual_input")) {
            val getText = editTextView(hint = androidAppString("enter_directory_path"))
            okButton {
                val inputPath = getText()
                if (inputPath.isBlank()) {
                    toastOnUi(androidAppString("empty_directory_input"))
                    return@okButton
                }
                val file = File(inputPath)
                if (file.exists() && file.isDirectory && isExternalStorage(file) && file.checkWrite()) {
                    onResult(Uri.fromFile(file))
                } else toastOnUi(androidAppString("invalid_directory"))
            }
            cancelButton {
                onResult(null)
            }
        }
    }

    private fun isExternalStorage(path: File): Boolean {
        if (path.canonicalPath.startsWith(appCtx.externalFiles.parent!!)) {
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

    private fun getFileData(): HandleFileContract.FileData? {
        @Suppress("DEPRECATION")
        return arguments?.getSerializable("fileData") as? HandleFileContract.FileData
    }

    private fun checkPermissions(success: (() -> Unit)?) {
        PermissionsCompat.Builder().addPermissions(*Permissions.Group.STORAGE)
            .rationale(androidAppString("tip_perm_request_storage"))
            .onGranted { success?.invoke() }.onDenied {
                onResult(null)
            }.onError {
            onResult(null)
        }.request()
    }

    private fun onResult(uri: Uri?) {
        if (!isAdded) return
        if (mode == HandleFileContract.EXPORT && uri != null) {
            getFileData()?.let { fileData ->
                viewModel.saveToLocal(uri, fileData.name, fileData.data) { savedUri ->
                    deliverResult(savedUri)
                }
                return
            }
        }
        deliverResult(uri)
    }

    private fun deliverResult(uri: Uri?) {
        if (!isAdded) return
        val result = HandleFileContract.Result(uri, requestCode, arguments?.getString("value"))
        val bundle = Bundle().apply {
            putParcelable("result", result.uri)
            putInt("requestCode", result.requestCode)
            putString("value", result.value)
        }
        parentFragmentManager.setFragmentResult("handleFile", bundle)
        dismiss()
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
