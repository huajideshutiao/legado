package io.legado.app.ui.association

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.root.AppNavigatorProviders
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
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
        viewModel.onLineImportLive.observe(this) {
            handleOnLineImport(it)
        }
        viewModel.notSupportedLiveData.observe(this) { data ->
            alert(
                title = appCtx.getString(R.string.draw),
                message = appCtx.getString(R.string.file_not_supported, data.second)
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
            viewModel.dispatchIntent(uri)
        } else {
            PermissionsCompat.Builder()
                .addPermissions(*Permissions.Group.STORAGE)
                .rationale(R.string.tip_perm_request_storage)
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

    private fun handleOnLineImport(uri: Uri) {
        val url = uri.getQueryParameter("src")
        if (url.isNullOrEmpty()) {
            finishActivity()
            return
        }
        when (uri.path) {
            "/bookSource", "/rssSource" -> showImportDialog(ImportBookSourceDialog(url, isShell))
            "/replaceRule" -> showImportDialog(ImportReplaceRuleDialog(url, isShell))
            "/textTocRule" -> showImportDialog(ImportTxtTocRuleDialog(url, isShell))
            "/httpTTS" -> showImportDialog(ImportHttpTtsDialog(url, isShell))
            "/dictRule" -> showImportDialog(ImportDictRuleDialog(url, isShell))
            "/theme" -> showImportDialog(ImportThemeDialog(url, isShell))
            "/addToBookshelf" -> {
                AddToBookshelfHelper.add(
                    AppNavigatorProviders.get(),
                    requireActivity(),
                    url,
                    isShell
                )
                removeSelf()
            }

            "/readConfig" -> viewModel.getBytes(url) { bytes ->
                viewModel.importReadConfig(bytes) { title, msg ->
                    finallyDialog(title, msg)
                }
            }

            "/importonline" -> when (uri.host) {
                "booksource", "rsssource" -> showImportDialog(ImportBookSourceDialog(url, isShell))
                "replace" -> showImportDialog(ImportReplaceRuleDialog(url, isShell))
                else -> viewModel.determineType(url) { title, msg ->
                    finallyDialog(title, msg)
                }
            }

            else -> viewModel.determineType(url) { title, msg ->
                finallyDialog(title, msg)
            }
        }
    }

    private fun handleSuccess(it: Pair<String, String>) {
        when (it.first) {
            "bookSource", "rssSource" -> showImportDialog(
                ImportBookSourceDialog(
                    it.second,
                    isShell
                )
            )

            "replaceRule" -> showImportDialog(ImportReplaceRuleDialog(it.second, isShell))
            "httpTts" -> showImportDialog(ImportHttpTtsDialog(it.second, isShell))
            "theme" -> showImportDialog(ImportThemeDialog(it.second, isShell))
            "txtRule" -> showImportDialog(ImportTxtTocRuleDialog(it.second, isShell))
            "dictRule" -> showImportDialog(ImportDictRuleDialog(it.second, isShell))
        }
    }

    private fun showImportDialog(dialog: DialogFragment) {
        // 通过宿主 Activity 的 FragmentManager 显示，避免强转 AppCompatActivity
        dialog.show(parentFragmentManager, dialog::class.simpleName)
        removeSelf()
    }

    private fun finallyDialog(title: String, msg: String) {
        alert(title, msg) {
            okButton()
            onDismiss {
                finishActivity()
            }
        }
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
                title = getString(R.string.select_book_folder)
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
                        title = getString(R.string.select_book_folder)
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
