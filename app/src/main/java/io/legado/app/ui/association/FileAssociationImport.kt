package io.legado.app.ui.association

import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.legado.app.App
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.DirUsage
import io.legado.app.data.entities.Book
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.copyToFileAtomically
import io.legado.app.help.file.AndroidDirAuthorizations
import io.legado.app.help.i18n.androidAppString
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.LaunchRequestBus
import io.legado.app.ui.root.toReadRoute
import io.legado.app.utils.FileUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.canRead
import io.legado.app.utils.checkWrite
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.openInputStream
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 文件关联导入的一次性执行器: 由透明壳 ([AssociationActivity]) 按当次请求创建并直接持有,
 * 无自有界面 (界面只由它弹出的对话框组成), 全部工作挂在壳的生命周期子任务上 ——
 * 壳销毁 (含界面重建收壳) 工作随之取消, 不跨重建续跑。
 *
 * isShellHost 由创建方按当次请求决定: true = 外部 VIEW 命中的独立壳 (打开书籍 asRoot 直达
 * 并唤起主界面), false = 主界面内部导入的普通透明宿主 (普通页面跳转, 不唤起主界面)。
 *
 * 流程收口一律经 [onDone] 回报壳: 打开书籍/移交导入请求/确认或取消/失败都只结束读取阶段,
 * 移交给共享导入宿主的请求由壳继续跟踪, 与壳寿命解耦。
 */
class FileAssociationImport(
    private val activity: BaseComposeActivity,
    val uri: Uri,
    private val isShellHost: Boolean,
    private val onDone: () -> Unit,
    private val onHandOff: (DeepLinkImportRequest) -> Unit,
) {
    private val viewModel = FileAssociationViewModel()

    /** 独立壳场景是否已唤起过主界面 (多本批次只唤一次)。 */
    private var mainLaunched = false

    /** 在途的存储权限请求实例 (撤销入口随流程持有)。 */
    private var storagePermission: PermissionsCompat? = null

    fun start() {
        activity.lifecycleScope.launch {
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 异常单出口: 文案在抛出点带全上下文; 取消 (宿主销毁/请求撤销) 不在此列
                AppLog.put(e.message ?: "导入失败", e)
                activity.toastOnUi(e.message ?: "导入失败")
            } finally {
                cleanup()
            }
        }
    }

    /** 流程收口: 撤销在途的权限请求与选择请求 (无则空操作), 回报壳扣减在途流程。 */
    private fun cleanup() {
        storagePermission?.cancel()
        storagePermission = null
        onDone()
    }

    private suspend fun run() {
        if (uri.isContentScheme() && uri.canRead()) {
            processBatch(viewModel.dispatchIntent(uri))
            return
        }
        if (uri.scheme == "legado" || uri.scheme == "yuedu") {
            // 深链统一走共享解析链; 解析不了 (缺 src 等) 原版直接结束流程
            LegadoDeepLink.parse(uri.toString())?.let(onHandOff)
            return
        }
        // content 不可读 / file / app 等其余: 存储权限先行 (对照原版 handleUri 同一顺序)
        if (!awaitStoragePermission()) {
            activity.toastOnUi("请求存储权限失败。")
            return
        }
        processBatch(viewModel.dispatchIntent(uri))
    }

    /** 逐项处理批次: 全部条目完成/取消后流程才收口; 单条失败按异常单出口结束整个流程。 */
    private suspend fun processBatch(items: List<DispatchOutcome>) {
        if (items.isEmpty()) {
            activity.toastOnUi("压缩包内没有可导入的书籍文件")
            return
        }
        for (item in items) {
            when (item) {
                is DispatchOutcome.Json -> {
                    val type = item.type
                        ?: throw NoStackTraceException("格式不对")
                    onHandOff(DeepLinkImportRequest(type, item.source))
                }

                is DispatchOutcome.Book -> importBookItem(item.uri)
                is DispatchOutcome.Unsupported ->
                    if (awaitUnsupportedConfirm(item.name)) importBookItem(item.uri)
            }
        }
    }

    private suspend fun importBookItem(bookUri: Uri) {
        val treeUriStr = AppConfig.defaultBookTreeUri
        if (bookUri.isContentScheme() && treeUriStr.isNullOrEmpty()) {
            val picked = awaitTreeSelection(bookUri) ?: return
            copyToTreeAndImport(picked.first, picked.second)
        } else {
            copyToTreeAndImport(treeUriStr?.toUri(), bookUri)
        }
    }

    private suspend fun copyToTreeAndImport(treeUri: Uri?, bookUri: Uri) {
        try {
            val book = withContext(Dispatchers.IO) { copyAndImport(treeUri, bookUri) }
            openBook(book)
        } catch (e: InvalidBooksDirException) {
            // 目录不可用 (未设置/被删/授权失效): 绑定当前书籍重选, 选定后重试本次条目
            val picked = awaitTreeSelection(bookUri) ?: return
            copyToTreeAndImport(picked.first, picked.second)
        }
    }

    /**
     * 复制进书籍保存位置后导入落库 (须在 IO 上调用): 读取/复制全部走 Application context
     * 的同步流原语; SAF 目录保持 DocumentFile 直写。偏好与用途登记在目录可写性成立后才落。
     */
    private fun copyAndImport(treeUri: Uri?, bookUri: Uri): Book {
        val fileDoc = FileDoc.fromUri(bookUri, false)
        if (treeUri == null) {
            return viewModel.importBook(bookUri)
        }
        if (treeUri.isContentScheme()) {
            val treeDoc = DocumentFile.fromTreeUri(App.instance, treeUri)
                ?: throw InvalidBooksDirException("请重新设置书籍保存位置")
            if (!treeDoc.checkWrite()) {
                throw InvalidBooksDirException("请重新设置书籍保存位置")
            }
            AppConfig.defaultBookTreeUri = treeUri.toString()
            AndroidDirAuthorizations.remember(treeUri.toString(), DirUsage.BookTree)
            val name = fileDoc.name
            var doc = treeDoc.findFile(name)
            if (doc == null || fileDoc.lastModified > doc.lastModified()) {
                if (doc == null) {
                    doc = treeDoc.createFile(FileUtils.getMimeType(name), name)
                        ?: throw InvalidBooksDirException("请重新设置书籍保存位置")
                }
                App.instance.contentResolver.openOutputStream(doc.uri)!!.use { output ->
                    fileDoc.openInputStream().getOrThrow().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            return viewModel.importBook(doc.uri)
        }
        val treeFile = File(treeUri.path ?: treeUri.toString())
        if (!treeFile.checkWrite()) {
            throw InvalidBooksDirException("请重新设置书籍保存位置")
        }
        AppConfig.defaultBookTreeUri = treeUri.toString()
        AndroidDirAuthorizations.remember(treeUri.toString(), DirUsage.BookTree)
        val name = fileDoc.name
        val file = treeFile.getFile(name)
        if (!file.exists() || fileDoc.lastModified > file.lastModified()) {
            // 同名重存的是书籍保存目录里的书文件, 阅读页可能正在整读它:
            // 写同目录唯一临时件再原子改名, 读方只会拿到完整内容
            fileDoc.openInputStream().getOrThrow().use { input ->
                copyToFileAtomically(file.path, input)
            }
        }
        return viewModel.importBook(Uri.fromFile(file))
    }

    private fun openBook(book: Book) {
        // 同进程直投路由引用: 类型分发由 Book.toReadRoute() 承担 (audio/video/manga/rss/reader);
        // 独立壳 asRoot (书架不进栈, 对照 master 直开) 并唤起主界面, 宿主内普通页面跳转
        LaunchRequestBus.dispatch(LaunchRequest.OpenRoute(book.toReadRoute(), asRoot = isShellHost))
        if (isShellHost && !mainLaunched) {
            mainLaunched = true
            activity.startActivity<MainActivity>()
        }
    }

    private suspend fun awaitStoragePermission(): Boolean =
        suspendCancellableCoroutine { cont ->
            val compat = PermissionsCompat.Builder()
                .addPermissions(*Permissions.Group.STORAGE)
                .rationale(androidAppString("tip_perm_request_storage"))
                .onGranted {
                    if (cont.isActive) cont.resume(true)
                }
                .onDenied {
                    if (cont.isActive) cont.resume(false)
                }
                .request()
            storagePermission = compat
            cont.invokeOnCancellation { compat.cancel() }
        }

    /**
     * 选书籍保存位置: 每次发起是一个独立选择请求, 回调只属于当次; 待导入书籍地址随请求
     * 参数 ([HandleFileContract.HandleFileParam.value]) 携带, 回调据此处理该书籍。
     * 取消 (返回 null) 即本次条目作罢, 不写偏好。
     */
    private suspend fun awaitTreeSelection(bookUri: Uri): Pair<Uri, Uri>? =
        suspendCancellableCoroutine { cont ->
            val launcher = activity.registerHandleFile { result ->
                val treeUri = result.uri
                val current = result.value?.toUri()
                if (treeUri != null && current != null) {
                    cont.resume(treeUri to current)
                } else {
                    cont.resume(null)
                }
            }
            cont.invokeOnCancellation { launcher.cancel() }
            launcher.launch {
                title = androidAppString("select_book_folder")
                mode = HandleFileContract.DIR_SYS
                value = bookUri.toString()
            }
        }

    private suspend fun awaitUnsupportedConfirm(name: String): Boolean =
        suspendCancellableCoroutine { cont ->
            activity.alert(
                title = androidAppString("draw"),
                message = androidAppString("file_not_supported", name)
            ) {
                yesButton {
                    if (cont.isActive) cont.resume(true)
                }
                noButton {
                    if (cont.isActive) cont.resume(false)
                }
                onCancelled {
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
}
