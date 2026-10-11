package io.legado.app.ui.book.import.local

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.PreferKey
import io.legado.app.help.book.IMPORT_BOOK_PATH_KEY
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.import.ImportBookDirHost
import io.legado.app.ui.book.import.ImportDirRef
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.concurrent.newSynchronizedList
import io.legado.app.utils.mapParallel
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * 导入本地书籍目录状态机 (四端共用一份)。
 *
 * 落点在 data/commonMain: 依赖只到 foundation + data 的导入契约 ([ImportDirRef] /
 * [ImportBookDirHost] / [ImportFileItem]) 与偏好读写, 不引 UI/Compose; 安卓 (FileDoc/SAF)、
 * 桌面 (java.io.File)、iOS 与鸿蒙 (okio File) 各自只注入目录列举与条目化能力, 目录栈/面包屑/
 * 过滤/排序/扫描/空态由本状态机维护。
 *
 * 语义对照原版 `ImportBookActivity` + `ImportBookViewModel`:
 * - 目录栈逐级进入/返回, 面包屑 = 根名/各级子目录名/;
 * - 列表 = 目录列举 → 隐藏项/书籍/压缩包过滤 → 目录优先 + sort + 字母数字混合序兜底;
 * - 平铺扫描 = Channel 逐层递归并发 16, 清空后增量发布;
 * - 读当前目录失败保留根目录与当前层级, 只提示; 根目录解析失败才回空态终态。
 */
object ImportBookShared {

    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    private val _items = MutableStateFlow<List<ImportFileItem>>(emptyList())

    /** 文件列表: 子目录时前置"返回上级"项 (对照原版 initData 的 collect / publish) */
    val items: StateFlow<List<ImportFileItem>> = _items.asStateFlow()

    // 面包屑路径: 根目录名/各级子目录名/ (对照 upDocs 内 showBreadcrumb)
    private val _path = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = _path.asStateFlow()

    // 目录读取/扫描加载中 (对照 scanFolder 的 refreshProgressBar / reload 的 _loading)
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // 空态文案可见 (对照 initRootDoc/initRootPath 的 tvEmptyMsg)
    private val _emptyMsgVisible = MutableStateFlow(false)
    val emptyMsgVisible: StateFlow<Boolean> = _emptyMsgVisible.asStateFlow()

    /**
     * 当前目录是否还有上级目录 (返回键逐级返回的开关)。
     *
     * 语义是"当前目录是否在导入根目录之下", 与列表里有没有"返回上级"项无关 —— 平铺扫描
     * 只换列表内容、不改当前目录, 据此清零会让扫描后返回键直接退出页面。
     */
    private val _hasParentDir = MutableStateFlow(false)
    val hasParentDir: StateFlow<Boolean> = _hasParentDir.asStateFlow()

    private var rootDir: ImportDirRef? = null
    private val subDirs = mutableListOf<ImportDirRef>()
    private var filterKey: String = ""
    private var sort: Int = 0

    /** 当前目录的原始子项引用 (过滤排序前的列举结果) */
    private val currentChildren: MutableList<ImportDirRef> =
        newSynchronizedList(arrayListOf())

    /** 目录读取与扫描共用一个操作位: 新操作取消在途操作 (对照桌面 loadJob 的互斥语义) */
    private var loadJob: Job? = null

    /**
     * 在途操作序号: 加载态归最新一次操作所有 —— 被新操作取消的旧操作收尾时不得复位加载态,
     * 否则新操作刚置起的加载态会被旧操作的收尾清掉 (进度指示提前消失)。
     */
    private val loadEpoch = atomic(0)

    private var dirHost: ImportBookDirHost? = null

    /** 是否还没选定导入根目录 (页面进入后据此自动拉起选择器)。 */
    val needsRootDir: Boolean get() = rootDir == null

    /** 注入页级平台服务 (每端进程内一次; 挂接前不产生任何目录访问)。 */
    fun attach(host: ImportBookDirHost) {
        dirHost = host
    }

    /**
     * 初始化导入页状态 (对照 initRootDoc / DesktopImportBook.init / NativeImportBook.init):
     * 恢复排序设置与上次导入目录; 已有根目录时只重读当前目录 (重进页面能看到别处新增的文件)。
     *
     * @param restoreLast 是否从偏好恢复上次导入目录 (iOS 传 false: 上次目录的授权经
     *   bookmark 由平台另行恢复后走 [setRoot])
     * @return true = 尚无可用目录, 页面据此拉起一次选择器
     */
    suspend fun initPage(restoreLast: Boolean = true): Boolean {
        sort = PreferenceProviders.get().getInt(PreferKey.localBookImportSort, 0)
        if (rootDir != null) {
            upPath()
            return false
        }
        val last = if (restoreLast) {
            PreferenceProviders.get().getString(IMPORT_BOOK_PATH_KEY, "")
        } else ""
        val restored = last.takeIf { it.isNotEmpty() }?.let { dirHost?.resolveRoot(it) }
        if (restored != null) {
            setRoot(restored, persist = false)
            return false
        }
        clearRoot()
        return true
    }

    /** 切换导入根目录并读一次目录 (对照原版 selectFolder 回调里的 initRootDoc(true))。 */
    fun setRoot(ref: ImportDirRef, persist: Boolean = true) {
        rootDir = ref
        subDirs.clear()
        if (persist) {
            PreferenceProviders.get().putString(IMPORT_BOOK_PATH_KEY, ref.pathKey)
        }
        upPath()
    }

    /** 进入子目录 (对照 nextDoc / enterDir)。 */
    fun navigateImportDir(item: ImportFileItem) {
        val ref = dirHost?.dirRefOf(item) ?: return
        if (!ref.isDirectory) return
        subDirs.add(ref)
        upPath()
    }

    /** 返回上级; 已在根目录返回 false (对照 goBackDir)。 */
    fun goBackImportDir(): Boolean {
        if (subDirs.isEmpty()) return false
        subDirs.removeAt(subDirs.lastIndex)
        upPath()
        return true
    }

    /** 更新搜索过滤关键字: 只重排当前列表, 不重读目录 (对照原版 updateCallBackFlow)。 */
    fun updateImportBookFilter(key: String) {
        filterKey = key
        scope.launch { publishChildren() }
    }

    /** 更新排序方式并持久化 (对照原版 upSort); 非读取/扫描中立即重排当前列表。 */
    fun updateImportBookSort(value: Int) {
        sort = value
        PreferenceProviders.get().putInt(PreferKey.localBookImportSort, value)
        if (loadJob?.isActive != true) {
            scope.launch { publishChildren() }
        }
    }

    /**
     * 平铺扫描当前目录及子目录下的书籍文件 (对照原版 ImportBookViewModel.scanDoc:
     * Channel 逐层递归 + 并发 16 + 清空后增量发布)。
     *
     * @param maxDepth 递归层数上限; -1 = 不限 (原版语义)。桌面/native 注入 8, 防误选
     *   系统根目录后全盘遍历。
     */
    fun scanImportFolder(maxDepth: Int = -1) {
        val root = subDirs.lastOrNull() ?: rootDir ?: return
        startLoad {
            // 扫描只换列表内容: 清空但不显空态 (加载中转圈, 对照原版 dataCallback.clear())
            currentChildren.clear()
            _items.value = emptyList()
            _emptyMsgVisible.value = false
            try {
                val channel = Channel<ScanNode>(UNLIMITED)
                var pending = 1
                channel.trySend(ScanNode(root, 0))
                val found = arrayListOf<ImportDirRef>()
                channel.consumeAsFlow()
                    .mapParallel(16) { node ->
                        node to (node.ref.listChildren() ?: error("目录不可读"))
                    }.onEach { (node, children) ->
                        pending--
                        found.clear()
                        children.forEach { child ->
                            if (child.isDirectory) {
                                if (maxDepth < 0 || node.depth < maxDepth) {
                                    pending++
                                    channel.trySend(ScanNode(child, node.depth + 1))
                                }
                            } else if (child.displayName.matches(bookFileRegex) ||
                                child.displayName.matches(archiveFileRegex)
                            ) {
                                found.add(child)
                            }
                        }
                        addChildren(found)
                    }.takeWhile { pending > 0 }
                    .collect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure("扫描文件夹出错\n${e.message}", e)
            }
        }
    }

    /** 扫描递归节点 (携带层深, 支撑 maxDepth 限深)。 */
    private data class ScanNode(val ref: ImportDirRef, val depth: Int)

    /** 离开导入页: 停掉本页发起的目录读取/扫描 (对照原版 Activity 销毁即取消 scanDocJob)。 */
    fun cancelPageJobs() {
        loadJob?.cancel()
        loadJob = null
    }

    /** 重读当前目录并发布 (上架后更新"已在书架"标记等场景)。 */
    fun refresh() {
        if (rootDir != null) upPath()
    }

    /** 重建根目录失败时的清空 (对照 clearImportRootDoc): 清栈 + 空态, 是否拉起选择器由调用方决定。 */
    fun clearImportRoot() = clearRoot()

    /** 目录缺失/失效的共同终态: 清栈 + 清列表 + 空态 (对照 clearImportRootDoc)。 */
    private fun clearRoot() {
        rootDir = null
        subDirs.clear()
        currentChildren.clear()
        _items.value = emptyList()
        _path.value = null
        _emptyMsgVisible.value = true
        _hasParentDir.value = false
    }

    /**
     * 目录失败的用户可见提示: 记日志 + 平台提示 (安卓原生吐司 / iOS 与鸿蒙吐司 / 桌面托盘通知)。
     */
    private fun reportFailure(message: String, throwable: Throwable? = null) {
        AppLog.put(message, throwable)
        Toasters.get().toast(message)
    }

    /**
     * 起一次目录读取/扫描并接管加载态: 新操作取消在途操作, 加载态归最新一次操作所有。
     */
    private fun startLoad(block: suspend () -> Unit) {
        loadJob?.cancel()
        val epoch = loadEpoch.incrementAndGet()
        _loading.value = true
        loadJob = scope.launch {
            try {
                block()
            } finally {
                if (loadEpoch.value == epoch) {
                    _loading.value = false
                }
            }
        }
    }

    /** 读当前目录并发布 (对照 upPath → upDocs → loadDoc)。 */
    private fun upPath() {
        val root = rootDir ?: return
        startLoad {
            _emptyMsgVisible.value = false
            _hasParentDir.value = subDirs.isNotEmpty()
            // 面包屑: 根目录名/各级子目录名/ (对照原版 upDocs 的 showBreadcrumb 拼接)
            _path.value = (listOf(root.displayName) + subDirs.map { it.displayName })
                .joinToString("/") + "/"
            val dir = subDirs.lastOrNull() ?: root
            val children = runCatching { dir.listChildren() }.getOrElse {
                reportFailure("获取文件列表出错\n${it.message}", it)
                null
            }
            if (children == null) {
                // 读当前目录失败保留根目录与当前层级, 只提示 (对照原版 loadDoc 的 onError)
                return@startLoad
            }
            currentChildren.clear()
            currentChildren.addAll(children.filter { it.isListed() })
            publishChildren()
        }
    }

    /**
     * 目录列举的展示过滤 (对照原版 loadDoc 的 list 谓词): 隐藏项不列; 目录照列;
     * 其余只列书籍文件与压缩包 (txt/封面/db 等非书文件不进列表, 也就不可能被勾选上架)。
     */
    private fun ImportDirRef.isListed(): Boolean = when {
        displayName.startsWith(".") -> false
        isDirectory -> true
        else -> displayName.matches(bookFileRegex) || displayName.matches(archiveFileRegex)
    }

    /** 扫描增量: 追加子项引用并重发布 (对照原版 dataCallback.addItems)。 */
    private suspend fun addChildren(refs: List<ImportDirRef>) {
        currentChildren.addAll(refs)
        publishChildren()
    }

    /** 条目化 + 过滤 + 排序 + 发布 (对照原版 dataFlow.map 与 Desktop/Native publish)。 */
    private suspend fun publishChildren() {
        val children = currentChildren.toList()
        val skipFilter = filterKey.isBlank()
        // 排序规则 (三端逐字同源): 目录优先, 再按 sort (2=时间 / 1=大小 / 0=名称),
        // 最后按字母数字混合序兜底
        val comparator = when (sort) {
            2 -> compareBy<ImportFileItem>({ !it.isDir }, { -it.lastModified })
            1 -> compareBy({ !it.isDir }, { -it.size })
            else -> compareBy { !it.isDir }
        } then compareBy(AlphanumComparator) { it.name }
        // 条目化需读文件属性 (挂起方法), 故用挂起循环而不是序列算子; 过滤与排序口径不变
        val list = ArrayList<ImportFileItem>(children.size)
        var unreadableCount = 0
        for (ref in children) {
            if (!skipFilter && !ref.displayName.contains(filterKey)) continue
            val item = runCatching { ref.toItem() }.getOrElse {
                AppLog.put("读取导入文件属性失败\n${it.message}", it)
                null
            }
            if (item == null) {
                unreadableCount++
                continue
            }
            list.add(item)
        }
        list.sortWith(comparator)
        if (subDirs.isNotEmpty()) {
            // 子目录层级前置"返回上级"项 (对照原版 initData 的 collect / publish 的 upDir 分支)
            (subDirs.lastOrNull() ?: rootDir)?.let { parent ->
                runCatching { parent.toItem(isUpDir = true) }.getOrNull()?.let { upItem ->
                    list.add(0, upItem)
                }
            }
        }
        _items.value = list
        _emptyMsgVisible.value = list.isEmpty()
        if (unreadableCount > 0) {
            // 不可读条目已被排除出列表, 提示用户免得当成文件消失 (对照 native publish 的口径)
            reportFailure("有 $unreadableCount 个文件已消失或无法访问")
        }
    }
}
