package io.legado.app.ui.file

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.AdapterView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.utils.SelectImageContract
import io.legado.app.utils.applyTint
import io.legado.app.utils.checkWrite
import io.legado.app.utils.externalFiles
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File

class HandleFileDialog : DialogFragment() {

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

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        mode = arguments?.getInt("mode") ?: 0
        requestCode = arguments?.getInt("requestCode") ?: 0
        allowExtensions = arguments?.getStringArray("allowExtensions")

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val otherActions =
            arguments?.getSerializable("otherActions") as? ArrayList<SelectItem<Int>>

        selectList = buildSelectList()
        otherActions?.let { selectList.addAll(it) }

        val title = arguments?.getString("title") ?: when (mode) {
            HandleFileContract.EXPORT -> getString(R.string.export)
            HandleFileContract.DIR -> getString(R.string.select_folder)
            HandleFileContract.IMAGE -> getString(R.string.select_image)
            else -> getString(R.string.select_file)
        }

        val items = selectList.map { it.title }.toTypedArray<CharSequence>()

        return AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(items, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        val alertDialog = dialog as? AlertDialog ?: return
        alertDialog.applyTint()
        alertDialog.listView?.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                handleAction(selectList[position])
            }
        dialog?.window?.let {
            if (AppConfig.isEInkMode) {
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                val attr = it.attributes
                attr.dimAmount = 0f
                attr.windowAnimations = 0
                it.attributes = attr
            } else {
                val attr = it.attributes
                attr.windowAnimations = R.style.Animation_Dialog
                it.attributes = attr
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        if (!isLaunchingResult) {
            onResult(null)
        }
    }

    override fun show(manager: FragmentManager, tag: String?) {
        kotlin.runCatching {
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }.onFailure {
            AppLog.put("显示对话框失败 tag:$tag", it)
        }
    }

    private fun buildSelectList(): ArrayList<SelectItem<Int>> = when (mode) {
        HandleFileContract.DIR_SYS -> getDirActions(true)
        HandleFileContract.DIR -> getDirActions()
        HandleFileContract.FILE -> getFileActions()
        HandleFileContract.EXPORT -> arrayListOf(
            SelectItem(
                getString(R.string.upload_url),
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
                    AppLog.put(getString(R.string.open_sys_dir_picker_error), it, true)
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
                        AppLog.put(getString(R.string.open_sys_dir_picker_error), it, true)
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
                    onResult(url.toUri())
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
            SelectItem(getString(R.string.sys_folder_picker), HandleFileContract.DIR),
            SelectItem(getString(R.string.manual_input), 112)
        )
    } else {
        arrayListOf(
            SelectItem(getString(R.string.sys_folder_picker), HandleFileContract.DIR),
            SelectItem(getString(R.string.app_folder_picker), 10),
            SelectItem(getString(R.string.manual_input), 112)
        )
    }

    private fun getFileActions() = arrayListOf(
        SelectItem(getString(R.string.sys_file_picker), HandleFileContract.FILE),
        SelectItem(getString(R.string.app_file_picker), 11)
    )

    private fun getImageActions() = arrayListOf(
        SelectItem(
            getString(R.string.sys_image_picker),
            HandleFileContract.IMAGE
        )
    ).apply { addAll(getFileActions()) }

    private fun showInputDirectoryDialog() {
        val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.enter_directory_path)
        }
        alert(getString(R.string.manual_input)) {
            customView { alertBinding.root }
            okButton {
                val inputPath = alertBinding.editView.text.toString()
                if (inputPath.isBlank()) {
                    toastOnUi(getString(R.string.empty_directory_input))
                    return@okButton
                }
                val file = File(inputPath)
                if (file.exists() && file.isDirectory && isExternalStorage(file) && file.checkWrite()) {
                    onResult(Uri.fromFile(file))
                } else toastOnUi(getString(R.string.invalid_directory))
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
            .rationale(R.string.tip_perm_request_storage)
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
