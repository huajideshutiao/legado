package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.legado.app.App
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.DirUsage
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.copyToFileAtomically
import io.legado.app.help.file.AndroidDirAuthorizations
import io.legado.app.help.i18n.androidAppString
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.HandleFileLauncher
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.LaunchRequestBus
import io.legado.app.ui.root.toReadRoute
import io.legado.app.utils.FileUtils
import io.legado.app.utils.canRead
import io.legado.app.utils.checkWrite
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.readUri
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件关联导入流程的跨重建宿主: 流程实例存进宿主 Activity 的 ViewModelStore, 界面重建
 * (旋转/夜间切换) 后由宿主重挂新 Activity 实例继续 (对照原版 FileAssociationFragment 由
 * FragmentManager 保留实例并自动恢复的语义); 流程各自完成后自移除。
 */
class FileAssociationFlows : ViewModel() {

    private val flows = mutableListOf<FileAssociationImport>()

    fun start(activity: BaseComposeActivity, uri: Uri, isShellHost: Boolean) {
        val flow = FileAssociationImport(uri, isShellHost) { flows.remove(it) }
        flows.add(flow)
        flow.attach(activity)
    }

    /** 界面重建后重挂: 存活流程重新登记选择器回调与观察者 (已结束的流程就地移除)。 */
    fun reattach(activity: BaseComposeActivity) {
        flows.toList().forEach { it.attach(activity) }
    }

    companion object {
        fun of(activity: AppCompatActivity): FileAssociationFlows =
            ViewModelProvider(activity)[FileAssociationFlows::class.java]
    }
}

/**
 * 文件关联导入的一次性流程执行器 (无自有界面, 界面只由它弹出的对话框/转圈组成)。
 * 流程状态 (uri/嗅探 ViewModel/进行中的写入) 存进宿主的 ViewModelStore 跨界面重建存活,
 * 由 [FileAssociationFlows] 持有; [attach] 挂接当前 Activity 实例。
 * isShellHost 由创建方决定: 为 true 时完成动作会 finish 宿主 Activity (独立透明壳场景),
 * 默认 false 只结束自身流程, 不动宿主 (挂在主界面上的默认行为)。
 */
class FileAssociationImport(
    val uri: Uri,
    private val isShellHost: Boolean = false,
    private val onDone: (FileAssociationImport) -> Unit = {},
) {
    private val isShell get() = isShellHost

    /** 选择器登记的稳定 key: 跨界面重建不变, 重建后重挂时用同一 key 重登记接回待回传结果。 */
    private val handleKey = "fileAssociation_${nextId++}"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val viewModel = FileAssociationViewModel(App.instance)

    /** 当前挂接的宿主实例与选择器 (结果回调捕获宿主, 重建后须重挂新实例); 界面重建后由 [attach] 换新。 */
    private var activity: BaseComposeActivity? = null
    private var treeSelect: HandleFileLauncher? = null

    private var started = false
    private var finished = false

    /** 挂接 (可能是重建后的) 宿主: 重登记书籍目录选择器、重挂 ViewModel 观察者, 首次挂接触发嗅探。 */
    fun attach(activity: BaseComposeActivity) {
        if (finished) {
            onDone(this)
            return
        }
        detachObservers()
        this.activity = activity
        treeSelect = activity.registerHandleFile(handleKey) { result ->
            if (finished) return@registerHandleFile
            result.uri?.let { treeUri ->
                AppConfig.defaultBookTreeUri = treeUri.toString()
                // 书籍保存位置下的文件按本地书读写, 登记为书籍用途
                AndroidDirAuthorizations.remember(treeUri.toString(), DirUsage.BookTree)
                importBook(treeUri, uri)
            }
        }
        observe(activity)
        if (!started) {
            started = true
            handleUri(uri)
        }
    }

    /** 观察者挂宿主生命周期: 宿主销毁 (含界面重建) 自动摘除, 重建后 [attach] 重挂;
     * 流程结束后 [finished] 拦截迟到回调, 不再弹提示/收尾/重复派发。 */
    private fun observe(activity: BaseComposeActivity) {
        viewModel.importBookLiveData.observe(activity) { if (!finished) importBook(it) }
        viewModel.successLive.observe(activity) { if (!finished) handleSuccess(it) }
        viewModel.errorLive.observe(activity) {
            if (!finished) {
                activity.toastOnUi(it)
                finish()
            }
        }
        viewModel.openBookLiveData.observe(activity) {
            if (!finished) {
                // 同进程直投路由引用: 类型分发由 Book.toReadRoute() 承担 (audio/video/manga/rss/reader),
                // 壳内 asRoot (书架不进栈, 对照 master 直开), 宿主内是普通 push
                LaunchRequestBus.dispatch(LaunchRequest.OpenRoute(it.toReadRoute(), asRoot = isShell))
                // 壳内: Intent 只负责把主界面唤到前台 (不带载荷); 宿主内本就在前台
                if (isShell) activity.startActivity<MainActivity>()
                finish()
            }
        }
        viewModel.notSupportedLiveData.observe(activity) { data ->
            if (!finished) {
                activity.alert(
                    title = androidAppString("draw"),
                    message = androidAppString("file_not_supported", data.second)
                ) {
                    yesButton {
                        importBook(data.first)
                    }
                    noButton {
                        finish()
                    }
                    onCancelled {
                        finish()
                    }
                }
            }
        }
    }

    private fun detachObservers() {
        activity?.let { a ->
            viewModel.importBookLiveData.removeObservers(a)
            viewModel.successLive.removeObservers(a)
            viewModel.errorLive.removeObservers(a)
            viewModel.openBookLiveData.removeObservers(a)
            viewModel.notSupportedLiveData.removeObservers(a)
        }
    }

    private fun handleUri(uri: Uri) {
        val activity = activity ?: return
        if (uri.isContentScheme() && uri.canRead()) {
            viewModel.dispatchIntent(uri)
        } else if (uri.scheme == "legado" || uri.scheme == "yuedu") {
            // 深链统一走 shared 解析链 (LegadoDeepLinkHandler → DeepLinkImportHost),
            // 与 MainActivity.handleExternalIntent / 共享 WebViewRoute.interceptUrl 同一条链。
            LegadoDeepLinkHandler.handle(uri.toString())
            finish()
        } else {
            PermissionsCompat.Builder()
                .addPermissions(*Permissions.Group.STORAGE)
                .rationale(androidAppString("tip_perm_request_storage"))
                .onGranted {
                    viewModel.dispatchIntent(uri)
                }
                .onDenied {
                    activity.toastOnUi("请求存储权限失败。")
                    finish()
                }
                .request()
        }
    }

    /**
     * 文件 JSON 导入成功。深链在线导入已统一走 shared [LegadoDeepLinkHandler],
     * 此处仅剩 importJson 的文件导入分支; 载荷是已读出的 JSON 全文, 导入对话框
     * 经 DeepLinkImportTarget 直接喂给 Import*ViewModelShared 解析。
     */
    private fun handleSuccess(it: Pair<DeepLinkImportType, String>) {
        showImportDialog(it.first, it.second)
    }

    /**
     * 导入对话框改走 shared Overlay 分发 (对照 ImportOverlayDialogs 的 IntentData 侧信道模式):
     * source 文本经 IntentData 存侧信道, overlay payload 只放 key。
     */
    private fun showImportDialog(type: DeepLinkImportType, source: String) {
        if (isShell) {
            // 独立透明壳: 壳内无主导航器 (showOverlay 会 error), 改走 shared pending 链,
            // 由壳的 DeepLinkImportHost 弹同一个导入对话框; handleResolved 的 src 与
            // overlay 路径传的是同一字符串、同一 Import*ViewModelShared 入口, 行为等价
            LegadoDeepLinkHandler.handleResolved(DeepLinkImportRequest(type, source))
            finish()
            return
        }
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog(
                key = "*Import:${type.name}",
                payload = IntentData.put(source),
            )
        )
        finish()
    }

    /** 流程收口: 停协程、摘观察者、从宿主列表自移除; 壳场景额外结束宿主 Activity。 */
    private fun finish() {
        if (finished) return
        finished = true
        scope.cancel()
        detachObservers()
        val activity = activity
        this.activity = null
        onDone(this)
        if (isShell) {
            activity?.finish()
        }
    }

    private fun importBook(uri: Uri) {
        val treeUriStr = AppConfig.defaultBookTreeUri
        if (uri.isContentScheme() && treeUriStr.isNullOrEmpty()) {
            treeSelect?.launch {
                title = androidAppString("select_book_folder")
                mode = HandleFileContract.DIR_SYS
            }
        } else {
            importBook(treeUriStr?.toUri(), uri)
        }
    }

    @SuppressLint("Recycle") // openOutputStream 由下方 .use 关闭, lint 追踪不到回调内传递的流
    private fun importBook(treeUri: Uri?, uri: Uri) {
        val activity = activity ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (treeUri == null) {
                        viewModel.importBook(uri)
                    } else if (treeUri.isContentScheme()) {
                        val treeDoc = DocumentFile.fromTreeUri(activity, treeUri)
                        if (treeDoc?.checkWrite() != true) {
                            throw InvalidBooksDirException("请重新设置书籍保存位置")
                        }
                        activity.readUri(uri) { fileDoc, inputStream ->
                            val name = fileDoc.name
                            var doc = treeDoc.findFile(name)
                            if (doc == null || fileDoc.lastModified > doc.lastModified()) {
                                if (doc == null) {
                                    doc = treeDoc.createFile(FileUtils.getMimeType(name), name)
                                        ?: throw InvalidBooksDirException("请重新设置书籍保存位置")
                                }
                                activity.contentResolver.openOutputStream(doc.uri)!!
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
                        activity.readUri(uri) { fileDoc, inputStream ->
                            val name = fileDoc.name
                            val file = treeFile.getFile(name)
                            if (!file.exists() || fileDoc.lastModified > file.lastModified()) {
                                // 同名重存的是书籍保存目录里的书文件, 阅读页可能正在整读它:
                                // 写同目录唯一临时件再原子改名, 读方只会拿到完整内容
                                copyToFileAtomically(file.path, inputStream)
                            }
                            viewModel.importBook(Uri.fromFile(file))
                        }
                    }
                }
            }.onFailure {
                if (it is InvalidBooksDirException) {
                    treeSelect?.launch {
                        title = androidAppString("select_book_folder")
                        mode = HandleFileContract.DIR_SYS
                    }
                } else {
                    val msg = "导入书籍失败\n${it.localizedMessage}"
                    AppLog.put(msg, it)
                    activity.toastOnUi(msg)
                    finish()
                }
            }
        }
    }

    companion object {
        private var nextId = 0
    }
}
