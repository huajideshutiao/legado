package io.legado.app.ui.association

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.AppLog
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.IntentData
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.help.i18n.androidAppString
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.FileUtils
import io.legado.app.utils.canRead
import io.legado.app.utils.checkWrite
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.readUri
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FileAssociationFragment() : Fragment() {

    constructor(uri: Uri) : this() {
        arguments = Bundle().apply {
            putParcelable("uri", uri)
        }
    }

    private val viewModel by viewModels<FileAssociationViewModel>()
    private val localBookTreeSelect by lazy {
        registerHandleFile { result ->
            val uri = arguments?.getParcelable<Uri>("uri") ?: return@registerHandleFile
            result.uri?.let { treeUri ->
                AppConfig.defaultBookTreeUri = treeUri.toString()
                importBook(treeUri, uri)
            }
        }
    }

    private val isShell get() = activity is MainActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = arguments?.getParcelable<Uri>("uri") ?: return removeSelf()

        viewModel.importBookLiveData.observe(this) {
            importBook(it)
        }
        viewModel.successLive.observe(this) {
            handleSuccess(it)
        }
        viewModel.errorLive.observe(this) {
            toastOnUi(it)
            finishActivity()
        }
        viewModel.openBookLiveData.observe(this) {
            // 按 book 类型分发到对应阅读路由
            val navigator = AppNavigatorProviders.get()
            val target = when {
                it.isAudio -> AppRoute.AudioPlay(it.toRouteRef())
                it.isVideo -> AppRoute.VideoPlay(it.toRouteRef())
                it.isImage -> AppRoute.MangaReader(it.toRouteRef())
                it.isRss -> AppRoute.ReadRss(it.toRouteRef())
                else -> AppRoute.Reader(it.toRouteRef())
            }
            navigator.push(target)
            finishActivity()
        }
        viewModel.notSupportedLiveData.observe(this) { data ->
            alert(
                title = androidAppString("draw"),
                message = androidAppString("file_not_supported", data.second)
            ) {
                yesButton {
                    importBook(data.first)
                }
                noButton {
                    finishActivity()
                }
                onCancelled {
                    finishActivity()
                }
            }
        }

        if (uri.isContentScheme() && uri.canRead()) {
            viewModel.dispatchIntent(uri)
        } else if (uri.scheme == "legado" || uri.scheme == "yuedu") {
            // 深链统一走 shared 解析链 (LegadoDeepLinkHandler → DeepLinkImportHost),
            // 与 MainActivity.handleExternalIntent / 共享 WebViewRoute.interceptUrl 同一条链。
            // 原 handleOnLineImport 的 when(uri.path) 手写分发已删除, path→类型映射由
            // commonMain LegadoDeepLink.parse 承担 (缺 src 等非法格式静默丢弃)。
            LegadoDeepLinkHandler.handle(uri.toString())
            finishActivity()
        } else {
            PermissionsCompat.Builder()
                .addPermissions(*Permissions.Group.STORAGE)
                .rationale(androidAppString("tip_perm_request_storage"))
                .onGranted {
                    viewModel.dispatchIntent(uri)
                }
                .onDenied {
                    toastOnUi("请求存储权限失败。")
                    finishActivity()
                }
                .request()
        }
    }

    /**
     * 文件 JSON 导入成功 (深链在线导入已统一走 shared [LegadoDeepLinkHandler],
     * 此处仅剩 importJson 的文件导入分支; 类型映射在 BaseAssociationViewModel 复用
     * shared JsonType.toDeepLinkImportType, 不再有第二份 when)。
     */
    private fun handleSuccess(it: Pair<DeepLinkImportType, Uri>) {
        showImportDialog(it.first, it.second.toString())
    }

    /**
     * 导入对话框改走 shared Overlay 分发 (对照 ImportOverlayDialogs 的 IntentData 侧信道模式):
     * source 文本经 IntentData 存侧信道, overlay payload 只放 key。
     * 原版 finishOnDismiss(isShell) 语义保留: 宿主是 MainActivity 时等 overlay 关闭后 finish。
     */
    private fun showImportDialog(type: DeepLinkImportType, source: String) {
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog(
                key = "*Import:${type.name}",
                payload = IntentData.put(source),
            )
        )
        if (isShell) {
            lifecycleScope.launch {
                AppNavigatorProviders.get().overlays.first { list ->
                    list.none { it.key.startsWith("*Import:") }
                }
                activity?.finish()
            }
        }
        removeSelf()
    }

    private fun finishActivity() {
        if (isShell) {
            activity?.finish()
        } else {
            removeSelf()
        }
    }

    private fun removeSelf() {
        if (isAdded) {
            parentFragmentManager.beginTransaction()
                .remove(this)
                .commitAllowingStateLoss()
        }
    }

    private fun importBook(uri: Uri) {
        val treeUriStr = AppConfig.defaultBookTreeUri
        if (uri.isContentScheme() && treeUriStr.isNullOrEmpty()) {
            localBookTreeSelect.launch {
                title = androidAppString("select_book_folder")
                mode = HandleFileContract.DIR_SYS
            }
        } else {
            importBook(treeUriStr?.toUri(), uri)
        }
    }

    private fun importBook(treeUri: Uri?, uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                withContext(IO) {
                    if (treeUri == null) {
                        viewModel.importBook(uri)
                    } else if (treeUri.isContentScheme()) {
                        val treeDoc = DocumentFile.fromTreeUri(requireContext(), treeUri)
                        if (treeDoc?.checkWrite() != true) {
                            throw InvalidBooksDirException("请重新设置书籍保存位置")
                        }
                        this@FileAssociationFragment.readUri(uri) { fileDoc, inputStream ->
                            val name = fileDoc.name
                            var doc = treeDoc.findFile(name)
                            if (doc == null || fileDoc.lastModified > doc.lastModified()) {
                                if (doc == null) {
                                    doc = treeDoc.createFile(FileUtils.getMimeType(name), name)
                                        ?: throw InvalidBooksDirException("请重新设置书籍保存位置")
                                }
                                requireContext().contentResolver.openOutputStream(doc.uri)!!
                                    .use { oStream ->
                                        inputStream.copyTo(oStream)
                                    }
                            }
                            viewModel.importBook(doc.uri)
                        }
                    } else {
                        val treeFile = File(treeUri.path ?: treeUri.toString())
                        if (!treeFile.checkWrite()) {
                            throw InvalidBooksDirException("请重新设置书籍保存位置")
                        }
                        this@FileAssociationFragment.readUri(uri) { fileDoc, inputStream ->
                            val name = fileDoc.name
                            val file = treeFile.getFile(name)
                            if (!file.exists() || fileDoc.lastModified > file.lastModified()) {
                                FileOutputStream(file).use { oStream ->
                                    inputStream.copyTo(oStream)
                                }
                            }
                            viewModel.importBook(Uri.fromFile(file))
                        }
                    }
                }
            }.onFailure {
                if (it is InvalidBooksDirException) {
                    localBookTreeSelect.launch {
                        title = androidAppString("select_book_folder")
                        mode = HandleFileContract.DIR_SYS
                    }
                } else {
                    val msg = "导入书籍失败\n${it.localizedMessage}"
                    AppLog.put(msg, it)
                    toastOnUi(msg)
                    finishActivity()
                }
            }
        }
    }
}
