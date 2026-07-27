package io.legado.app.ui.book.changesource

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.timeLimit
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.primaryStr
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.closeIfCloseable
import io.legado.app.help.coroutine.newFixedThreadPoolDispatcher
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.concurrent.newConcurrentMap
import io.legado.app.utils.concurrent.newSynchronizedList
import io.legado.app.utils.internString
import io.legado.app.utils.mapParallel
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.onEachIndexed
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.min

/**
 * 换源 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `ChangeBookSourceViewModel(application: Application) : BaseViewModel(application)`:
 * - 核心业务编排 (书源加载 / 并发搜索 / 排序 / 字数加载 / 切源 / 评分 / 置顶置底 / 禁用删除 /
 *   自动换源 / 刷新列表 / 筛选) 不依赖 Android 专属 API, 仅依赖 [AppDbProviders] /
 *   [WebBook] / [SourceHelp] / [Coroutine] / 协程, 可以下沉 commonMain 供多端复用。
 * - 状态用 [MutableStateFlow] 替代 `androidx.lifecycle.MutableLiveData` (LiveData 不可 KMP)。
 *   Android 宿主用 `viewModelScope.launch { shared.searchState.collect { ... } }` 把 StateFlow
 *   转发到 MutableLiveData, 调用方 `observe` 用法不变 (项目未引入 lifecycle-livedata-ktx,
 *   不用 `StateFlow.asLiveData()` 扩展)。
 * - DAO 访问走 [AppDbProviders.get] (宿主启动时注册), 替代 app 端 `appDb` 单例。
 * - [Coroutine.async] (commonMain 版, 已下沉) 替代 `BaseViewModel.execute`, 行为等价
 *   (内部都是 SupervisorJob + Dispatchers.IO + 链式 onSuccess/onError 回调切到 mainDispatcher)。
 *
 * # 留 app 端实现的部分 (Android-specific)
 *
 * 以下平台专属逻辑通过 [ChangeBookSourcePlatform] 聚合接口注入, 不在 commonMain 硬编码:
 * - **AppConfig 4 个换源开关** + threadCount + searchGroup 读写:
 *   `AppConfig.changeSourceCheckAuthor / changeSourceLoadInfo / changeSourceLoadToc /
 *   changeSourceLoadWordCount / threadCount / searchGroup` 依赖 SharedPreferences + appCtx,
 *   未下沉 (AppConfigAccessor 接口暂未包含 4 个 changeSource* 字段)。
 * - **BookHelp.getDurChapter**: 依赖 `EscapeUtils.jaccardSimilarity` + `StringUtils.fullToHalf`
 *   + `Pattern` 等字符串处理, BookHelp 重 Android 依赖 (BitmapFactory/ParcelFileDescriptor 等),
 *   留 app 端。
 * - **ContentProcessor.getContent**: 依赖 `appDb.replaceRuleDao` + `BookHelp` + `Pattern` 等,
 *   ContentProcessor 重 Android 依赖, 留 app 端。
 * - **SourceConfig 评分 3 个方法** (getBookScore/setBookScore/getSourceScore):
 *   SourceConfig 已下沉 commonMain (走 PreferenceProviders), 各端 platform 实现直接调用;
 *   仍通过 [ChangeBookSourcePlatform] 注入以保持与 getDurChapter/processContent 等聚合一致。
 * - **toastOnUi**: Android Context 专属。
 *
 * # 设计选择 (避免超多继承与参数传递)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用**组合委托**模式 (参考 [TocViewModelShared]):
 * - app 端 ChangeBookSourceViewModel `extends BaseViewModel`, 内部持有本类实例;
 * - 通过 [ChangeBookSourcePlatform] 聚合接口注入平台专属依赖, 仅 1 个参数不算"超多";
 * - 转发 `initData / startSearch / refresh / screen / getToc / disableSource / topSource /
 *   bottomSource / del / autoChangeSource / setBookScore / getBookScore / startRefreshList /
 *   stopSearch / startOrStopSearch / onLoadWordCountChecked` 到本类;
 * - Android 专属部分 (initData 解析 Bundle / toastOnUi) 在 app 端 ViewModel 处理后转发。
 *
 * # ChangeChapterSourceViewModel 兼容
 *
 * app 端 [ChangeChapterSourceViewModel] 继承 [ChangeBookSourceViewModel], 覆盖 initData
 * 调用 super.initData 添加 chapterIndex/chapterTitle 解析。本类 initData 不接收 Bundle,
 * app 端 ChangeBookSourceViewModel.initData 保留 (Bundle?, Book?, Boolean) 签名, 解析后
 * 转发到本类 (name, author, fromReadBookActivity, oldBook), 子类签名不变。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 * @param platform 平台专属依赖聚合 (AppConfig 4 开关 + threadCount + searchGroup 读写 +
 *   BookHelp.getDurChapter + ContentProcessor.getContent + SourceConfig 评分 3 方法 +
 *   toastOnUi)
 */
@Suppress("MemberVisibilityCanBePrivate")
class ChangeBookSourceViewModelShared(
    private val scope: CoroutineScope,
    private val platform: ChangeBookSourcePlatform,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    // region 状态流: 外部只读 StateFlow, 适配 Compose 重组 / Android asLiveData 桥接

    /**
     * 搜索中状态流 (对照原 `searchStateData: MutableLiveData<Boolean>`)。
     *
     * app 端用 `viewModelScope.launch { collect { searchStateData.postValue(it) } }` 桥接,
     * 行为与原 `searchStateData.postValue(true/false)` 等价。
     */
    private val _searchState = MutableStateFlow(false)
    val searchState: MutableStateFlow<Boolean> = _searchState

    /**
     * 换源进度流 (对照原 `_changeSourceProgress: MutableStateFlow<Pair<Int, String>>`)。
     *
     * 原本就是 StateFlow, app 端直接转发 `changeSourceProgress` 即可。
     */
    private val _changeSourceProgress = MutableStateFlow(0 to "")
    val changeSourceProgress = _changeSourceProgress.asStateFlow()
    // endregion

    /**
     * 搜索完成回调 (对照原 `searchFinishCallback: ((isEmpty: Boolean) -> Unit)?`)。
     *
     * 由 app 端 ViewModel 暴露给 Dialog 设置, 内部 search/onCompletion 调用。
     */
    var searchFinishCallback: ((isEmpty: Boolean) -> Unit)? = null

    /** 书名 (initData 写入)。app 端通过 getter 转发暴露。 */
    var name: String = ""
        private set

    /** 作者 (initData 写入, 已去 authorRegex)。app 端通过 getter 转发暴露。 */
    var author: String = ""
        private set

    /**
     * 章节序号 (章节换源用, 对照原 `ChangeChapterSourceViewModel.chapterIndex`)。
     *
     * 由 [initData] 6 参数重载写入, app 端 ChangeChapterSourceViewModel 转发暴露,
     * ChangeChapterSourceDialog 通过 `viewModel.chapterIndex` 传给
     * `BookHelp.getDurChapter(chapterIndex, chapterTitle, toc)` 定位章节。
     */
    var chapterIndex: Int = 0

    /**
     * 章节标题 (章节换源用, 对照原 `ChangeChapterSourceViewModel.chapterTitle`)。
     *
     * 由 [initData] 6 参数重载写入, app 端 ChangeChapterSourceViewModel 转发暴露,
     * ChangeChapterSourceDialog 用 `viewModel.chapterTitle` 作为标题栏显示。
     */
    var chapterTitle: String = ""

    /** 是否从阅读页进入 (影响 loadBookWordCount 中章节定位逻辑)。 */
    private var fromReadBookActivity = false

    /** 旧书 (loadBookWordCount / getToc 用)。 */
    private var oldBook: Book? = null

    /** 筛选关键词 (screen 写入, searchCallback.searchSuccess 内判断)。 */
    private var screenKey: String = ""

    /** 当前搜索使用的书源列表 (startSearch 时填充)。 */
    private var bookSources = arrayListOf<BookSource>()

    /** 当前搜索使用的书源总数 (app 端 Dialog 显示进度用)。 */
    val totalSourceCount: Int
        get() = bookSources.size

    /** 搜索结果原始列表 (searchCallback.searchSuccess 时 add)。 */
    private val searchBooks = newSynchronizedList(arrayListOf<SearchBook>())

    /** 已加载章节列表的缓存 (loadBookToc 时填充, getToc 命中缓存)。 */
    private val tocMap = newConcurrentMap<String, List<BookChapter>>()

    /** 已加载详情的书籍缓存 (loadBookToc 时填充, Dialog.changeSource 取 book 用)。 */
    val bookMap = newConcurrentMap<String, Book>()

    /** tocMap 累计章节数 (限制 < 30000 防内存爆炸, 对照原逻辑)。 */
    private var tocMapChapterCount = 0

    /** 章节字数文本中的章节序号正则 (对照原 `chapterNumRegex`)。 */
    private val chapterNumRegex = "^\\[(\\d+)]".toRegex()

    /** 排序基准: 分数降序 → 源评分降序 (对照原 `comparatorBase`)。 */
    private val comparatorBase by lazy {
        compareByDescending<SearchBook> { getBookScore(it) }.thenByDescending {
            platform.getSourceScore(
                it.origin
            )
        }
    }

    /** 默认排序: 基准 → originOrder 升序 (对照原 `defaultComparator`)。 */
    private val defaultComparator by lazy {
        comparatorBase.thenBy { it.originOrder }
    }

    /** 字数排序: 基准 → 字数 > 1000 → 章节序号 → 字数 → originOrder (对照原 `wordCountComparator`)。 */
    private val wordCountComparator by lazy {
        comparatorBase.thenByDescending { it.chapterWordCount > 1000 }
            .thenByDescending { getChapterNum(it.chapterWordCountText) }
            .thenByDescending { it.chapterWordCount }.thenBy { it.originOrder }
    }

    /** 当前搜索/刷新任务 (stopSearch 时 cancel)。 */
    private var task: Job? = null

    /** 搜索线程池 (startSearch/refreshList 时初始化, stopSearch/onCleared 时关闭)。 */
    private var searchPool: CoroutineDispatcher? = null

    /**
     * 搜索回调 (对照原 `private var searchCallback: SourceCallback? = null`)。
     *
     * 由 [searchDataFlow] 的 callbackFlow 内部赋值 (订阅时设置, 取消时置 null),
     * search/onCompletion/loadBookInfo/loadBookToc/loadBookWordCount/refresh/screen/
     * disableSource/topSource/bottomSource/del/setBookScore 等处通过 `searchCallback?.xxx` 调用
     * 触发 trySend 把最新 searchBooks 推给下游订阅者。
     */
    private var searchCallback: SourceCallback? = null

    /**
     * 搜索结果 Flow (对照原 `searchDataFlow: Flow<List<SearchBook>>`)。
     *
     * - `callbackFlow` 内创建 [SourceCallback] 接收搜索结果 (searchSuccess / upAdapter),
     *   每 add 一条立即 trySend 当前 searchBooks;
     * - 启动时先按 screenKey 过滤一遍已存在结果 (screen 切换后重新订阅场景);
     * - 若 searchBooks 为空则启动 startSearch;
     * - `awaitClose { searchCallback = null }` 在订阅取消时清理回调;
     * - `map { 排序 }` 在 IO 调度器上对 searchBooks 排序后 emit。
     *
     * app 端 Dialog 用 `viewModel.searchDataFlow.conflate().collect { items = it }` 消费。
     */
    val searchDataFlow: Flow<List<SearchBook>> = callbackFlow {

        searchCallback = object : SourceCallback {

            override fun searchSuccess(searchBook: SearchBook) {
                searchBook.releaseHtmlData()
                when {
                    screenKey.isEmpty() -> searchBooks.add(searchBook)
                    searchBook.name.contains(screenKey) -> searchBooks.add(searchBook)
                    else -> return
                }
                trySend(searchBooks)
            }

            override fun upAdapter() {
                trySend(searchBooks)
            }

        }

        // 订阅时先按当前 screenKey 过滤已存在结果 (对照原 callbackFlow 启动块)
        searchBooks.removeAll {
            (if (platform.changeSourceCheckAuthor) it.author != author
            else false) || !it.name.contains(
                screenKey, ignoreCase = true
            ) || !it.author.contains(screenKey, ignoreCase = true)
        }
        trySend(searchBooks)

        // 若结果为空则启动搜索 (首次进入场景)
        if (searchBooks.isEmpty()) {
            startSearch()
        }

        awaitClose {
            searchCallback = null
        }
    }.map {
        kotlin.runCatching {
            val comparator = if (platform.changeSourceLoadWordCount) {
                wordCountComparator
            } else {
                defaultComparator
            }
            searchBooks.sortedWith(comparator)
        }.onFailure {
            AppLog.put("换源排序出错\n${it.localizedMessage}", it)
        }.getOrDefault(searchBooks)
    }.flowOn(Dispatchers.IO)

    /**
     * 初始化数据 (对照原 `initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean)`)。
     *
     * 原 app 端从 Bundle 解析 name/author, 此处由 app 端解析后通过参数传入 commonMain。
     * author 已在 app 端用 `AppPattern.authorRegex` 去除非法字符。
     *
     * @param name 书名
     * @param author 作者 (已去 authorRegex)
     * @param fromReadBookActivity 是否从阅读页进入
     * @param oldBook 旧书 (loadBookWordCount / getToc 用)
     */
    fun initData(name: String, author: String, fromReadBookActivity: Boolean, oldBook: Book?) {
        this.name = name
        this.author = author
        this.fromReadBookActivity = fromReadBookActivity
        this.oldBook = oldBook
    }

    /**
     * 初始化数据 (章节换源重载, 对照原 `ChangeChapterSourceViewModel.initData` 转发)。
     *
     * 在 4 参数版本基础上额外设置 [chapterIndex] / [chapterTitle], 供章节换源场景使用:
     * - app 端 ChangeChapterSourceDialog 用 `viewModel.chapterTitle` 作标题栏显示;
     * - `BookHelp.getDurChapter(viewModel.chapterIndex, viewModel.chapterTitle, toc)` 用
     *   这两个字段在新源目录中定位对应章节;
     * - [getContent] 用 chapter 取正文供阅读页替换。
     *
     * 内部复用 4 参数 [initData] 设置 name/author/fromReadBookActivity/oldBook,
     * 再额外写入 [chapterIndex] / [chapterTitle], 不破坏原 4 参数签名。
     *
     * @param name 书名
     * @param author 作者 (已去 authorRegex)
     * @param fromReadBookActivity 是否从阅读页进入
     * @param oldBook 旧书 (loadBookWordCount / getToc 用)
     * @param chapterIndex 章节序号 (章节换源定位用)
     * @param chapterTitle 章节标题 (章节换源标题显示用)
     */
    fun initData(
        name: String,
        author: String,
        fromReadBookActivity: Boolean,
        oldBook: Book?,
        chapterIndex: Int,
        chapterTitle: String,
    ) {
        initData(name, author, fromReadBookActivity, oldBook)
        this.chapterIndex = chapterIndex
        this.chapterTitle = chapterTitle
    }

    /**
     * 初始化搜索线程池 (对照原 `initSearchPool`)。
     *
     * 线程数取 `min(threadCount, AppConst.MAX_THREAD)`, 用 [newFixedThreadPoolDispatcher]
     * 创建 [CoroutineDispatcher] 供 search/refreshList 协程调度
     * (KMP 桥接: JVM/Android 端内部走 `Executors.newFixedThreadPool`, 与原实现一致)。
     */
    private fun initSearchPool() {
        searchPool = newFixedThreadPoolDispatcher(min(platform.threadCount, AppConst.MAX_THREAD))
    }

    /**
     * 刷新筛选 (对照原 `refresh(): Boolean`)。
     *
     * 按 screenKey + changeSourceCheckAuthor 过滤 searchBooks, 通知 upAdapter,
     * 返回过滤后是否为空 (空则调用方会触发 startSearch)。
     */
    fun refresh(): Boolean {
        searchBooks.removeAll {
            (if (platform.changeSourceCheckAuthor) it.author != author
            else false) || !it.name.contains(
                screenKey, ignoreCase = true
            ) || !it.author.contains(screenKey, ignoreCase = true)
        }
        searchCallback?.upAdapter()
        return searchBooks.isEmpty()
    }

    /**
     * 启动搜索 (对照原 `startSearch()`)。
     *
     * 1. stopSearch 取消旧任务
     * 2. 清空 searchBooks / bookSources / tocMap / bookMap / tocMapChapterCount
     * 3. 按 searchGroup 取启用书源 (空 group 取全部)
     * 4. initSearchPool 创建线程池
     * 5. search() 并发搜索
     */
    fun startSearch() {
        Coroutine.async(scope) {
            stopSearch()
            if (searchBooks.isNotEmpty()) searchBooks.clear()
            searchCallback?.upAdapter()
            bookSources.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            _changeSourceProgress.value = 0 to ""
            val searchGroup = platform.searchGroup
            if (searchGroup.isBlank()) {
                bookSources.addAll(appDb.bookSourceDao.allEnabled())
            } else {
                val sources = appDb.bookSourceDao.getEnabledByGroup(searchGroup)
                if (sources.isEmpty()) {
                    platform.searchGroup = ""
                    bookSources.addAll(appDb.bookSourceDao.allEnabled())
                } else {
                    bookSources.addAll(sources)
                }
            }
            initSearchPool()
            search()
        }
    }

    /**
     * 启动单源搜索 (对照原 `startSearch(origin: String)`)。
     *
     * 编辑书源后单独搜该源, 不清空 bookSources 但清空 tocMap/bookMap,
     * 移除 searchBooks 中该源的旧结果, 再 search()。
     */
    fun startSearch(origin: String) {
        Coroutine.async(scope) {
            stopSearch()
            bookSources.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            bookSources.add(appDb.bookSourceDao.getBookSource(origin)!!)
            searchBooks.removeIf { it.origin == origin }
            initSearchPool()
            search()
        }
    }

    /**
     * 并发搜索所有 bookSources (对照原 `search()`)。
     *
     * - flow { emit(bookSource) } 串行发射书源;
     * - onStart 推送 searchState=true;
     * - mapParallel(threadCount) 并发执行 search(source), 单源超时 timeLimit;
     * - onEachIndexed 更新 _changeSourceProgress (index+1, sourceName);
     * - onCompletion 推送 searchState=false + 调 searchFinishCallback;
     * - catch 记录 AppLog。
     */
    private fun search() {
        task = scope.launch(searchPool!!) {
            flow {
                bookSources.map {
                    emit(it)
                }
            }.onStart {
                _searchState.value = true
            }.mapParallel(platform.threadCount) {
                try {
                    withTimeout(timeLimit) {
                        search(it)
                    }
                } catch (_: Throwable) {
                    currentCoroutineContext().ensureActive()
                }
                it
            }.onEachIndexed { index, value ->
                _changeSourceProgress.update { _ ->
                    index + 1 to value.bookSourceName
                }
            }.onCompletion {
                ensureActive()
                _searchState.value = false
                searchFinishCallback?.invoke(searchBooks.isEmpty())
            }.catch {
                AppLog.put("换源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    /**
     * 单源搜索 (对照原 `suspend fun search(source: BookSource)`)。
     *
     * 1. 读 4 个 changeSource* 开关;
     * 2. WebBook.getBookListAwait 取搜索结果 (filter 按 name + checkAuthor);
     * 3. 若需 loadInfo/loadToc/loadWordCount 则 loadBookInfo, 否则直接 searchSuccess。
     */
    private suspend fun search(source: BookSource) {
        val checkAuthor = platform.changeSourceCheckAuthor
        val loadInfo = platform.changeSourceLoadInfo
        val loadToc = platform.changeSourceLoadToc
        val loadWordCount = platform.changeSourceLoadWordCount
        val resultBooks = WebBook.getBookListAwait(
            source, name, filter = { fName, fAuthor ->
                fName == name && (!checkAuthor || fAuthor.contains(author))
            }).books
        resultBooks.forEach { searchBook ->
            when {
                loadInfo || loadToc || loadWordCount -> {
                    loadBookInfo(source, searchBook.toBook())
                }

                else -> {
                    searchCallback?.searchSuccess(searchBook)
                }
            }
        }
    }

    /**
     * 加载书籍详情 (对照原 `loadBookInfo`)。
     *
     * - tocUrl 为空则先 getBookInfoAwait 取详情;
     * - 若需 loadToc 或 loadWordCount 则 loadBookToc, 否则 searchSuccess。
     */
    private suspend fun loadBookInfo(source: BookSource, book: Book) {
        if (book.tocUrl.isEmpty()) {
            WebBook.getBookInfoAwait(source, book)
        }
        if (platform.changeSourceLoadToc || platform.changeSourceLoadWordCount) {
            loadBookToc(source, book)
        } else {
            // 从详情页里获取最新章节
            val searchBook = book.toSearchBook()
            searchCallback?.searchSuccess(searchBook)
        }
    }

    /**
     * 加载目录 (对照原 `loadBookToc`)。
     *
     * 1. getChapterListAwait 取章节列表, internString 去重字符串;
     * 2. tocMapChapterCount < 30000 时缓存到 tocMap;
     * 3. bookMap 缓存 book (Dialog.changeSource 用);
     * 4. releaseHtmlData 清 HTML;
     * 5. 若需 loadWordCount 则 loadBookWordCount, 否则 searchSuccess。
     */
    private suspend fun loadBookToc(source: BookSource, book: Book) {
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        for (chapter in chapters) {
            chapter.internString()
        }
        if (tocMapChapterCount < 30000) {
            tocMapChapterCount += chapters.size
            tocMap[book.primaryStr()] = chapters
        }
        bookMap[book.primaryStr()] = book
        book.releaseHtmlData()
        if (platform.changeSourceLoadWordCount) {
            loadBookWordCount(source, book, chapters)
        } else {
            val searchBook = book.toSearchBook()
            searchCallback?.searchSuccess(searchBook)
        }
    }

    /**
     * 加载字数 (对照原 `loadBookWordCount`)。
     *
     * 1. fromReadBookActivity=true 则用 [ChangeBookSourcePlatform.getDurChapter] 定位当前章节,
     *    否则取末章;
     * 2. WebBook.getContentAwait 取正文;
     * 3. [ChangeBookSourcePlatform.processContent] 走正文处理 (替换规则/简繁等);
     * 4. 算字数 + 拼接字数文本 "[index] title\n字数：len";
     * 5. 失败时字数=-1, 文本标注"获取字数失败";
     * 6. 设置 searchBook.chapterWordCountText / chapterWordCount / respondTime, searchSuccess。
     */
    private suspend fun loadBookWordCount(
        source: BookSource, book: Book, chapters: List<BookChapter>
    ) = coroutineScope {
        val chapterIndex = if (fromReadBookActivity) {
            platform.getDurChapter(oldBook!!, chapters)
        } else {
            chapters.lastIndex
        }
        val bookChapter = chapters[chapterIndex]
        var title = bookChapter.title.trim()
        if (title.length > 20) {
            title = title.take(20) + "…"
        }
        val startTime = systemCurrentTimeMillis()
        val pair = try {
            val nextChapterUrl = chapters.getOrNull(chapterIndex + 1)?.url
            var content = WebBook.getContentAwait(source, book, bookChapter, nextChapterUrl, false)
            content = platform.processContent(oldBook!!, bookChapter, content, false).toString()
            val len = content.length
            len to "[${chapterIndex + 1}] ${title}\n字数：${len}"
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            -1 to "[${chapterIndex + 1}] ${title}\n获取字数失败：${t.localizedMessage}"
        }
        val endTime = systemCurrentTimeMillis()
        val searchBook = book.toSearchBook().apply {
            chapterWordCountText = pair.second
            chapterWordCount = pair.first
            respondTime = (endTime - startTime).toInt()
        }
        searchCallback?.searchSuccess(searchBook)
    }

    /**
     * 字数开关变化回调 (对照原 `onLoadWordCountChecked`)。
     *
     * 勾选字数加载时立即 startRefreshList(true) 重新加载无字数书籍。
     */
    fun onLoadWordCountChecked(isChecked: Boolean) {
        if (isChecked) {
            startRefreshList(true)
        }
    }

    /**
     * 刷新列表 (对照原 `startRefreshList`)。
     *
     * 1. stopSearch 取消旧任务;
     * 2. onlyRefreshNoWordCountBook=true: 把无字数书籍移到 searchBookList 重新加载;
     *    false: 全量重新加载;
     * 3. initSearchPool + refreshList()。
     */
    fun startRefreshList(onlyRefreshNoWordCountBook: Boolean = false) {
        Coroutine.async(scope) {
            stopSearch()
            val searchBookList = arrayListOf<SearchBook>()
            if (onlyRefreshNoWordCountBook) {
                searchBooks.filterTo(searchBookList) {
                    it.chapterWordCountText == null
                }
                searchBooks.removeIf { it.chapterWordCountText == null }
            } else {
                searchBookList.addAll(searchBooks)
                searchBooks.clear()
            }
            searchCallback?.upAdapter()
            initSearchPool()
            refreshList(searchBookList)
        }
    }

    /**
     * 并发刷新 searchBookList (对照原 `refreshList()`)。
     *
     * 与 search() 类似, 但每个 searchBook 用 origin 反查 BookSource 后 loadBookInfo。
     */
    private fun refreshList(searchBookList: List<SearchBook>) {
        task = scope.launch(searchPool!!) {
            flow {
                for (searchBook in searchBookList) {
                    emit(searchBook)
                }
            }.onStart {
                _searchState.value = true
            }.mapParallelSafe(platform.threadCount, bookSources.size) {
                val source = appDb.bookSourceDao.getBookSource(it.origin)!!
                withTimeout(timeLimit) {
                    loadBookInfo(source, it.toBook())
                }
            }.onCompletion {
                _searchState.value = false
            }.catch {
                AppLog.put("换源刷新列表出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    /**
     * 筛选 (对照原 `screen`)。
     *
     * 更新 screenKey, 按 screenKey + changeSourceCheckAuthor 过滤 searchBooks, upAdapter。
     */
    fun screen(key: String?) {
        screenKey = key?.trim() ?: ""
        Coroutine.async(scope) {
            searchBooks.removeAll {
                (if (platform.changeSourceCheckAuthor) it.author != author
                else false) || !it.name.contains(
                    screenKey, ignoreCase = true
                ) || !it.author.contains(screenKey, ignoreCase = true)
            }
            searchCallback?.upAdapter()
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
     * 获取目录 (带回调, 对照原 `getToc(book, onSuccess, onError): Coroutine`)。
     *
     * 1. tocMap 命中则直接返回;
     * 2. 否则 WebBook 取目录, 缓存到 tocMap;
     * 3. onSuccess / onError 回调。
     *
     * 返回 [Coroutine] 供调用方 cancel (Dialog.waitDialog.onCancelListener)。
     */
    fun getToc(
        book: Book,
        onSuccess: (toc: List<BookChapter>, source: BookSource) -> Unit,
        onError: (e: Throwable) -> Unit
    ): Coroutine<Pair<List<BookChapter>, BookSource>> {
        return Coroutine.async(scope) {
            val toc = tocMap[book.primaryStr()]
            if (toc != null) {
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                return@async Pair(toc, source!!)
            }
            val result = getToc(book).getOrThrow()
            tocMap[book.primaryStr()] = result.first
            result
        }.onSuccess {
            onSuccess.invoke(it.first, it.second)
        }.onError {
            onError.invoke(it)
        }
    }

    /**
     * 获取目录 (suspend, 对照原 `suspend fun getToc(book: Book): Result<...>`)。
     *
     * 1. 取 BookSource (不存在则抛 NoStackTraceException);
     * 2. tocUrl 为空则 getBookInfoAwait 取详情;
     * 3. getChapterListAwait 取章节列表;
     * 4. 返回 Pair(toc, source)。
     */
    suspend fun getToc(book: Book): Result<Pair<List<BookChapter>, BookSource>> {
        return kotlin.runCatching {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw NoStackTraceException("书源不存在")
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
            }
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
            Pair(toc, source)
        }
    }

    /**
     * 获取正文 (对照原 `ChangeChapterSourceViewModel.getContent`)。
     *
     * 章节换源场景: 选中源 + 章节后, 取该源章节正文供阅读页替换
     * (ChangeChapterSourceDialog.clickChapter 调 viewModel.getContent 后
     * callBack.replaceContent(content) 替换当前阅读页正文)。
     *
     * 1. [appDb.bookSourceDao.getBookSource] 取源 (不存在则抛 [NoStackTraceException]);
     * 2. [WebBook.getContentAwait] 取正文 (reload=false, 不强制重新下载);
     * 3. [Coroutine.async] 的 onSuccess 回调 [success], onError 回调 [error]。
     *
     * @param book 书籍 (用 origin 反查 BookSource)
     * @param chapter 章节 (getContentAwait 用)
     * @param nextChapterUrl 下一章 URL (getContentAwait 用, 章节末尾内容去重)
     * @param success 成功回调 (正文内容)
     * @param error 失败回调 (错误消息)
     */
    fun getContent(
        book: Book,
        chapter: BookChapter,
        nextChapterUrl: String?,
        success: (content: String) -> Unit,
        error: (msg: String) -> Unit,
    ) {
        Coroutine.async(scope) {
            val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw NoStackTraceException("书源不存在")
            WebBook.getContentAwait(bookSource, book, chapter, nextChapterUrl, false)
        }.onSuccess {
            success.invoke(it)
        }.onError {
            error.invoke(it.localizedMessage ?: "获取正文出错")
        }
    }

    /**
     * 禁用书源 (对照原 `disableSource`)。
     *
     * source.enabled = false + dao.update + 从 searchBooks 移除 + upAdapter。
     */
    fun disableSource(searchBook: SearchBook) {
        Coroutine.async(scope) {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                source.enabled = false
                appDb.bookSourceDao.update(source)
            }
            searchBooks.remove(searchBook)
            searchCallback?.upAdapter()
        }
    }

    /**
     * 置顶书源 (对照原 `topSource`)。
     *
     * source.customOrder = minOrder - 1 + dao.update + upAdapter。
     */
    fun topSource(searchBook: SearchBook) {
        Coroutine.async(scope) {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val minOrder = appDb.bookSourceDao.minOrder() - 1
                source.customOrder = minOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
            }
            searchCallback?.upAdapter()
        }
    }

    /**
     * 置底书源 (对照原 `bottomSource`)。
     *
     * source.customOrder = maxOrder + 1 + dao.update + upAdapter。
     */
    fun bottomSource(searchBook: SearchBook) {
        Coroutine.async(scope) {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val maxOrder = appDb.bookSourceDao.maxOrder() + 1
                source.customOrder = maxOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
            }
            searchCallback?.upAdapter()
        }
    }

    /**
     * 删除书源 (对照原 `del`)。
     *
     * SourceHelp.deleteBookSource + 从 searchBooks 移除 + upAdapter。
     */
    fun del(searchBook: SearchBook) {
        Coroutine.async(scope) {
            SourceHelp.deleteBookSource(searchBook.origin)
        }
        searchBooks.remove(searchBook)
        searchCallback?.upAdapter()
    }

    /**
     * 自动换源 (对照原 `autoChangeSource`)。
     *
     * 遍历 searchBooks 找 type 匹配的源, 调 getToc 取目录, 首个成功则 onSuccess;
     * 全部失败则 onError + toastOnUi。
     */
    fun autoChangeSource(
        bookType: Int?, onSuccess: (book: Book, toc: List<BookChapter>, source: BookSource) -> Unit
    ) {
        Coroutine.async(scope) {
            searchBooks.forEach {
                if (it.type == bookType) {
                    val book = it.toBook()
                    val result = getToc(book).getOrNull()
                    if (result != null) {
                        return@async Triple(book, result.first, result.second)
                    }
                }
            }
            throw NoStackTraceException("没有有效源")
        }.onSuccess {
            onSuccess.invoke(it.first, it.second, it.third)
        }.onError {
            platform.toastOnUi("自动换源失败\n${it.localizedMessage}")
        }
    }

    /**
     * 设置书源评分 (对照原 `setBookScore`)。
     *
     * 通过 [ChangeBookSourcePlatform.setBookScore] 持久化 + upAdapter。
     */
    fun setBookScore(searchBook: SearchBook, score: Int) {
        Coroutine.async(scope) {
            platform.setBookScore(searchBook.origin, searchBook.name, searchBook.author, score)
            searchCallback?.upAdapter()
        }
    }

    /**
     * 获取书源评分 (对照原 `getBookScore`)。
     *
     * 通过 [ChangeBookSourcePlatform.getBookScore] 读取。
     */
    fun getBookScore(searchBook: SearchBook): Int {
        return platform.getBookScore(searchBook.origin, searchBook.name, searchBook.author)
    }

    /**
     * 从字数文本提取章节序号 (对照原 `getChapterNum`)。
     *
     * 匹配 `^\[(\d+)]`, 取 group(1) 转 Int, 失败返回 -1。
     */
    private fun getChapterNum(wordCountText: String?): Int {
        wordCountText ?: return -1
        return chapterNumRegex.find(wordCountText)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /**
     * 释放资源 (对照原 `onCleared`)。
     *
     * app 端 ViewModel.onCleared 调用, 关闭 searchPool。
     */
    fun onCleared() {
        searchPool?.closeIfCloseable()
    }

    /**
     * 搜索回调接口 (对照原 `SourceCallback`)。
     *
     * 由 searchDataFlow 内部实现, searchSuccess/add + upAdapter 触发 trySend。
     */
    interface SourceCallback {

        fun searchSuccess(searchBook: SearchBook)

        fun upAdapter()

    }

}

/**
 * 换源平台专属依赖聚合接口 (KMP 注入点)。
 *
 * 用一个聚合接口封装所有平台专属依赖, 避免在 [ChangeBookSourceViewModelShared] 构造函数
 * 列出 7+ 个 lambda 参数 (违反"避免超多继承与参数传递"原则)。
 *
 * 各端实现:
 * - **Android**: `AndroidChangeBookSourcePlatform` 包装 `AppConfig` / `ContentProcessor` /
 *   `BookHelp` / `SourceConfig` / `context.toastOnUi`;
 * - **桌面**: `DesktopChangeBookSourcePlatform` 简化实现 (评分返回 0 / getDurChapter 取末章 /
 *   processContent 直接返回 content / toastOnUi 用 println 替代 / 4 个开关 + threadCount +
 *   searchGroup 从 PreferenceProviders 读)。
 *
 * # 为何不扩展既有 Provider 接口
 *
 * - `AppConfigAccessor` 接口暂未包含 4 个 changeSource* 字段, 扩散接口需改 app 端
 *   `AppConfigAccessorImpl` + 桌面端 `DesktopAppConfigAccessor` + 接口本身, 改动面较大;
 * - `ContentProcessorAccessor` / `BookHelpAccessor` 同样缺 getContent / getDurChapter;
 * - `SourceConfig` 已下沉 commonMain (走 PreferenceProviders), 但评分方法仍通过
 *   [ChangeBookSourcePlatform] 注入以聚合 getDurChapter/processContent 等其他平台差异;
 * - 用聚合接口注入只改 ChangeBookSourceViewModel 一处, 不扩散既有 accessor, 符合
 *   "避免超多 Provider 接口" 原则。
 */
interface ChangeBookSourcePlatform {

    // ---- AppConfig 相关 ----

    /** 并发线程数 (对照 `AppConfig.threadCount`)。 */
    val threadCount: Int

    /** 搜索分组 (对照 `AppConfig.searchGroup`), var 因 startSearch 写回空串。 */
    var searchGroup: String

    /**
     * 换源是否校验作者 (对照 `AppConfig.changeSourceCheckAuthor`)。
     *
     * var 因 UI 切换开关时需写回持久化 (app 端写 AppConfig, 桌面端写 PreferenceProviders)。
     */
    var changeSourceCheckAuthor: Boolean

    /**
     * 换源是否加载详情 (对照 `AppConfig.changeSourceLoadInfo`)。
     *
     * var 因 UI 切换开关时需写回持久化。
     */
    var changeSourceLoadInfo: Boolean

    /**
     * 换源是否加载目录 (对照 `AppConfig.changeSourceLoadToc`)。
     *
     * var 因 UI 切换开关时需写回持久化。
     */
    var changeSourceLoadToc: Boolean

    /**
     * 换源是否加载字数 (对照 `AppConfig.changeSourceLoadWordCount`)。
     *
     * var 因 UI 切换开关时需写回持久化。
     */
    var changeSourceLoadWordCount: Boolean

    // ---- BookHelp 相关 ----

    /**
     * 根据旧书定位新章节列表中的当前章节索引 (对照 `BookHelp.getDurChapter(oldBook, chapters)`)。
     *
     * app 端用 `BookHelp.getDurChapter(oldBook, chapters)` (内部走章节名相似度匹配);
     * 桌面端简化取 `chapters.lastIndex` (与 fromReadBookActivity=false 路径一致)。
     */
    fun getDurChapter(oldBook: Book, chapters: List<BookChapter>): Int

    // ---- ContentProcessor 相关 ----

    /**
     * 处理正文 (对照 `ContentProcessor.get(oldBook).getContent(book, chapter, content, ...)`)。
     *
     * app 端走完整 ContentProcessor (替换规则/简繁/重排段);
     * 桌面端简化直接返回 content (无替换规则处理)。
     *
     * @param oldBook 旧书 (ContentProcessor 按 book 取替换规则)
     * @param chapter 当前章节 (用于去除重复标题)
     * @param content 原始正文
     * @param includeTitle 是否包含标题 (传 false, 与原调用一致)
     * @return 处理后的正文
     */
    fun processContent(
        oldBook: Book, chapter: BookChapter, content: String, includeTitle: Boolean
    ): CharSequence

    // ---- SourceConfig 评分相关 ----

    /**
     * 设置书源评分 (对照 `SourceConfig.setBookScore(origin, name, author, score)`)。
     *
     * app 端走 SourceConfig 持久化到 SharedPreferences;
     * 桌面端 no-op (评分功能未实现)。
     */
    fun setBookScore(origin: String, name: String, author: String, score: Int)

    /**
     * 获取书源评分 (对照 `SourceConfig.getBookScore(origin, name, author)`)。
     *
     * app 端走 SourceConfig 读 SharedPreferences;
     * 桌面端返回 0 (评分功能未实现)。
     */
    fun getBookScore(origin: String, name: String, author: String): Int

    /**
     * 获取源评分 (对照 `SourceConfig.getSourceScore(origin)`)。
     *
     * app 端走 SourceConfig;
     * 桌面端返回 0。
     */
    fun getSourceScore(origin: String): Int

    // ---- Toast 相关 ----

    /**
     * 显示 Toast (对照 `context.toastOnUi(msg)`)。
     *
     * app 端走 Android Toast;
     * 桌面端用 println 替代 (或后续接桌面通知系统)。
     */
    fun toastOnUi(msg: String)
}
