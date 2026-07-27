package io.legado.app.ui.book.changecover

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.timeLimit
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.closeIfCloseable
import io.legado.app.help.coroutine.newFixedThreadPoolDispatcher
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.concurrent.newSynchronizedList
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.min

/**
 * 换封面 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `ChangeCoverViewModel(application: Application) : BaseViewModel(application)`:
 * - 核心业务编排 (启用书源加载 / 过滤 searchRule.coverUrl 非空 / 并发搜索 /
 *   命中 name==name && author==author && coverUrl 非空 时回调 / 默认封面占位项) 不依赖
 *   Android 专属 API, 仅依赖 [AppDbProviders] / [WebBook] / [mapParallelSafe] / 协程,
 *   可以下沉 commonMain 供多端复用。
 * - 状态用 [MutableStateFlow] 替代 `androidx.lifecycle.MutableLiveData` (LiveData 不可 KMP)。
 *   Android 宿主用 `viewModelScope.launch { collect { ... } }` 把 StateFlow 转发到
 *   MutableLiveData, 调用方 `observe` 用法不变。
 * - DAO 访问走 [AppDbProviders.get] (宿主启动时注册), 替代 app 端 `appDb` 单例。
 * - [Coroutine.async] (commonMain 版, 已下沉) 替代 `BaseViewModel.execute`, 行为等价
 *   (内部都是 SupervisorJob + Dispatchers.IO + 链式 onSuccess/onError 回调切到 mainDispatcher)。
 *   换封面无 onSuccess/onError 链式回调, 仅用 Coroutine.async 启动协程即可。
 *
 * # 留 app 端实现的部分 (Android-specific)
 *
 * 以下平台专属逻辑通过 [ChangeCoverPlatform] 聚合接口注入, 不在 commonMain 硬编码:
 * - **AppConfig.threadCount**: 依赖 SharedPreferences + appCtx, 未下沉;
 * - **AppPattern.authorRegex 清洗 author**: AppPattern 已下沉 commonMain, 但为保持
 *   注入风格一致 (与 [io.legado.app.ui.book.changesource.ChangeBookSourcePlatform] 一致),
 *   通过 [ChangeCoverPlatform.cleanAuthor] 注入, Android/桌面两端均直接用 AppPattern.authorRegex。
 *
 * # 设计选择 (组合委托模式)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用**组合委托**模式 (参考
 * [io.legado.app.ui.book.changesource.ChangeBookSourceViewModelShared]):
 * - app 端 ChangeCoverViewModel `extends BaseViewModel`, 内部持有本类实例;
 * - 通过 [ChangeCoverPlatform] 聚合接口注入平台专属依赖, 仅 1 个参数不算"超多";
 * - 转发 `initData / startSearch / startOrStopSearch / stopSearch / onCleared` 到本类;
 * - Android 专属部分 (initData 解析 Bundle) 在 app 端 ViewModel 处理后转发。
 *
 * # 与 app 端原实现的差异
 *
 * - `initData(arguments: Bundle?)` 改为 `initData(name: String, author: String)`:
 *   commonMain 无 Bundle, 由 app 端解析 Bundle 后转发; author 清洗通过
 *   [ChangeCoverPlatform.cleanAuthor] 在本类内完成 (app 端原在 initData 内用
 *   `author.replace(AppPattern.authorRegex, "")`)。
 * - `searchStateData: MutableLiveData<Boolean>` 改为 `searchState: MutableStateFlow<Boolean>`:
 *   app 端用 `viewModelScope.launch { collect { searchStateData.postValue(it) } }` 桥接。
 * - `dataFlow` (callbackFlow) 保留, 内部 `searchSuccess` / `upAdapter` 回调机制不变;
 *   `flowOn(IO)` 保留。
 * - `execute { ... }` 改为 `Coroutine.async(scope) { ... }`: 行为等价, 无 onSuccess/onError 链。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 * @param platform 平台专属依赖聚合 (threadCount + cleanAuthor)
 */
@Suppress("MemberVisibilityCanBePrivate")
class ChangeCoverViewModelShared(
    private val scope: CoroutineScope,
    private val platform: ChangeCoverPlatform,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    // region 状态流: 外部只读 StateFlow, 适配 Compose 重组 / Android asLiveData 桥接

    /**
     * 搜索中状态流 (对照原 `searchStateData: MutableLiveData<Boolean>`)。
     *
     * app 端用 `viewModelScope.launch { collect { searchStateData.postValue(it) } }` 桥接,
     * 行为与原 `searchStateData.postValue(true/false)` 等价。
     * 桌面端直接 `collectAsState` 即可。
     */
    private val _searchState = MutableStateFlow(false)
    val searchState: MutableStateFlow<Boolean> = _searchState
    // endregion

    /** 书名 (initData 写入)。app 端通过 getter 转发暴露。 */
    var name: String = ""
        private set

    /** 作者 (initData 写入, 已去 authorRegex)。app 端通过 getter 转发暴露。 */
    var author: String = ""
        private set

    /** 当前搜索使用的书源列表 (startSearch 时填充)。 */
    private var bookSources = arrayListOf<BookSource>()

    /** 搜索结果原始列表 (searchSuccess 时 add)。 */
    private val searchBooks = newSynchronizedList(arrayListOf<SearchBook>())

    /**
     * 默认封面占位项 (对照原 `defaultCover by lazy`)。
     *
     * `originName = "默认封面"` + `coverUrl = "use_default_cover"` 标记,
     * 调用方 (Dialog) 点击后通过 `onCoverSelected("use_default_cover")` 回调,
     * 由调用方决定如何处理 (app 端 BookHelp.clearCover / 桌面端清 customCoverUrl)。
     */
    private val defaultCover: List<SearchBook>
        get() = listOf(
            SearchBook(
                originName = "默认封面",
                name = name,
                author = author,
                coverUrl = "use_default_cover"
            )
        )

    /** 当前搜索任务 (stopSearch 时 cancel)。 */
    private var task: Job? = null

    /** 搜索线程池 (startSearch 时初始化, stopSearch/onCleared 时关闭)。 */
    private var searchPool: CoroutineDispatcher? = null

    /**
     * 搜索成功回调 (对照原 `private var searchSuccess: ((SearchBook) -> Unit)?`)。
     *
     * 由 [dataFlow] 的 callbackFlow 内部赋值 (订阅时设置, 取消时置 null),
     * search() 内命中 name/author/coverUrl 匹配时调用, 把 searchBook 加入 searchBooks
     * 并 trySend `defaultCover + searchBooks.sortedBy { it.originOrder }`。
     */
    private var searchSuccess: ((SearchBook) -> Unit)? = null

    /**
     * 刷新列表回调 (对照原 `private var upAdapter: (() -> Unit)?`)。
     *
     * 由 [dataFlow] 的 callbackFlow 内部赋值, startSearch 清空 searchBooks 后调用,
     * trySend `defaultCover + searchBooks.sortedBy { it.originOrder }` 通知下游刷新空列表。
     */
    private var upAdapter: (() -> Unit)? = null

    /**
     * 搜索结果 Flow (对照原 `dataFlow: callbackFlow`)。
     *
     * - `callbackFlow` 内创建 [searchSuccess] / [upAdapter] 回调 (订阅时设置, 取消时置 null);
     * - 若 searchBooks.size <= 1 (仅默认封面或空) 则启动 startSearch;
     * - `awaitClose { searchSuccess = null; upAdapter = null }` 在订阅取消时清理回调。
     *
     * app 端 Dialog 用 `viewModel.dataFlow.conflate().collect { items = it }` 消费;
     * 桌面端 Dialog 用 `LaunchedEffect(Unit) { dataFlow.conflate().collect { items = it } }`。
     */
    val dataFlow: Flow<List<SearchBook>> = callbackFlow {

        searchSuccess = { searchBook ->
            if (!searchBooks.contains(searchBook)) {
                searchBooks.add(searchBook)
                trySend(defaultCover + searchBooks.sortedBy { it.originOrder })
            }
        }

        upAdapter = {
            trySend(defaultCover + searchBooks.sortedBy { it.originOrder })
        }

        if (searchBooks.size <= 1) {
            startSearch()
        }

        awaitClose {
            searchBooks.clear()
            searchSuccess = null
            upAdapter = null
        }
    }.flowOn(IoDispatcher)

    /**
     * 初始化数据 (对照原 `initData(arguments: Bundle?)`)。
     *
     * 原 app 端从 Bundle 解析 name/author, 此处由 app 端解析后通过参数传入 commonMain。
     * author 清洗通过 [ChangeCoverPlatform.cleanAuthor] 在本类内完成
     * (app 端原在 initData 内用 `author.replace(AppPattern.authorRegex, "")`)。
     *
     * @param name 书名
     * @param author 作者 (原始值, 本方法内部调用 [ChangeCoverPlatform.cleanAuthor] 清洗)
     */
    fun initData(name: String, author: String) {
        this.name = name
        // author 清洗走 platform 注入 (AppPattern.authorRegex 已下沉 commonMain,
        // 各端 Platform 实现均直接调用; 抽象为接口方法保持注入风格一致)
        this.author = platform.cleanAuthor(author)
    }

    /**
     * 初始化搜索线程池 (对照原 `initSearchPool`)。
     *
     * 线程数取 `min(threadCount, AppConst.MAX_THREAD)`, 用 [newFixedThreadPoolDispatcher]
     * 创建 [CoroutineDispatcher] 供 search 协程调度 (替代原 Executors.newFixedThreadPool)。
     */
    private fun initSearchPool() {
        searchPool = newFixedThreadPoolDispatcher(min(platform.threadCount, AppConst.MAX_THREAD))
    }

    /**
     * 启动搜索 (对照原 `startSearch()`)。
     *
     * 1. stopSearch 取消旧任务
     * 2. 清空 searchBooks / bookSources
     * 3. upAdapter 通知下游刷新空列表
     * 4. 拉启用书源 + 过滤 searchRule.coverUrl 非空
     * 5. initSearchPool 创建线程池
     * 6. search() 并发搜索
     *
     * 与原 app 端的差异: 用 [Coroutine.async] 替代 `BaseViewModel.execute` (行为等价)。
     */
    fun startSearch() {
        Coroutine.async(scope) {
            stopSearch()
            searchBooks.clear()
            upAdapter?.invoke()
            bookSources.clear()
            // 过滤 searchRule.coverUrl 非空的源 (对照原 `filter { !it.searchRule.coverUrl.isNullOrBlank() }`)
            bookSources.addAll(
                appDb.bookSourceDao.allEnabled().filter { !it.searchRule.coverUrl.isNullOrBlank() }
            )
            initSearchPool()
            search()
        }
    }

    /**
     * 并发搜索所有 bookSources (对照原 `search()`)。
     *
     * - flow { emit(bookSource) } 串行发射书源;
     * - onStart 推送 searchState=true;
     * - mapParallelSafe(threadCount, bookSources.size) 并发执行 search(source), 单源超时 timeLimit;
     * - onCompletion 推送 searchState=false;
     * - catch 记录 AppLog。
     */
    private fun search() {
        task = scope.launch(searchPool!!) {
            flow {
                for (bs in bookSources) { emit(bs) }
            }.onStart {
                _searchState.value = true
            }.mapParallelSafe(platform.threadCount, bookSources.size) {
                withTimeout(timeLimit) {
                    search(it)
                }
            }.onCompletion {
                _searchState.value = false
            }.catch {
                AppLog.put("封面换源搜索出错\n${it.message}", it)
            }.collect()
        }
    }

    /**
     * 单源搜索 (对照原 `suspend fun search(source: BookSource)`)。
     *
     * 1. searchRule.coverUrl 为空则跳过;
     * 2. WebBook.getBookListAwait 取搜索结果 (shouldBreak = { it > 0 } 命中首条即停);
     * 3. 命中 name==name && author==author && coverUrl 非空 时调 searchSuccess 回调。
     */
    private suspend fun search(source: BookSource) {
        if (source.searchRule.coverUrl.isNullOrBlank()) {
            return
        }
        val searchBook = WebBook.getBookListAwait(
            source, name,
            shouldBreak = { it > 0 }).books.firstOrNull() ?: return
        if (searchBook.name == name && searchBook.author == author
            && !searchBook.coverUrl.isNullOrEmpty()
        ) {
            searchSuccess?.invoke(searchBook)
        }
    }

    /**
     * 启停切换 (对照原 `startOrStopSearch`)。
     *
     * task 不存在或不活跃则 startSearch, 否则 stopSearch。
     */
    fun startOrStopSearch() {
        if (task == null || !task!!.isActive) {
            startSearch()
        } else {
            stopSearch()
        }
    }

    /**
     * 停止搜索 (对照原 `stopSearch`)。
     *
     * cancel task + close searchPool + 推送 searchState=false。
     */
    fun stopSearch() {
        task?.cancel()
        searchPool?.closeIfCloseable()
        _searchState.value = false
    }

    /**
     * 释放资源 (对照原 `onCleared`)。
     *
     * app 端 ViewModel.onCleared 调用, 关闭 searchPool。
     */
    fun onCleared() {
        searchPool?.closeIfCloseable()
    }

}
