package io.legado.app.ui

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.AppDbProviders
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.toast.Toasters
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.import.ImportBookDirHost
import io.legado.app.ui.book.import.ImportDirRef
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.import.local.ImportBookShared
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.toReadRoute
import io.legado.app.utils.File
import io.legado.app.utils.copyFileAtomically
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * iOS/鸿蒙本地书条目 (对照 app 端 [io.legado.app.ui.book.import.local.ImportBook] /
 * desktop 端 DesktopImportFile, 用 nativeMain 的 okio [File] 替代 FileDoc/java.io.File)。
 */
class NativeImportFile(
    val file: File,
    override val isUpDir: Boolean = false,
    override var isOnBookShelf: Boolean = false,
) : ImportFileItem {
    // 目录属性/大小/时间都是文件系统调用: 外部目录需在 security-scoped 授权内取
    // (一次租约取齐三项, 不逐字段起停)
    private val meta: LocalFileMeta = if (isUpDir) LocalFileMeta(true, 0L, 0L) else
        withLocalDirAccess(file.path) {
            val dir = file.isDirectory
            LocalFileMeta(dir, if (dir) 0L else file.length(), file.lastModified())
        }

    override val name: String = if (isUpDir) ".." else file.name
    override val isDir: Boolean = meta.isDir
    override val size: Long = meta.size
    override val lastModified: Long = meta.lastModified
    override val tag: String = file.name.substringAfterLast(".")
    override val itemKey: Any = file.absolutePath
}

/** [NativeImportFile] 构造时一次性取齐的文件元数据。 */
internal data class LocalFileMeta(
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * iOS/鸿蒙本地书导入薄注入 (状态与扫描在 data/commonMain [ImportBookShared], 与桌面共用)。
 *
 * # 与 desktop 的差异 (保留在本 object 与 [NativeImportDirRef])
 * - **无 SAF**: 目录遍历直接用 nativeMain okio [File] (listFiles), 每次文件系统调用都包
 *   [withLocalDirAccess] 授权租约 —— iOS 只能浏览用户经 UIDocumentPicker 显式授权的目录
 *   (minimal bookmark 持久化, 冷启动由 IosPlatformCapabilities.initImportBookData 恢复),
 *   鸿蒙目录经 picker URI 归一为 POSIX 路径, 桥接未就绪时降级返回 null;
 * - **沙盒语义**: desktop 上架只引用原文件路径, 移动端把普通书文件**复制**进
 *   `{filesDir}/books`, bookUrl 指向沙盒内副本, 保证重启后仍可读;
 * - **压缩包**: 无压缩包内选章阅读 UI, openReader 走 [FileAssociationDispatch]
 *   的直接导入链 (对照 Android startRead 的 onArchiveFileClick 分支, 平台能力上限)。
 */
object NativeImportBook {

    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    val items get() = ImportBookShared.items
    val path get() = ImportBookShared.path
    val loading get() = ImportBookShared.loading
    val emptyMsgVisible get() = ImportBookShared.emptyMsgVisible
    val hasParentDir get() = ImportBookShared.hasParentDir

    /** 是否还没选定导入根目录 (页面进入后据此自动拉起选择器)。 */
    val needsRootDir get() = ImportBookShared.needsRootDir

    /** 已上架判定缓存 (一次查询供整页条目判定, key = 文件名; native 上架是复制进沙盒, 文件名即副本名) */
    @Volatile
    private var shelfNames: Set<String> = emptySet()

    private suspend fun refreshShelfNames() {
        shelfNames = runCatching {
            AppDbProviders.get().bookDao.all().mapTo(HashSet()) { it.originName }
        }.getOrDefault(emptySet())
    }

    /**
     * okio File 的目录引用包装 (状态机经它列举/条目化)。
     *
     * [isDirectory] 取列举时在同一份授权租约内观测到的目录属性: 容器外路径离开租约即不可读,
     * 每次询问现取租约会让一份列表逐个起停 security scope。
     */
    private class NativeImportDirRef(
        val dir: File,
        override val isDirectory: Boolean,
        private val shelfNamesProvider: () -> Set<String>,
    ) : ImportDirRef {
        override val displayName: String get() = dir.name
        override val pathKey: String get() = dir.path

        override suspend fun listChildren(): List<ImportDirRef>? = withLocalDirAccess(dir.path) {
            dir.listFiles()?.map { NativeImportDirRef(it, it.isDirectory, shelfNamesProvider) }
        }

        override suspend fun toItem(isUpDir: Boolean): ImportFileItem? = try {
            NativeImportFile(
                dir,
                isUpDir = isUpDir,
                isOnBookShelf = !isUpDir && shelfNamesProvider().contains(dir.name),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("读取导入文件属性失败: ${dir.path}\n${e.message}", e)
            null
        }
    }

    private val importDirHost = object : ImportBookDirHost {
        override fun dirRefOf(item: ImportFileItem): ImportDirRef? =
            // 用条目构造时在授权租约内取到的目录属性; 裸读文件系统在容器外路径上不受授权保护
            (item as? NativeImportFile)?.takeIf { it.isDir }
                ?.let { NativeImportDirRef(it.file, isDirectory = true) { shelfNames } }

        override suspend fun resolveRoot(pathKey: String): ImportDirRef? {
            if (pathKey.isBlank()) return null
            val file = File(pathKey)
            if (!canReadDirectory(file)) return null
            return NativeImportDirRef(file, isDirectory = true) { shelfNames }
        }
    }

    /**
     * 初始化导入页状态。
     *
     * @param restoreLast 是否从 prefs 恢复上次目录。iOS 传 false: 上次导入目录的授权经
     *   minimal bookmark 由 IosPlatformCapabilities.initImportBookData 另行恢复 (不走 prefs 路径);
     *   鸿蒙传 true (picker 归一化路径直接可读)。
     */
    suspend fun init(restoreLast: Boolean = true) {
        ImportBookShared.attach(importDirHost)
        refreshShelfNames()
        ImportBookShared.initPage(restoreLast)
    }

    /** 切换导入根目录 (对照 Android initRootPath / desktop setRoot); 入参来自各端目录选择器, 恒为目录。 */
    fun setRoot(dir: String) {
        ImportBookShared.setRoot(NativeImportDirRef(File(dir), isDirectory = true) { shelfNames })
    }

    /** 进入子目录 (对照 Android nextDoc)。 */
    fun enterDir(item: ImportFileItem) {
        ImportBookShared.navigateImportDir(item)
    }

    private fun canReadDirectory(file: File): Boolean = try {
        withLocalDirAccess(file.path) { file.isDirectory }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.put("访问导入目录失败: ${file.path}\n${e.message}", e)
        Toasters.get().toast("无法访问目录: ${file.name}\n${e.message}")
        false
    }

    /** 返回上级; 已在根目录返回 false (对照 Android goBackDir)。 */
    fun goBack(): Boolean = ImportBookShared.goBackImportDir()

    /** 更新搜索过滤关键字 (对照 Android updateCallBackFlow)。 */
    fun updateFilter(key: String) {
        ImportBookShared.updateImportBookFilter(key)
    }

    /** 更新排序方式并持久化 (对照 Android upSort)。 */
    fun updateSort(value: Int) {
        ImportBookShared.updateImportBookSort(value)
    }

    /** 平铺扫描当前目录及子目录下的书籍文件 (对照 ImportBookViewModel.scanDoc); 限深 8 层。 */
    fun scan() {
        scope.launch { refreshShelfNames() }
        ImportBookShared.scanImportFolder(maxDepth = 8)
    }

    /**
     * 上架选中条目 (对照 ImportBookViewModel.addToBookshelf)。
     *
     * [onNeedBooksDir] 供"压缩包导入需要书籍保存位置而尚未设置"的场景接到"书籍保存位置"
     * 出口 (与"其它设置 → 书籍保存位置"同一选择器/同一偏好键), 返回 true 再重试该条导入;
     * 返回 false (用户取消) 或传 null 时按导入出错提示。普通书文件导入不经它。
     */
    fun addToBookshelf(
        selection: List<ImportFileItem>,
        onComplete: () -> Unit,
        onNeedBooksDir: (suspend () -> Boolean)? = null,
    ) {
        scope.launch {
            importItems(selection.filterIsInstance<NativeImportFile>(), onNeedBooksDir)
            onComplete()
        }
    }

    /**
     * 导入系统面板选定的文件 (不经列表勾选)。
     *
     * 选定文件是临时件 (iOS 的 Import 副本 / 鸿蒙的缓存物化件), 一律先复制进沙盒再导入:
     * 压缩包在包内没有书籍文件时导入的是压缩包自身 (见 FileBookLocalImportShared), 引用临时件会在
     * 临时件被清掉后失效; 其余情形复制后与列表上架同一条链。
     */
    fun importPickedFiles(
        paths: List<String>,
        onComplete: () -> Unit,
        onNeedBooksDir: (suspend () -> Boolean)? = null,
    ) {
        scope.launch {
            val items = paths.mapNotNull { path ->
                runCatching { NativeImportFile(sandboxCopy(File(path))) }
                    .onFailure {
                        AppLog.put("导入 ${File(path).name} 出错\n${it.message}", it)
                        Toasters.get().toast("导入 ${File(path).name} 出错\n${it.message}")
                    }
                    .getOrNull()
            }
            importItems(items, onNeedBooksDir)
            onComplete()
        }
    }

    /** 逐项导入 + 刷新已上架标记与列表 (两条入口共用)。 */
    private suspend fun importItems(
        items: List<NativeImportFile>,
        onNeedBooksDir: (suspend () -> Boolean)?,
    ) {
        items.forEach { item ->
            runCatching { importOne(item, onNeedBooksDir) }
                .onSuccess { item.isOnBookShelf = true }
                .onFailure { e ->
                    AppLog.put("导入 ${item.name} 出错\n${e.message}", e)
                    Toasters.get().toast("导入 ${item.name} 出错\n${e.message}")
                }
        }
        refreshShelfNames()
        ImportBookShared.refresh()
    }

    /** 导入单个条目; 保存位置未设置时先选目录再重试一次 (其余导入异常照旧上抛)。 */
    private suspend fun importOne(
        item: NativeImportFile,
        onNeedBooksDir: (suspend () -> Boolean)?,
    ) {
        val doImport: () -> Unit = {
            val file = item.file
            if (file.name.matches(AppPattern.archiveFileRegex)) {
                // 压缩包 (zip 内含书籍文件可解压导入; rar/7z native 端无解析器,
                // importLocalFile 抛明确异常, 不静默跳过) —— 解压产物落"书籍保存位置"
                FileBook.importLocalFile(file.path)
            } else {
                // 普通书文件: 先复制进沙盒再导入 (见类注释"沙盒语义")
                FileBook.importLocalFile(sandboxCopy(file).path)
            }
        }
        try {
            doImport()
        } catch (e: InvalidBooksDirException) {
            if (onNeedBooksDir?.invoke() != true) throw e
            doImport()
        }
    }

    /**
     * 打开已上架书籍阅读页 (对照 Android startRead)。
     *
     * - 压缩包: native 无压缩包内选章阅读 UI, 走 [FileAssociationDispatch]
     *   (解压 → 导入 → 打开首个书籍文件), 与文件关联导入同链;
     * - 普通书: 按文件名查库, 未入库直接返回 (与 Android `getBookByFileName?.let` 行为一致);
     *   入库后确保书文件在沙盒内 (旧数据引用外部目录时复制修正 bookUrl), 再推阅读路由。
     */
    fun openReader(item: ImportFileItem) {
        val file = (item as? NativeImportFile)?.file ?: return
        val fileName = file.name
        if (fileName.matches(AppPattern.archiveFileRegex)) {
            // 外部压缩包的解压/导入全程包在授权租约内 (deCompress 直接读原文件;
            // 解出后的书籍文件已落沙盒, 由后续链路自行处理)
            scope.launch {
                try {
                    withLocalDirAccess(file.path) { FileAssociationDispatch.dispatch(file.path) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put("打开压缩包失败: ${file.path}\n${e.message}", e)
                    Toasters.get().toast("无法打开压缩包: ${file.name}\n${e.message}")
                }
            }
            return
        }
        scope.launch {
            val bookDao = AppDbProviders.get().bookDao
            val book = bookDao.getBookByFileName(fileName) ?: return@launch
            val saved = runCatching { sandboxCopy(file) }.getOrElse { return@launch }
            val bookUrl = "file://" + saved.absolutePath
            if (book.bookUrl != bookUrl) {
                book.bookUrl = bookUrl
                runCatching { bookDao.update(book) }
            }
            AppNavigatorProviders.get().push(book.toReadRoute())
        }
    }

    /**
     * 复制书文件到沙盒 books 目录 (`{filesDir}/books`, 应用私有导入副本,
     * 与"书籍保存位置"无关 —— 原版 importLocalFile 不复制, 三端外部文件为保住重启后可读
     * 才落沙盒).
     *
     * 同名直接覆盖 (与 FileBook 重复导入的覆写语义一致): 复制进临时件再整份改名就位,
     * 中途失败时已有副本内容照旧 (见 [copyFileAtomically]); 源已在目标目录则跳过。
     * 源文件读取 (外部目录) 包在授权租约内, 复制完即释放。
     */
    private fun sandboxCopy(src: File): File {
        val dir = File(AppFilesDirs.get().filesDir, "books")
        dir.mkdirs()
        val dest = File(dir, src.name)
        if (dest.absolutePath == src.absolutePath) return dest
        return withLocalDirAccess(src.path) { copyFileAtomically(src, dest) }
    }
}
