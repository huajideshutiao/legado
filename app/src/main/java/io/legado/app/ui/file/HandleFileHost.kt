package io.legado.app.ui.file

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.App
import io.legado.app.constant.AppLog
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.help.i18n.androidAppString
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialogContent
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogScaffold
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppSelectorList
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.book.import.ImportFileRow
import io.legado.app.ui.compose.component.SelectActionBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.FileDoc
import io.legado.app.utils.SelectImageContract
import io.legado.app.utils.checkWrite
import io.legado.app.utils.externalFiles
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.toastOnUi
import java.io.File

/**
 * 文件选择/导出对话框的命令式入口与声明式宿主 (原 HandleFileDialog / FilePickerDialog
 * 两个 Fragment 的迁移形态)。调用方经 [HandleFileLauncher] 发起, 结果走原回调;
 * 宿主挂在各 Activity 的 Content 尾部, 仅 RESUMED 的实例渲染, 防后台窗口重复弹窗。
 */
object HandleFileRequests {

    /** 在队请求: 每条请求各占一窗 (对照原机制每请求一个对话框 Fragment)。 */
    internal val pending = mutableStateListOf<HandleFileRequest>()

    fun request(
        param: HandleFileContract.HandleFileParam,
        lifecycleOwner: LifecycleOwner,
        callback: (HandleFileContract.Result) -> Unit,
    ) {
        pending.add(HandleFileRequest(param, lifecycleOwner, callback))
    }

    /** 按请求身份出队并投递结果 (并发请求不会串结果)。 */
    fun deliver(request: HandleFileRequest, result: HandleFileContract.Result) {
        if (!pending.remove(request)) return
        // 发起方已销毁则结果无人接收 (对齐原 Fragment 结果监听随生命周期注销的语义)
        if (request.lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            request.callback(result)
        }
    }
}

class HandleFileRequest(
    val param: HandleFileContract.HandleFileParam,
    val lifecycleOwner: LifecycleOwner,
    val callback: (HandleFileContract.Result) -> Unit,
)

/** 挂在各 Activity Content 尾部; 请求发起方所在 Activity 前台时渲染。 */
@Composable
fun HandleFileHost() {
    if (HandleFileRequests.pending.isEmpty()) return
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!resumed) return
    // 每条请求各占一窗 (对照原机制每请求一个对话框 Fragment)
    HandleFileRequests.pending.forEach { request ->
        key(request) { HandleFileDialogContent(request) }
    }
}

@Composable
private fun HandleFileDialogContent(request: HandleFileRequest) {
    val param = request.param
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val viewModel = remember { HandleFileViewModel(app) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var isLaunchingResult by remember { mutableStateOf(false) }
    var childPicker by remember { mutableStateOf<FilePickerParam?>(null) }
    var inputDirDialog by remember { mutableStateOf(false) }

    val selectList = remember(param) {
        buildSelectList(param.mode, param.otherActions)
    }

    fun deliverResult(uri: Uri?) {
        HandleFileRequests.deliver(
            request,
            HandleFileContract.Result(uri, param.requestCode, param.value),
        )
    }

    val selectDocTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        isLaunchingResult = false
        uri?.let {
            if (it.isContentScheme()) {
                val modeFlags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, modeFlags)
            }
            deliverResult(it)
        } ?: deliverResult(null)
    }

    val selectDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        isLaunchingResult = false
        uri?.let {
            if (it.isContentScheme()) {
                val modeFlags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, modeFlags)
            }
            deliverResult(it)
        } ?: deliverResult(null)
    }

    val selectImage = rememberLauncherForActivityResult(SelectImageContract()) {
        isLaunchingResult = false
        it.uri?.let { uri -> deliverResult(uri) } ?: deliverResult(null)
    }

    // 错误提示 (原 errorLiveData.observe: toast + 关闭)
    DisposableEffect(viewModel) {
        val observer = Observer<String> { msg ->
            context.toastOnUi(msg)
            deliverResult(null)
        }
        viewModel.errorLiveData.observe(lifecycleOwner, observer)
        onDispose { viewModel.errorLiveData.removeObserver(observer) }
    }

    val title = param.title ?: when (param.mode) {
        HandleFileContract.EXPORT -> rememberString("export")
        HandleFileContract.DIR -> rememberString("select_folder")
        HandleFileContract.IMAGE -> rememberString("select_image")
        else -> rememberString("select_file")
    }

    fun checkPermissions(success: (() -> Unit)?) {
        PermissionsCompat.Builder().addPermissions(*Permissions.Group.STORAGE)
            .rationale(androidAppString("tip_perm_request_storage"))
            .onGranted { success?.invoke() }.onDenied {
                deliverResult(null)
            }.onError {
                deliverResult(null)
            }.request()
    }

    fun handleAction(item: SelectItem<Int>) {
        when (item.value) {
            HandleFileContract.DIR -> {
                isLaunchingResult = true
                kotlin.runCatching { selectDocTree.launch(null) }.onFailure {
                    isLaunchingResult = false
                    AppLog.put(androidAppString("open_sys_dir_picker_error"), it, true)
                    checkPermissions {
                        childPicker = FilePickerParam(mode = HandleFileContract.DIR)
                    }
                }
            }

            HandleFileContract.FILE -> {
                isLaunchingResult = true
                kotlin.runCatching { selectDoc.launch(typesOfExtensions(param.allowExtensions)) }
                    .onFailure {
                        isLaunchingResult = false
                        AppLog.put(androidAppString("open_sys_dir_picker_error"), it, true)
                        checkPermissions {
                            childPicker = FilePickerParam(
                                mode = HandleFileContract.FILE,
                                allowExtensions = param.allowExtensions,
                            )
                        }
                    }
            }

            HandleFileContract.IMAGE -> {
                isLaunchingResult = true
                selectImage.launch()
            }

            10 -> checkPermissions {
                childPicker = FilePickerParam(mode = HandleFileContract.DIR)
            }

            11 -> checkPermissions {
                childPicker = FilePickerParam(
                    mode = HandleFileContract.FILE,
                    allowExtensions = param.allowExtensions,
                )
            }

            111 -> param.fileData?.let { fileData ->
                viewModel.upload(fileData.name, fileData.data, fileData.type) { url ->
                    deliverResult(url.toUri())
                }
            }

            112 -> inputDirDialog = true
            else -> {
                val path = item.title
                val uri = if (path.isContentScheme()) path.toUri() else Uri.fromFile(File(path))
                deliverResult(uri)
            }
        }
    }

    fun onResult(uri: Uri?) {
        if (param.mode == HandleFileContract.EXPORT && uri != null) {
            param.fileData?.let { fileData ->
                viewModel.saveToLocal(uri, fileData.name, fileData.data) { savedUri ->
                    deliverResult(savedUri)
                }
                return
            }
        }
        deliverResult(uri)
    }

    // 对齐原 onCancel: 非结果等待期的取消按用户取消回传 null
    fun onUserCancel() {
        if (!isLaunchingResult) {
            onResult(null)
        }
    }

    AppDialog(
        onDismissRequest = { onUserCancel() },
        properties = AppDialogSizes.properties(),
    ) {
        AppAlertDialogContent(
            onDismissRequest = { onUserCancel() },
            title = title,
        ) {
            // 对齐旧 AlertDialog setItems(items, null)：点击不自动关闭，等结果回传后关闭
            AppSelectorList(items = selectList.map { it.title }) { index ->
                handleAction(selectList[index])
            }
        }
    }

    childPicker?.let { pickerParam ->
        FilePickerHost(
            param = pickerParam,
            onResult = { path ->
                childPicker = null
                if (path != null) {
                    val uri = if (path.isContentScheme()) path.toUri() else Uri.fromFile(File(path))
                    onResult(uri)
                }
            },
            onDismiss = { childPicker = null },
        )
    }

    if (inputDirDialog) {
        InputDirectoryDialog(
            onResult = { path ->
                inputDirDialog = false
                if (path != null) {
                    val uri = if (path.isContentScheme()) path.toUri() else Uri.fromFile(File(path))
                    onResult(uri)
                } else onResult(null)
            },
        )
    }
}

private fun buildSelectList(
    mode: Int,
    otherActions: ArrayList<SelectItem<Int>>?,
): ArrayList<SelectItem<Int>> {
    fun getDirActions(onlySys: Boolean = false) = if (onlySys) {
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

    fun getFileActions() = arrayListOf(
        SelectItem(androidAppString("sys_file_picker"), HandleFileContract.FILE),
        SelectItem(androidAppString("app_file_picker"), 11)
    )

    fun getImageActions() = arrayListOf(
        SelectItem(
            androidAppString("sys_image_picker"),
            HandleFileContract.IMAGE
        )
    ).apply { addAll(getFileActions()) }

    return when (mode) {
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
    }.apply { otherActions?.let { addAll(it) } }
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

/** 手动输入目录 (原 showInputDirectoryDialog 的 alert DSL 迁声明式) */
@Composable
private fun InputDirectoryDialog(onResult: (String?) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    AppDialog(
        onDismissRequest = { onResult(null) },
        properties = AppDialogSizes.properties(),
    ) {
        AppAlertDialogContent(
            onDismissRequest = { onResult(null) },
            title = androidAppString("manual_input"),
            okButton = AlertButton(
                text = rememberString("ok"),
                onClick = {
                    val inputPath = text
                    if (inputPath.isBlank()) {
                        context.toastOnUi(androidAppString("empty_directory_input"))
                    } else {
                        val file = File(inputPath)
                        if (file.exists() && file.isDirectory && isExternalStorage(file) && file.checkWrite()) {
                            onResult(inputPath)
                        } else context.toastOnUi(androidAppString("invalid_directory"))
                    }
                },
            ),
            cancelButton = AlertButton(
                text = rememberString("cancel"),
            ),
            content = {
                AppUnderlineTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = androidAppString("enter_directory_path"),
                    singleLine = true,
                )
            },
        )
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

class FilePickerParam(
    val mode: Int = HandleFileContract.FILE,
    val title: String? = null,
    val initPath: String? = null,
    val isShowHideDir: Boolean = false,
    val allowExtensions: Array<String>? = null,
)

/** 应用内目录/文件浏览器 (原 FilePickerDialog Fragment 的宿主化形态) */
@Composable
fun FilePickerHost(
    param: FilePickerParam,
    onResult: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val viewModel = remember { FilePickerViewModel(app) }
    val colors = AppTheme.colors
    val lifecycleOwner = LocalLifecycleOwner.current

    var items by remember { mutableStateOf<List<ImportBook>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<ImportBook>>(emptySet()) }
    var path by remember { mutableStateOf("") }
    var emptyVisible by remember { mutableStateOf(false) }
    var createFolderDialog by remember { mutableStateOf(false) }

    LaunchedEffect(param) {
        viewModel.initData(param.mode, param.initPath, param.isShowHideDir, param.allowExtensions)
    }
    DisposableEffect(viewModel) {
        val observer = Observer<List<File>> { files ->
            emptyVisible = files.isEmpty()
            items = files.map { file ->
                val isUpDir = file == viewModel.lastDir && file != viewModel.rootDoc
                ImportBook(FileDoc.fromFile(file), isUpDir = isUpDir, isFileManageMode = true)
            }
            viewModel.rootDoc?.let { rootDoc ->
                var p = rootDoc.name + File.separator
                for (doc in viewModel.subDocs) {
                    p = p + doc.name + File.separator
                }
                path = p
            }
        }
        viewModel.filesLiveData.observe(lifecycleOwner, observer)
        onDispose { viewModel.filesLiveData.removeObserver(observer) }
    }

    fun isCheckable(item: ImportBook): Boolean = !item.isUpDir && !item.isDir

    fun onConfirm() {
        if (viewModel.isSelectDir) {
            viewModel.lastDir?.let {
                onResult(it.path)
            }
        } else {
            val file = selected.firstOrNull()?.file
            if (file == null) {
                context.toastOnUi("请选择文件")
            } else {
                onResult(file.toString())
            }
        }
    }

    AppDialogScaffold(
        onDismissRequest = onDismiss,
        title = param.title
            ?: if (viewModel.isSelectDir) rememberString("folder_chooser") else rememberString("file_chooser"),
        titleActions = {
            IconButton(onClick = { createFolderDialog = true }) {
                Icon(
                    painter = rememberPainter("ic_create_folder_outline"),
                    contentDescription = rememberString("create_folder"),
                    tint = colors.primaryText,
                )
            }
        },
        contentPadding = PaddingValues(0.dp),
        properties = AppDialogSizes.properties(),
    ) {
        Text(
            text = path,
            color = colors.secondaryText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(DesignTokens.spacingDefault),
        )
        Spacer(Modifier.height(2.dp)) // 对照静态 RefreshProgressBar 占位
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
        ) {
            FastScrollLazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxWidth(),
                // 目录/文件少时列表随内容收缩 (对照原版对话框的自适应高度)
                wrapContentHeight = true,
            ) {
                items(items, key = { it.file.toString() }) { item ->
                    fun onItemClick(item: ImportBook) {
                        when {
                            item.isUpDir -> {
                                if (viewModel.subDocs.isNotEmpty()) {
                                    viewModel.subDocs.removeAt(viewModel.subDocs.lastIndex)
                                    viewModel.upFiles(viewModel.lastDir)
                                }
                            }

                            item.isDir -> {
                                item.file.asFile()?.let {
                                    viewModel.subDocs.add(it)
                                    viewModel.upFiles(it)
                                }
                            }

                            else -> selected =
                                if (item in selected) selected - item else selected + item
                        }
                    }

                    ImportFileRow(
                        name = item.name,
                        isDir = item.isDir,
                        isUpDir = item.isUpDir,
                        checkable = !item.isUpDir && !item.isDir,
                        checked = selected.contains(item),
                        onBookShelf = false,
                        tag = item.name.substringAfterLast("."),
                        size = item.size,
                        lastModified = item.lastModified,
                        onClick = { onItemClick(item) },
                    )
                }
            }
            if (emptyVisible) {
                Text(
                    text = rememberString("empty"),
                    color = colors.secondaryText,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(DesignTokens.spacingLg),
                )
            }
        }
        SelectActionBar(
            selectCount = selected.size,
            allCount = items.count { !it.isUpDir && !it.isDir },
            onSelectAll = { all ->
                selected = if (all) items.filter { !it.isUpDir && !it.isDir }.toSet() else emptySet()
            },
            onRevertSelection = {
                selected = items.filter { !it.isUpDir && !it.isDir }.toSet() - selected
            },
            mainActionText = rememberString("confirm"),
            onMainAction = { onConfirm() },
        )
    }

    if (createFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AppDialog(
            onDismissRequest = { createFolderDialog = false },
            properties = AppDialogSizes.properties(),
        ) {
            AppAlertDialogContent(
                onDismissRequest = { createFolderDialog = false },
                title = androidAppString("create_folder"),
                okButton = AlertButton(
                    text = rememberString("ok"),
                    onClick = {
                        val name = folderName
                        if (name.isBlank()) {
                            context.toastOnUi("文件夹名不能为空")
                        } else {
                            viewModel.createFolder(name.trim())
                            createFolderDialog = false
                        }
                    },
                ),
                cancelButton = AlertButton(
                    text = rememberString("cancel"),
                ),
                content = {
                    AppUnderlineTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        placeholder = "文件夹名",
                        singleLine = true,
                    )
                },
            )
        }
    }
}
