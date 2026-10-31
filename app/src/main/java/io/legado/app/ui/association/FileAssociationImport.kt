package io.legado.app.ui.association

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.App
import io.legado.app.constant.AppLog
import io.legado.app.constant.DirUsage
import io.legado.app.data.entities.Book
import io.legado.app.model.fileBook.TempBookPersistence
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.copyToFileAtomically
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.file.AndroidDirAuthorizations
import io.legado.app.help.i18n.androidAppString
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.toReadRoute
import io.legado.app.utils.FileDoc
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.copyGatedTo
import io.legado.app.utils.copyToDocumentAtomically
import io.legado.app.utils.canRead
import io.legado.app.utils.checkWrite
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.openInputStream
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 文件流程等待当前有效宿主呈现的 UI 阶段 (宿主重建后由新组合重新呈现同一阶段;
 * 阶段与参数都在 VM 持有的执行器里, 不依赖任何具体 Activity 实例)。
 */
sealed class ImportUiStage {

    /**
     * 选书籍保存位置: [bookUri] 是待导入书籍地址; [attemptId] 是本次等待的唯一身份 ——
     * 参数相同 (同一本书重选) 的两次等待也必须区分, 否则 effect key 结构相等不重发、
     * 回调会把回执串到下一次等待。
     */
    data class TreeSelect(val bookUri: Uri, val attemptId: Long) : ImportUiStage()

    /** 不支持格式确认; [attemptId] 同上。 */
    data class UnsupportedConfirm(val name: String, val attemptId: Long) : ImportUiStage()
}

/**
 * 文件流程的宿主级 UI 出口: 结果交由当前有效 Activity 消费, 执行器不持有任何 Activity。
 */
fun interface ImportUiSink {

    /** [sourceKey] 标明是哪个文件请求的结果 —— 每个请求保自己的待交结果, 不互相覆盖。 */
    fun openBook(sourceKey: String, request: LaunchRequest.OpenRoute)
}

/**
 * 文件关联导入的一次性执行器: 由透明壳的宿主 ViewModel 创建并持有, 无自有界面。
 * 需要界面交互的阶段 (选目录/不支持确认) 以 [uiStage] 状态暴露, 由当前有效 Activity
 * 的组合观察并呈现; 配置重建不取消、不重跑任何已结束或进行中的阶段。
 *
 * isShellHost 由创建方按当次请求决定: true = 外部 VIEW 命中的独立壳 (打开书籍 asRoot 直达
 * 并唤起主界面), false = 主界面内部导入的普通透明宿主 (普通页面跳转, 不唤起主界面)。
 *
 * 流程收口一律经 [onDone] 回报宿主 VM (以状态驱动收壳); 移交给共享导入宿主的请求
 * 由其继续跟踪, 与本执行器寿命解耦。
 */
class FileAssociationImport(
    private val scope: CoroutineScope,
    /** 宿主侧账目键 (VM 分配); 待交打开结果按它归属本请求。 */
    val importKey: String,
    val uri: Uri,
    private val isShellHost: Boolean,
    private val sink: ImportUiSink,
    private val onDone: () -> Unit,
    private val onHandOff: (DeepLinkImportRequest) -> Unit,
) {
    private val viewModel = FileAssociationViewModel()

    /** 本请求序号: 独占解压目录与重建后可重绑的稳定选择器 key 共用。 */
    private val importId = requestSequence.incrementAndGet()

    /** 本请求独占的解压目录: 同名压缩包/并发导入互不覆盖, 清理只删本目录。 */
    private val workDir = ArchiveUtils.TEMP_PATH + File.separator + "import-" +
        importId + "-" + System.currentTimeMillis()

    /** 重建后仍有效的目录选择请求 key (当前宿主以同 key 注册入口认领在途请求)。 */
    val treeKey: String = "assoc-import-$importId"

    /** 在途的存储权限请求实例 (App 级独立权限页承载, 不随壳 Activity 重建失效)。 */
    private var storagePermission: PermissionsCompat? = null

    /** 当前等待宿主呈现的 UI 阶段; 结果经 resolve* 回到原等待协程。 */
    var uiStage by mutableStateOf<ImportUiStage?>(null)
        private set

    private var treeAttemptSeq = 0L
    private var treeAttemptId = 0L
    private var treeLaunched = false
    private var treeDeferred: CompletableDeferred<Pair<Uri, Uri>?>? = null

    /** 本等待的会话内选择请求撤销入口 (真取消撤选择; 重建重绑不撤)。 */
    private var treeAttemptCancel: (() -> Unit)? = null

    private var confirmAttemptSeq = 0L
    private var confirmAttemptId = 0L
    private var confirmDeferred: CompletableDeferred<Boolean>? = null

    fun start() {
        scope.launch {
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 异常单出口: 文案在抛出点带全上下文; 取消 (宿主销毁/请求撤销) 不在此列
                AppLog.put(e.message ?: "导入失败", e)
                App.instance.toastOnUi(e.message ?: "导入失败")
            } finally {
                cleanup()
            }
        }
    }

    /** 流程收口: 撤销在途权限请求, 清掉 UI 阶段与本请求解压目录, 以状态回报宿主。 */
    private fun cleanup() {
        storagePermission?.cancel()
        storagePermission = null
        treeDeferred = null
        treeAttemptCancel = null
        confirmDeferred = null
        uiStage = null
        File(workDir).deleteRecursively()
        onDone()
    }

    private suspend fun run() {
        if (uri.isContentScheme() && uri.canRead()) {
            processBatch(viewModel.dispatchIntent(uri, workDir))
            return
        }
        if (uri.scheme == "legado" || uri.scheme == "yuedu") {
            // 深链统一走共享解析链; 解析不了 (缺 src 等) 原版直接结束流程
            LegadoDeepLink.parse(uri.toString())?.let(onHandOff)
            return
        }
        // content 不可读 / file / app 等其余: 存储权限先行 (对照原版 handleUri 同一顺序)
        if (!awaitStoragePermission()) {
            App.instance.toastOnUi("请求存储权限失败。")
            return
        }
        processBatch(viewModel.dispatchIntent(uri, workDir))
    }

    /**
     * 逐项处理批次: 全部条目完成/取消后才导航, 避免首个条目唤起主界面时销毁透明壳
     * 取消后续批次; 单条失败按异常单出口结束整个流程。
     */
    private suspend fun processBatch(items: List<DispatchOutcome>) {
        if (items.isEmpty()) {
            App.instance.toastOnUi("压缩包内没有可导入的书籍文件")
            return
        }
        val imported = arrayListOf<Book>()
        for (item in items) {
            when (item) {
                is DispatchOutcome.Json -> {
                    val type = item.type
                        ?: throw NoStackTraceException("格式不对")
                    onHandOff(DeepLinkImportRequest(type, item.source))
                }

                is DispatchOutcome.Book -> importBookItem(item.uri)?.let { imported.add(it) }
                is DispatchOutcome.Unsupported ->
                    if (awaitUnsupportedConfirm(item.name)) {
                        importBookItem(item.uri)?.let { imported.add(it) }
                    }
            }
        }
        // 整批落库后再导航: 独立壳只在这里唤起一次主界面
        imported.lastOrNull()?.let(::openBook)
    }

    private suspend fun importBookItem(bookUri: Uri): Book? {
        val treeUriStr = AppConfig.defaultBookTreeUri
        if (bookUri.isContentScheme() && treeUriStr.isNullOrEmpty()) {
            val picked = awaitTreeSelection(bookUri) ?: return null
            return copyToTreeAndImport(picked.first, picked.second)
        }
        return copyToTreeAndImport(treeUriStr?.toUri(), bookUri)
    }

    private suspend fun copyToTreeAndImport(treeUri: Uri?, bookUri: Uri): Book? {
        try {
            return withContext(Dispatchers.IO) {
                copyAndImport(treeUri, bookUri, kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
            }
        } catch (e: InvalidBooksDirException) {
            // 目录不可用 (未设置/被删/授权失效): 绑定当前书籍重选, 选定后重试本次条目
            val picked = awaitTreeSelection(bookUri) ?: return null
            return copyToTreeAndImport(picked.first, picked.second)
        }
    }

    /** 来源是否在本请求的解压暂存目录 (只有归档条目需要先持久化)。 */
    private fun isUnderWorkDir(uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        return path.startsWith(workDir + File.separator)
    }

    /**
     * 复制进书籍保存位置后导入落库 (须在 IO 上调用): 读取/复制全部走 Application context
     * 的同步流原语; SAF 目录保持 DocumentFile 直写。偏好与用途登记在目录可写性成立后才落。
     */
    private fun copyAndImport(treeUri: Uri?, bookUri: Uri, parent: kotlinx.coroutines.Job?): Book {
        val fileDoc = FileDoc.fromUri(bookUri, false)
        if (treeUri == null) {
            // 归档条目位于本请求暂存目录: 先落成应用内长期文件再入库, 否则 cleanup 会删掉新书;
            // 用户原有的永久文件仍按引用导入 (不进这里)
            if (isUnderWorkDir(bookUri)) {
                val path = bookUri.path
                    ?: return viewModel.importBook(bookUri, parent)
                val durable = TempBookPersistence.persist(path, fileDoc.name, parent)
                return viewModel.importBook(Uri.fromFile(File(durable)), parent)
            }
            return viewModel.importBook(bookUri, parent)
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
                // 源整份先物化到本请求独占的暂存件 (每块前核父, 取消不再继续搬字节;
                // 源读取中途失败不触碰正式目标, 也不会留下空正式件)
                val staged = java.io.File(
                    io.legado.app.utils.atomicWriteTempPath(
                        java.io.File(java.io.File(App.instance.cacheDir, "bookSaveStage").apply { mkdirs() }, name).absolutePath
                    )
                )
                try {
                    parent?.ensureActive()
                    FileUtilsCommon.copyToFile(staged.absolutePath, fileDoc.openInputStream().getOrThrow(), parent)
                    if (doc == null) {
                        // 新建目标: 整份写入原语同目录就位 (每块前核父 + 正式提交前核父),
                        // 取消/失败不落半截正式件; 就位后按名取回正式 doc
                        check(copyToDocumentAtomically(App.instance, treeUri, name, staged.inputStream(), parent)) {
                            "写入书籍保存位置失败: $name"
                        }
                        doc = treeDoc.findFile(name)
                            ?: throw InvalidBooksDirException("请重新设置书籍保存位置")
                    } else {
                        // 同名重存保留既有 documentId (rename 换 id 破坏既有书籍/阅读引用,
                        // 与 FileBookAccessorImpl 的 SAF 语义一致): 本地→provider 短拷贝每块前核父,
                        // 取消不写半截; 拷贝失败原样抛原始 IO 错误
                        App.instance.contentResolver.openOutputStream(doc.uri)!!.use { output ->
                            staged.inputStream().use { copyGatedTo(it, output, parent) }
                        }
                    }
                } finally {
                    staged.delete()
                }
            }
            return viewModel.importBook(doc.uri, parent)
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
            // 写同目录唯一临时件再原子改名, 读方只会拿到完整内容; 资格核验在原语内
            // (每块前 + 正式替换前), 取消不动正式件
            fileDoc.openInputStream().getOrThrow().use { input ->
                copyToFileAtomically(file.path, input, parent)
            }
        }
        return viewModel.importBook(Uri.fromFile(file), parent)
    }

    private fun openBook(book: Book) {
        // 待交打开结果按本请求 key 归属宿主 VM: 由当前有效 Activity 分发, 不经旧 Activity 引用;
        // 重建空档完成也不丢 (宿主收尾会计入待交结果)
        sink.openBook(importKey, LaunchRequest.OpenRoute(book.toReadRoute(), asRoot = isShellHost))
    }

    /**
     * 存储权限走 App 级独立权限页 (PermissionsCompat 自启 PermissionActivity):
     * 请求不随壳 Activity 重建失效, 结果按原请求回到本等待协程; 只有真取消才撤销。
     */
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
                .onError { e ->
                    // 启动权限页失败/系统错误不是用户拒绝: 原异常交给真实失败出口
                    if (cont.isActive) cont.resumeWithException(e)
                }
                .request()
            storagePermission = compat
            cont.invokeOnCancellation { compat.cancel() }
        }

    /**
     * 选书籍保存位置: 阶段状态交当前宿主呈现 ([ImportUiStage.TreeSelect]);
     * 待导入书籍地址随请求参数携带, 结果 (选中目录 to 书籍地址, 取消为 null) 回到本协程。
     */
    private suspend fun awaitTreeSelection(bookUri: Uri): Pair<Uri, Uri>? {
        val attempt = ++treeAttemptSeq
        val deferred = CompletableDeferred<Pair<Uri, Uri>?>()
        treeAttemptId = attempt
        treeDeferred = deferred
        treeLaunched = false
        uiStage = ImportUiStage.TreeSelect(bookUri, attempt)
        try {
            return deferred.await()
        } finally {
            if (treeAttemptId == attempt && treeDeferred === deferred) {
                treeDeferred = null
                uiStage = null
                // 真取消 (等待没结出结果就退出) 撤销本次选择请求; 重建只重绑不撤
                if (!deferred.isCompleted) {
                    treeAttemptCancel?.invoke()
                }
                treeAttemptCancel = null
            }
        }
    }

    /**
     * 当前宿主已注册本等待 ([attemptId]) 的选择入口: 首次呈现才真正发起; 配置重建后
     * 只重绑在途请求等真实回执。[launch] 抛错回原等待的失败出口, 不让原等待永挂。
     */
    fun attachTreePresenter(attemptId: Long, launch: () -> Unit) {
        if (treeAttemptId != attemptId || treeLaunched) return
        treeLaunched = true
        try {
            launch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failTree(attemptId, e)
        }
    }

    /** 当前宿主登记本等待的会话内选择请求撤销入口 (真取消时用)。 */
    fun bindTreeAttemptCancel(attemptId: Long, cancel: () -> Unit) {
        if (treeAttemptId == attemptId) treeAttemptCancel = cancel
    }

    /** 当前宿主交回本次等待 ([attemptId]) 的目录选择结果 (取消为 null)。 */
    fun resolveTree(attemptId: Long, result: Pair<Uri, Uri>?) {
        if (treeAttemptId == attemptId) treeDeferred?.complete(result)
    }

    /** 本次等待的选择入口真实失败 (权限持久化等): 原异常交回原等待协程, 不冒充用户取消。 */
    fun failTree(attemptId: Long, error: Throwable) {
        if (treeAttemptId == attemptId) treeDeferred?.completeExceptionally(error)
    }

    /** 不支持格式确认: 阶段状态交当前宿主呈现, 结果回到本协程。 */
    private suspend fun awaitUnsupportedConfirm(name: String): Boolean {
        val attempt = ++confirmAttemptSeq
        val deferred = CompletableDeferred<Boolean>()
        confirmAttemptId = attempt
        confirmDeferred = deferred
        uiStage = ImportUiStage.UnsupportedConfirm(name, attempt)
        try {
            return deferred.await()
        } finally {
            if (confirmAttemptId == attempt && confirmDeferred === deferred) {
                confirmDeferred = null
                uiStage = null
            }
        }
    }

    /** 当前宿主交回本次确认 ([attemptId]) 的结果 (取消/关闭按拒绝)。 */
    fun resolveConfirm(attemptId: Long, confirmed: Boolean) {
        if (confirmAttemptId == attemptId) confirmDeferred?.complete(confirmed)
    }

    /** 本次确认框呈现真实失败 (show 抛错等): 原异常交回原等待, 不冒充用户拒绝。 */
    fun failConfirm(attemptId: Long, error: Throwable) {
        if (confirmAttemptId == attemptId) confirmDeferred?.completeExceptionally(error)
    }

    private companion object {
        /** 请求目录序号: 同一毫秒内并发导入也各有独立目录。 */
        val requestSequence = AtomicLong()
    }
}
