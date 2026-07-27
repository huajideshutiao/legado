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
import io.legado.app.help.toast.Toasters
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.remote.RemoteBook
import io.legado.app.utils.AlphanumComparator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 远程书籍 VM 共享核心 (KMP 版, commonMain)。
 *
 * 对照 app 端原 `RemoteBookViewModel(application: Application) : BaseViewModel(application)`:
 * - 去 Application / BaseViewModel 依赖, 改用 [CoroutineScope] 构造注入
 *   (Android = viewModelScope / 桌面 = rememberCoroutineScope())
 * - 去 MutableLiveData / callbackFlow, 改用 [MutableStateFlow] + [asStateFlow]
 *   (对照 [ServersViewModelShared] / [ServerConfigViewModelShared] 同模式)
 * - 去 RemoteBookWebDav (app 专属, 依赖 isNetworkAvailable / Uri / runBlocking init),
 *   改为直接调用已下沉的 [WebDav] + [RemoteBook.create] + [FileBook.importRemoteBook]
 *   (三者均为 commonMain expect/actual, 已在 jvmAndAndroidMain actual 实现)
 * - 去 AppWebDav.defaultBookWebDav (app 专属, 依赖 RemoteBookWebDav),
 *   改为 [AppWebDavShared.upConfig] + 手工拼接 rootBookUrl (与 AppWebDavShared 内部
 *   rootWebDavUrl + "books/" 同值, 因 AppWebDavShared.rootWebDavUrl 为 private 不可外部访问)
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

    /** 面包屑路径 (相对, UI 显示用, 对照 app Activity.path)。 */
    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
        scope.launch(Dispatchers.IO) {
            try {
                isDefaultWebdav = false
                val serverId = AppConfigProviders.get().remoteServerId
                val server = appDb.serverDao.get(serverId)
                val config = server?.getWebDavConfig()
                if (config != null) {
                    authorization = Authorization(config)
                    rootBookUrl = config.url
                    serverID = server.id
                } else {
                    // 默认 webdav (坚果云), 用 AppWebDavShared 完成认证
                    isDefaultWebdav = true
                    AppWebDavShared.upConfig()
                    authorization = AppWebDavShared.authorization
                        ?: throw NoStackTraceException("webDav没有配置")
                    rootBookUrl = computeDefaultRootBookUrl()
                    serverID = null
                }
                onSuccess()
            } catch (e: Throwable) {
                AppLog.put("初始化webDav出错\n${e.localizedMessage}", e)
                Toasters.get().toast("初始化webDav出错:${e.localizedMessage}")
            }
        }
    }

    /**
     * 默认 webdav 的书籍根 URL (对照 app AppWebDav.defaultBookWebDav.rootBookUrl)。
     *
     * AppWebDavShared.rootWebDavUrl 为 private 不可外部访问, 此处按相同逻辑重算:
     * - 配置 URL 为空时默认坚果云 https://dav.jianguoyun.com/dav/
     * - URL 不以 "/" 结尾自动补
     * - webDavDir 非空时追加为子目录
     * - 末尾追加 "books/" 子目录 (对照 app AppWebDav.exportsWebDavUrl 同值)
     */
    private fun computeDefaultRootBookUrl(): String {
        val configUrl = AppConfigProviders.get().webDavUrl
        var url = if (configUrl.isEmpty()) "https://dav.jianguoyun.com/dav/" else configUrl
        if (!url.endsWith("/")) url = "$url/"
        PreferenceProviders.get().getString(PreferKey.webDavDir, "legado")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { url = "${url}${it}/" }
        return "${url}books/"
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
        scope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true
                _error.value = null
                _items.value = emptyList()
                val webDavFileList: List<WebDavFile> = WebDav(targetPath, auth).listFiles()
                val remoteBooks = webDavFileList
                    .filter { file ->
                        file.isDir
                            || bookFileRegex.matches(file.displayName)
                            || archiveFileRegex.matches(file.displayName)
                    }
                    .map { RemoteBook.create(it) }
                _items.value = sortItems(remoteBooks)
            } catch (e: Throwable) {
                AppLog.put("获取webDav书籍出错\n${e.localizedMessage}", e)
                Toasters.get().toast("获取webDav书籍出错\n${e.localizedMessage}")
                _error.value = e.localizedMessage
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
        scope.launch(Dispatchers.IO) {
            try {
                selection.forEach { remoteBook ->
                    val webDav = WebDav(remoteBook.path, auth)
                    FileBook.importRemoteBook(
                        webDav = webDav,
                        serverID = serverID,
                        name = remoteBook.filename,
                        path = remoteBook.path,
                        size = remoteBook.size,
                        lastModify = remoteBook.lastModify,
                        downloadFile = true,
                    )
                    remoteBook.isOnBookShelf = true
                }
            } catch (e: Throwable) {
                AppLog.put("导入出错\n${e.localizedMessage}", e, true)
            } finally {
                finally()
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
        scope.launch(Dispatchers.IO) {
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
    fun sortCheck(newSortKey: RemoteBookSort, onSortChanged: (RemoteBookSort, Boolean) -> Unit) {
        if (sortKey == newSortKey) {
            sortAscending = !sortAscending
        } else {
            sortAscending = true
            sortKey = newSortKey
        }
        onSortChanged(sortKey, sortAscending)
        // 重新排序当前 items (无需重新请求网络)
        _items.value = sortItems(_items.value)
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
