package io.legado.app.ui.book.import.remote

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.remote.RemoteBook
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.isSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 远程书籍 VM 共享核心 (KMP 版, commonMain)。
 *
 * 对照 app 端原 `RemoteBookViewModel(application: Application) : BaseViewModel(application)`:
 * - 去 Application / BaseViewModel 依赖, 改用 [CoroutineScope] 构造注入
 *   (Android = viewModelScope / 桌面 = rememberCoroutineScope())
 * - 去 MutableLiveData / callbackFlow, 改用 [MutableStateFlow] + [asStateFlow]
 *   (对照 [ServersViewModelShared] / [ServerConfigViewModelShared] 同模式)
 * - WebDav 连接初始化与文件操作通过 [RemoteBookConnectionProvider]/[RemoteBookOperations]
 *   注入；默认实现使用 [AppWebDavShared]/[WebDav]/[FileBook]，Android 注入原
 *   RemoteBookWebDav/网络检查/SAF 下载语义，避免共享简化实现改变 app 行为。
 *
 * # 设计选择 (组合委托)
 *
 * 与 [ServersViewModelShared] / [ServerConfigViewModelShared] 一致, 不采用 expect abstract
 * 让 app 端子类继承: BaseViewModel 是 AndroidViewModel, commonMain 不可用, Kotlin 单继承冲突。
 * 改用组合委托模式: app 端 RemoteBookViewModel 内部持有本类实例, 通过构造函数注入 [scope]
 * (app 端 = viewModelScope), 方法转发到本类, app 端调用方接口不变。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = viewModelScope / 桌面 = rememberCoroutineScope())
 * @param onStartRead 已上架书籍点击阅读时的启动回调 (平台注入, app 端走 startReadBook,
 *   桌面端切到 READER 路由 + 设置 readerBook)
 */
class RemoteBookViewModelShared(
    private val scope: CoroutineScope,
    private val onStartRead: (Book) -> Unit = {},
    private val connectionProvider: RemoteBookConnectionProvider = DefaultRemoteBookConnectionProvider(),
    private val operations: RemoteBookOperations = DefaultRemoteBookOperations(),
    private val onPermissionDenied: () -> Unit = {},
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    var sortKey: RemoteBookSort = RemoteBookSort.Default
        private set
    var sortAscending: Boolean = false
        private set
    var isDefaultWebdav: Boolean = false
        private set

    /** 子目录栈 (面包屑路径用, 空表示在根目录), 对照 app VM dirList。 */
    val dirList: MutableList<RemoteBook> = mutableListOf()

    private val _items = MutableStateFlow<List<RemoteBook>>(emptyList())
    val items: StateFlow<List<RemoteBook>> = _items.asStateFlow()
    private var sourceItems: List<RemoteBook> = emptyList()
    private var filterKey: String? = null

    /** 面包屑路径 (相对, UI 显示用, 对照 app Activity.path)。 */
    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _refreshVersion = MutableStateFlow(0)
    val refreshVersion: StateFlow<Int> = _refreshVersion.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // 当前 WebDav 配置 (initData 设置)
    private var authorization: Authorization? = null
    private var rootBookUrl: String? = null
    private var serverID: Long? = null

    /** 最近一次 loadPath 的实际 URL (供 [refresh] 使用)。 */
    private var lastLoadedPath: String? = null

    /**
     * 初始化 WebDav 配置 (对照 app VM initData)。
     *
     * - remoteServerId 对应 Server 存在且 config 非空: 走自定义服务器
     * - 否则: 走默认 webdav (坚果云), 调 [AppWebDavShared.upConfig] 完成认证,
     *   手工拼接 rootBookUrl = rootWebDavUrl + "books/" (对照 app AppWebDav.defaultBookWebDav)
     *
     * @param onSuccess 初始化成功回调 (调用方通常 [upPath] 拉取根目录列表)
     */
    fun initData(onSuccess: () -> Unit) {
        scope.launch(IoDispatcher) {
            try {
                val connection = connectionProvider.resolve()
                isDefaultWebdav = connection.isDefaultWebdav
                authorization = connection.authorization
                rootBookUrl = connection.rootBookUrl
                serverID = connection.serverID
                withContext(mainDispatcher) { onSuccess() }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                AppLog.put("初始化webDav出错\n${e.message}", e)
                Toasters.get().toast("初始化webDav出错:${e.message}")
            }
        }
    }

    /**
     * 加载指定路径下的远程书籍列表 (对照 app VM loadRemoteBookList)。
     *
     * 不修改 [_currentPath] (面包屑由 [upPath] 维护), 仅记录 [lastLoadedPath] 供 [refresh]。
     *
     * @param path 远程路径, null 时用 [rootBookUrl]
     */
    fun loadPath(path: String?) {
        val auth = authorization
        val rootUrl = rootBookUrl
        if (auth == null || rootUrl == null) {
            Toasters.get().toast("没有配置webDav")
            return
        }
        val targetPath = path ?: rootUrl
        lastLoadedPath = targetPath
        scope.launch(IoDispatcher) {
            try {
                _isLoading.value = true
                _error.value = null
                sourceItems = emptyList()
                publishItems()
                val remoteBooks = operations.listFiles(targetPath, auth)
                    .filter { file ->
                        file.isDir
                            || bookFileRegex.matches(file.displayName)
                            || archiveFileRegex.matches(file.displayName)
                    }
                    .map { RemoteBook.create(it) }
                sourceItems = remoteBooks
                publishItems()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                AppLog.put("获取webDav书籍出错\n${e.message}", e)
                Toasters.get().toast("获取webDav书籍出错\n${e.message}")
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 进入子目录或返回根目录 (对照 app Activity.upPath + VM.loadRemoteBookList)。
     *
     * - isDefaultWebdav=true 时根路径前缀 "books/", 否则 "/"
     * - 拼接 [dirList] 中各 filename 形成面包屑路径
     * - 实际加载路径: [dirList] 非空时取末尾 path, 否则用 [rootBookUrl]
     */
    fun upPath() {
        val breadcrumb = (if (isDefaultWebdav) "books/" else "/") +
            dirList.joinToString("") { "${it.filename}/" }
        _currentPath.value = breadcrumb
        loadPath(dirList.lastOrNull()?.path ?: rootBookUrl)
    }

    /**
     * 加入书架 (对照 app VM.addToBookshelf + FileBook.importRemoteBook)。
     *
     * @param selection 选中的远程书籍集合
     * @param finally 完成回调 (无论成功失败均调用, 调用方清空 selection / 刷新列表)
     */
    fun addSelectionToBookshelf(
        selection: Set<RemoteBook>,
        finally: () -> Unit,
    ) {
        val auth = authorization
        if (auth == null) {
            Toasters.get().toast("没有配置webDav")
            finally()
            return
        }
        scope.launch(IoDispatcher) {
            try {
                // 对照 app 端 addSelectionToBookshelf / addToBookShelfAgain: loading=true
                _isLoading.value = true
                selection.forEach { remoteBook ->
                    operations.importRemoteBook(
                        authorization = auth,
                        serverID = serverID,
                        remoteBook = remoteBook,
                    )
                    remoteBook.isOnBookShelf = true
                }
                publishItems()
                _refreshVersion.value += 1
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                AppLog.put("导入出错\n${e.message}", e, true)
                if (e.isSecurityException()) onPermissionDenied()
            } finally {
                // 对照 app 端 callback 内 loading=false
                _isLoading.value = false
                withContext(mainDispatcher) { finally() }
            }
        }
    }

    /**
     * 开始阅读已上架书籍 (对照 app RemoteBookActivity.startRead)。
     *
     * - 压缩包格式 (.zip/.rar/...) 走 archive 路径, app 端依赖 defaultBookTreeUri + SAF,
     *   桌面端无 SAF, 暂不实现 (TODO)
     * - 普通格式 (txt/epub/pdf/cbz) 通过 bookDao.getBookByFileName 查找本地书籍,
     *   找到则调 [onStartRead] 启动阅读
     *
     * @param remoteBook 远程书籍条目 (须已上架, 否则 no-op)
     */
    fun startRead(remoteBook: RemoteBook) {
        val filename = remoteBook.filename
        if (archiveFileRegex.matches(filename)) {
            // TODO: 压缩包格式阅读依赖平台专属 (app: SAF + FileDoc; desktop: 暂未实现)
            return
        }
        scope.launch(IoDispatcher) {
            val book = appDb.bookDao.getBookByFileName(filename)
            if (book != null) {
                onStartRead(book)
            }
        }
    }

    /** 刷新当前路径 (对照 app Activity 拉顶栏刷新按钮)。 */
    fun refresh() {
        loadPath(lastLoadedPath)
    }

    /**
     * 排序切换 (对照 app RemoteBookActivity.sortCheck)。
     *
     * @param newSortKey 新排序键 (与当前相同则切换升降序, 否则重置为升序)
     * @param onSortChanged 排序状态变更回调 (调用方刷新 UI 显示的 sortKeyState)
     */
    fun sortCheck(
        newSortKey: RemoteBookSort,
        onSortChanged: (RemoteBookSort, Boolean) -> Unit,
        reorderCurrent: Boolean = true,
    ) {
        if (sortKey == newSortKey) {
            sortAscending = !sortAscending
        } else {
            sortAscending = true
            sortKey = newSortKey
        }
        onSortChanged(sortKey, sortAscending)
        // Android 原入口随后 upPath 重新请求，避免在此产生一次原版没有的中间列表更新。
        if (reorderCurrent) publishItems()
    }

    /** 对照 app VM updateCallBackFlow/DataCallback.screen，空关键字恢复完整列表。 */
    fun updateFilter(key: String?) {
        filterKey = key?.takeIf { it.isNotBlank() }
        publishItems()
    }

    private fun publishItems() {
        val filtered = filterKey?.let { key ->
            sourceItems.filter { it.filename.contains(key) }
        } ?: sourceItems
        // copy 对齐旧 callbackFlow 每次 emit 新列表引用，也让 isOnBookShelf 原地变更可靠触发 UI。
        _items.value = sortItems(filtered).map { it.copy() }
    }

    /** 按 sortKey + sortAscending 排序 (对照 app VM dataFlow.map 排序逻辑)。 */
    private fun sortItems(list: List<RemoteBook>): List<RemoteBook> {
        val secondary: Comparator<RemoteBook> = when (sortKey) {
            RemoteBookSort.Name -> compareBy(AlphanumComparator) { it: RemoteBook -> it.filename }
            else -> compareBy { it: RemoteBook -> it.lastModify }
        }.let { if (sortAscending) it else it.reversed() }
        return list.sortedWith(compareBy<RemoteBook> { !it.isDir }.then(secondary))
    }
}

data class RemoteBookConnection(
    val authorization: Authorization,
    val rootBookUrl: String,
    val serverID: Long?,
    val isDefaultWebdav: Boolean,
)

interface RemoteBookConnectionProvider {
    suspend fun resolve(): RemoteBookConnection
}

private class DefaultRemoteBookConnectionProvider : RemoteBookConnectionProvider {
    override suspend fun resolve(): RemoteBookConnection {
        val serverId = AppConfigProviders.get().remoteServerId
        val server = AppDbProviders.get().serverDao.get(serverId)
        val config = server?.getWebDavConfig()
        if (config != null) {
            return RemoteBookConnection(
                authorization = Authorization(config),
                rootBookUrl = config.url,
                serverID = server.id,
                isDefaultWebdav = false,
            )
        }

        AppWebDavShared.upConfig()
        val authorization = AppWebDavShared.authorization
            ?: throw NoStackTraceException("webDav没有配置")
        val configUrl = AppConfigProviders.get().webDavUrl
        var rootUrl = if (configUrl.isEmpty()) "https://dav.jianguoyun.com/dav/" else configUrl
        if (!rootUrl.endsWith("/")) rootUrl = "$rootUrl/"
        PreferenceProviders.get().getString(PreferKey.webDavDir, "legado")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { rootUrl = "$rootUrl$it/" }
        return RemoteBookConnection(
            authorization = authorization,
            rootBookUrl = "${rootUrl}books/",
            serverID = null,
            isDefaultWebdav = true,
        )
    }
}

interface RemoteBookOperations {
    suspend fun listFiles(path: String, authorization: Authorization): List<WebDavFile>

    suspend fun importRemoteBook(
        authorization: Authorization,
        serverID: Long?,
        remoteBook: RemoteBook,
    )
}

private class DefaultRemoteBookOperations : RemoteBookOperations {
    override suspend fun listFiles(
        path: String,
        authorization: Authorization,
    ): List<WebDavFile> = WebDav(path, authorization).listFiles()

    override suspend fun importRemoteBook(
        authorization: Authorization,
        serverID: Long?,
        remoteBook: RemoteBook,
    ) {
        FileBook.importRemoteBook(
            webDav = WebDav(remoteBook.path, authorization),
            serverID = serverID,
            name = remoteBook.filename,
            path = remoteBook.path,
            size = remoteBook.size,
            lastModify = remoteBook.lastModify,
            downloadFile = true,
        )
    }
}
