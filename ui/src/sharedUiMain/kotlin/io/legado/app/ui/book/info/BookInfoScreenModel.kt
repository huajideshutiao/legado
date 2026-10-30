package io.legado.app.ui.book.info

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookChapterLoader
import io.legado.app.help.book.addType
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.book.updateTo
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.root.ImportStageOwner
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.screenModelScope
import io.legado.app.ui.widget.dialog.WaitDialogHandle
import io.legado.app.utils.ConvertUtils
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.error_get_book_info
import legado.ui.generated.resources.error_get_chapter_list
import legado.ui.generated.resources.error_no_source
import legado.ui.generated.resources.lasted_show
import org.jetbrains.compose.resources.getString

/**
 * 书籍详情页 shared ScreenModel: 托管 [BookInfoUiState]。
 *
 * app 端 [BookInfoActivity] 观察 ViewModel LiveData 后通过 [dispatch] 推入状态;
 * 桌面/iOS 端可直接构造本类复用。派生状态 (isLandscape/useDevFeat/isDarkTheme/menuState)
 * 由宿主在 Composition 时经 [BookInfoUiState.copy] 覆盖, 不走 dispatch。
 */
@OptIn(ExperimentalAtomicApi::class)
class BookInfoScreenModel(initialBook: Book? = null) : ScreenModel {

    private val appDb get() = AppDbProviders.get()

    // 自管 scope (对照 TocScreenModel, 由 ScreenModelStore 在 onCleared 取消)
    private val scope = screenModelScope("书籍详情")

    // 页面级请求的执行 scope = 本 Model 自管 scope: 真页退出 (ScreenModelStore.onCleared) 即撤,
    // 不把页面状态塞全局能力面, 也不为保留囚住旧 UI 对象。
    val pageScope: CoroutineScope get() = scope

    // 待交阅读导航: 每请求一条 (id → 路由), 真实分发后只消费本条, 不用序号补单槽
    private val _pendingReaderNavs = MutableStateFlow<Map<Long, AppRoute>>(emptyMap())
    val pendingReaderNavs: StateFlow<Map<Long, AppRoute>> = _pendingReaderNavs.asStateFlow()
    private val readerNavSeq = atomic(0L)

    /**
     * 交当前有效组合打开阅读路由 (不经旧成功闭包直接 push)。
     *
     * 编号在 [update] 之外发: `update` 的 lambda 可能被重试 (并发的 setter 竞争), 在里面自增会
     * 重复取号/撞号; atomicfu 的发号本身是原子的, 放进 lambda 也不会丢, 但纯 lambda 才可重试。
     */
    fun postReaderNav(route: AppRoute) {
        val id = readerNavSeq.incrementAndGet()
        _pendingReaderNavs.update { it + (id to route) }
    }

    fun consumeReaderNav(id: Long) {
        _pendingReaderNavs.update { it - id }
    }

    private val _state = MutableStateFlow(
        BookInfoUiState(
            book = initialBook,
            bookTick = 0,
            coverTick = 0,
            refreshing = false,
            inBookshelf = initialBook?.let { !it.isNotShelf } ?: false,
            groupName = "",
            // 目录文案初值 = 已入库的阅读进度 (零查询即知), 不在页面落定前显示假"加载中"
            tocText = initialBook?.durChapterTitle?.takeIf { it.isNotBlank() },
            // 最新章节需套本地化模板 (getString 是挂起函数), 首帧留空 —— 对照原版该控件首帧无文本
            lastedTitle = "",
            wordCountText = null,
            isLandscape = false,
            useDevFeat = false,
            isDarkTheme = false,
            menuState = BookInfoMenuState(
                // 与路由层同一口径 (origin 判定): 路由每次组合都会重算并覆盖本初值,
                // 两处语义不一就会出现"模型说本地书、菜单按非本地渲染"的错位
                isLocal = initialBook?.origin == BookType.localTag,
                isWebDav = initialBook?.origin?.startsWith(BookType.webDavTag) == true,
                hasSource = false,
                sourceHasLogin = false,
                sourceHasReviewRule = false,
                canUpdate = initialBook?.canUpdate ?: true,
                isLocalTxt = initialBook?.isLocalTxt == true,
                splitLongChapter = initialBook?.config?.splitLongChapter ?: false,
                bookUrl = initialBook?.bookUrl,
                tocUrl = initialBook?.tocUrl,
            ),
        )
    )
    val state: StateFlow<BookInfoUiState> = _state.asStateFlow()

    // webFile 等待框: 按归属句柄逐条保存 (原 app 端 waitDialogData 的显隐 + 归属)。
    // 句柄由能力面 (等待方) 创建并经 onWaitDialog 传入; 两条下载/导入交错时
    // 各自的框互不覆盖, 旧流程收框只动它自己的那个 handle。
    private val _waits = MutableStateFlow<Map<WaitDialogHandle, Boolean>>(emptyMap())
    val waits: StateFlow<Map<WaitDialogHandle, Boolean>> = _waits.asStateFlow()

    /**
     * 导入链用户选择阶段 (下载项候选 / 压缩包条目 / 不支持格式确认)。
     *
     * 随模型存活: 宿重建后新组合读到同一阶段就重显, 请求去留不靠"框还在不在"推断。
     * 原先的四端各自弹原生框并且有一条 actionLive 单向通道 (replay=0) 把"去设置目录"
     * 发出去就算完 —— 框一关或没收集者时请求从此没人管, 那条通道已整体删除,
     * 目录需求改由调用方挂起等真回执 (见 [io.legado.app.ui.root.ensureBookSaveDirSelected])。
     */
    val importStages = ImportStageOwner()

    fun upWaitDialog(show: Boolean, handle: WaitDialogHandle) {
        _waits.update { current ->
            if (show) current + (handle to true)
            // 关闭只收确切 handle 的框: 不是本 handle 的在显框一概不动
            else if (current.containsKey(handle)) current - handle else current
        }
    }

    /**
     * 最近一次解析到的目录（对照 app 端 `BookInfoViewModel.chapterListData`）。
     * 未加书架的书不落库，跳目录页/阅读页只能靠这份内存章节表。
     */
    var loadedChapterList: List<BookChapter>? = null
        private set

    /**
     * 落地加载是否已开工。放在 ScreenModel 而不是 remember: 模型随路由 entry 存活
     * (ScreenModelStore.retain), 从阅读页/目录页/编辑页返回本页时组合体重建而模型不重建 ——
     * 对照原版 `BookInfoViewModel.initData` 的 `curBook != null → return`, 不重复查库/回源,
     * 也不再二次执行 rss 书的 tocUrl 补位。
     */
    var bootStarted: Boolean = false

    /**
     * 当前书源 (对照 app 端 `BookInfoViewModel.curBookSource`), 供菜单/登录/搜索限定当前书源。
     *
     * 随模型跨宿主重建保留: 落地加载只在首次开工时查一次源, 源若只存在组合里"重建后再也拿不回来",
     * 菜单与登录会误判"无当前书源"。所有换源入口 (落地加载、书源编辑回执、整书换源回执)
     * 都写这一份。读它即订阅重组 (Compose 状态)。
     */
    private val bookSourceState =
        androidx.compose.runtime.mutableStateOf<BookSource?>(null)
    var bookSource: BookSource?
        get() = bookSourceState.value
        set(value) {
            bookSourceState.value = value
        }

    /**
     * 当前书籍展示版本 (每次 [BookInfoUiEvent.ShowBook] 递增)。
     *
     * 落地加载的多条链工作线程不同, 而字数链是"先算后交", 算出值的线程与交回值的线程可能不同;
     * 用原子量保证"递增与读取"看到的是同一个先后顺序。
     */
    private val bookVersion = AtomicInt(0)

    /**
     * 供并行链开工时记版: 与目录链并行推进的字数计算结果上屏前要按此比对
     * (目录链完成时已换上刷新后的书, 再回填刷新前的字数会把新的覆盖掉),
     * 陈旧结果直接丢弃。
     */
    fun currentBookVersion(): Int = bookVersion.load()

    fun dispatch(event: BookInfoUiEvent) {
        when (event) {
            // 只置刷新标志: 不再把 tocText 清成 null —— 同一个 null 曾被界面同时当成
            // "未加载完"与"刷新中", 导致每次刷新整页变"加载中"
            BookInfoUiEvent.Refresh -> _state.update { it.copy(refreshing = true) }
            // 书籍数据更新 (bookData observe 触发)。**不**顺带 bump coverTick:
            // 封面重载只由真换了封面驱动 (BumpCoverTick / getDisplayCover 变化后 remember 重启),
            // 否则每次刷新都销毁封面子树 → 首帧退回默认封面
            is BookInfoUiEvent.ShowBook -> {
                bookVersion.incrementAndFetch()
                _state.update {
                    it.copy(
                        book = event.book,
                        bookTick = it.bookTick + 1,
                        lastedTitle = event.lastedTitle,
                        // refreshing 不在这里复位: 收口在 [launchRefreshing] 的 finally,
                        // 否则刷新的两步 (ShowBook/UpdateToc) 各自复位一次, 只是重复
                    )
                }
            }

            is BookInfoUiEvent.UpdateToc -> _state.update {
                it.copy(
                    tocText = event.tocText,
                    lastedTitle = event.lastedTitle ?: it.lastedTitle,
                )
            }

            is BookInfoUiEvent.UpdateBookshelf -> _state.update {
                it.copy(inBookshelf = event.inBookshelf)
            }

            is BookInfoUiEvent.UpdateGroup -> _state.update {
                it.copy(groupName = event.groupName)
            }

            is BookInfoUiEvent.UpdateWordCount -> _state.update { cur ->
                if (event.bookVersion != bookVersion.load()) cur
                else cur.copy(wordCountText = event.text)
            }

            BookInfoUiEvent.RefreshDone -> _state.update {
                if (it.refreshing) it.copy(refreshing = false) else it
            }

            BookInfoUiEvent.BumpBookTick -> _state.update {
                it.copy(bookTick = it.bookTick + 1)
            }

            BookInfoUiEvent.BumpCoverTick -> _state.update {
                it.copy(coverTick = it.coverTick + 1)
            }
        }
    }

    /**
     * 刷新期协程: 统一挂 [BookInfoUiEvent.Refresh] 开工、无论成功/异常/取消都复位刷新标志。
     *
     * 叠跑按“后一次取代前一次”处理: 起新协程前先取消在飞那次, 被取代那次**不**派发
     * [BookInfoUiEvent.RefreshDone] (它的 finally 里 isActive 已为 false)。refreshing 是单一
     * 布尔、没有序号可对, 让被取代者照常收尾就会先把后一次的转圈提前掐掉。
     *
     * 不加这一层的话, 协程体开头 [PlatformCapabilityProviders.get] 一类在 try 之外的调用报错,
     * 或协程在 ShowBook 与 UpdateToc 之间被取消, 下拉指示器会永久转圈。
     */
    private var refreshingJob: Job? = null

    private fun launchRefreshing(block: suspend () -> Unit) {
        refreshingJob?.cancel()
        dispatch(BookInfoUiEvent.Refresh)
        refreshingJob = scope.launch(IoDispatcher) {
            try {
                block()
            } finally {
                // 只让“仍在跑的那一次”收尾
                if (isActive) dispatch(BookInfoUiEvent.RefreshDone)
            }
        }
    }

    /**
     * 刷新书籍信息 + 目录 (对照 app 端 BookInfoViewModel.refreshBook + BaseReadViewModel.loadBookInfo)。
     * [bookSource] 由调用方查好传入; 无论成功失败都会 dispatch UpdateToc 解除加载中状态。
     */
    fun refresh(
        book: Book,
        bookSource: BookSource?,
        errorLoadToc: String,
        canReName: Boolean = true,
        runPreUpdateJs: Boolean = true,
        isSearchBook: Boolean = false,
    ) {
        launchRefreshing {
            // 对照 app 端 refreshBook 前置: 本地非漫画书拉 WebDav 远端更新, 其余同步书源名
            if (book.isLocal && !book.isImage) {
                val capabilities = PlatformCapabilityProviders.get()
                try {
                    capabilities.refreshWebDavBook(book)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 对照 app 端 refreshBook.onError: 远端已删则退回本地书
                    if (e is ObjectNotFoundException) {
                        book.origin = BookType.localTag
                    } else {
                        AppLog.put("下载远程书籍<${book.name}>失败", e)
                    }
                }
            } else {
                bookSource?.let {
                    if (book.originName != it.bookSourceName) book.originName = it.bookSourceName
                }
            }
            val toc = try {
                loadBookInfo(book, bookSource, canReName, runPreUpdateJs, isSearchBook)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 内层 loadBookInfo 的 catch 体本身含挂起调用 (getString/toast), scope 已取消时
                // 会二次抛取消并落到这里, 补查协程状态避免把同一次取消记成第二条错误日志
                currentCoroutineContext().ensureActive()
                AppLog.put("获取书籍信息失败\n${e.message}", e)
                Toasters.get().toast(getString(Res.string.error_get_book_info))
                emptyList()
            }
            // 对照 app 端 showBook + upLoading(false, chapterList)
            upShowBook(book, toc, errorLoadToc)
        }
    }

    /**
     * 仅拉目录 (对照 app 端 BaseReadViewModel.upBook 中 tocUrl 非空时的 loadChapterList 分支)。
     */
    fun loadToc(
        book: Book,
        bookSource: BookSource?,
        errorLoadToc: String,
        runPreUpdateJs: Boolean = true,
    ) {
        launchRefreshing {
            val toc = loadChapterList(book, bookSource, runPreUpdateJs)
            upShowBook(book, toc, errorLoadToc)
        }
    }

    /** 最新章节文案按 book 现值构造 (对照 Activity showBook/upLoading 每次读 curBook.latestChapterTitle)。 */
    suspend fun lastedTitleOf(book: Book): String =
        getString(Res.string.lasted_show, book.latestChapterTitle ?: "")

    /**
     * 字数文案 (对照 Activity upKinds 字数段): book.wordCount + 本地书文件大小, 逗号拼接。
     * 详情刷新会原地改写 book.wordCount, 未在架书不落库无 DB 观察者,
     * 需在每次 showBook 后重算 (对照 archive: bookData 观察 → showBook → upKinds)。
     */
    suspend fun wordCountTextOf(book: Book): String? {
        val wordCounts = arrayListOf<String>()
        book.wordCount?.takeIf { it.isNotBlank() }?.let { wordCounts.add(it) }
        if (book.isLocal) {
            // 能力面在块外取: try 只兜"读文件大小失败", 不该顺带吞掉 registry 未注册与协程取消
            val capabilities = PlatformCapabilityProviders.get()
            val size = try {
                if (book.bookUrl.startsWith("http", true) ||
                    book.bookUrl.startsWith("dav", true)
                ) 0L
                else capabilities.localBookFileSize(book.bookUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0L
            }
            if (size > 0) wordCounts.add(ConvertUtils.formatFileSize(size))
        }
        return when {
            wordCounts.isNotEmpty() -> wordCounts.joinToString(",")
            book.isLocal -> ""
            else -> null
        }
    }

    /** 加载完成后回填书籍与目录文案 (对照 Activity showBook + upLoading(false, chapterList))。 */
    private suspend fun upShowBook(book: Book, toc: List<BookChapter>, errorLoadToc: String) {
        if (toc.isNotEmpty()) loadedChapterList = toc
        val lasted = lastedTitleOf(book)
        dispatch(BookInfoUiEvent.ShowBook(book, lasted))
        dispatch(
            BookInfoUiEvent.UpdateToc(
                tocText = if (toc.isEmpty()) errorLoadToc else book.durChapterTitle.orEmpty(),
                lastedTitle = if (toc.isEmpty()) null else lasted,
            )
        )
        dispatch(BookInfoUiEvent.UpdateWordCount(wordCountTextOf(book), currentBookVersion()))
    }

    /** 对照 app 端 BaseReadViewModel.loadBookInfo。 */
    private suspend fun loadBookInfo(
        book: Book,
        bookSource: BookSource?,
        canReName: Boolean,
        runPreUpdateJs: Boolean,
        isSearchBook: Boolean,
    ): List<BookChapter> {
        if (book.isLocal) {
            val tmp = book.copy()
            FileBook.upBookInfo(book)
            return if (tmp.tocUrl != book.tocUrl || book.totalChapterNum == 0) {
                loadChapterList(book, bookSource, runPreUpdateJs)
            } else {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            }
        }
        val source = bookSource ?: let {
            Toasters.get().toast(getString(Res.string.error_no_source))
            return emptyList()
        }
        return try {
            WebBook.getBookInfoAwait(source, book, canReName)
            if (isSearchBook) {
                val dbBook = appDb.bookDao.getBook(book.bookUrl)
                    ?: appDb.bookDao.getBook(book.name, book.author)
                // 搜索来源的书加载详情后书名可能变化, 同源则并回书架那本, 异源则标记不在书架
                // (对照 app 端 loadBookInfo, 上游 #3652 #4619 #3149)
                if (dbBook != null && dbBook.origin == book.origin) {
                    dbBook.updateTo(book)
                    dispatch(BookInfoUiEvent.UpdateBookshelf(true))
                } else {
                    book.addType(BookType.notShelf)
                    dispatch(BookInfoUiEvent.UpdateBookshelf(false))
                }
            }
            if (_state.value.inBookshelf) saveBook(book)
            // app 端 webFile 走 loadWebFile 下载导入 (平台专属), shared 只读已入库目录
            if (book.isWebFile) {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            } else {
                loadChapterList(book, source, runPreUpdateJs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 退出详情页 (onCleared → scope.cancel) 时 WebBook/JS 链路可能把取消换壳成普通异常,
            // 类型判断拦不住, 补查协程状态: 已取消按取消路径抛出, 不记"获取书籍信息失败"假错误
            currentCoroutineContext().ensureActive()
            AppLog.put("获取书籍信息失败\n${e.message}", e)
            Toasters.get().toast(getString(Res.string.error_get_book_info))
            emptyList()
        }
    }

    /** 对照 app 端 BaseReadViewModel.loadChapterList。 */
    private suspend fun loadChapterList(
        book: Book,
        bookSource: BookSource?,
        runPreUpdateJs: Boolean,
    ): List<BookChapter> {
        if (!book.isLocal && bookSource == null) {
            Toasters.get().toast(getString(Res.string.error_no_source))
            return emptyList()
        }
        return try {
            BookChapterLoader.fetchFromSource(book, bookSource, runPreUpdateJs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            Toasters.get().toast(getString(Res.string.error_get_chapter_list))
            emptyList()
        }
    }

    /** 对照 app 端 Book.save() (BookExtensions), shared 无该扩展。 */
    private suspend fun saveBook(book: Book) {
        book.removeType(BookType.notShelf)
        if (appDb.bookDao.has(book.bookUrl)) {
            appDb.bookDao.update(book)
        } else {
            appDb.bookDao.insert(book)
        }
    }

    override fun onCleared() {
        // 真页退出: 撤本页全部在途请求 (下载/导入/解包) 与待交结果
        scope.cancel()
        _pendingReaderNavs.value = emptyMap()
    }
}

sealed interface BookInfoUiEvent {
    /**
     * 开始刷新: 只置刷新标志 (驱动下拉指示器), **不清**已有目录文案。
     *
     * 旧实现把 tocText 置 null, 同一个 null 同时被当成"未加载完"与"刷新中",
     * 每次刷新整页变“加载中”。
     */
    object Refresh : BookInfoUiEvent

    /** 刷新流程收尾 (成功/失败/取消均发): 只复位刷新标志, 不动文案 */
    object RefreshDone : BookInfoUiEvent

    /** 书籍数据更新 (bookData observe 触发); 不驱动封面重载 */
    data class ShowBook(val book: Book, val lastedTitle: String) : BookInfoUiEvent

    /** 目录加载状态更新 (chapterListData observe / upLoading 触发) */
    data class UpdateToc(val tocText: String?, val lastedTitle: String?) : BookInfoUiEvent

    /** 书架状态更新 */
    data class UpdateBookshelf(val inBookshelf: Boolean) : BookInfoUiEvent

    /** 分组名更新 */
    data class UpdateGroup(val groupName: String) : BookInfoUiEvent

    /**
     * 字数信息更新。
     *
     * [bookVersion] 为计算开工时的 [currentBookVersion] 快照: 展示中的书已换代 (回源/换源/刷新回写了新书)
     * 时丢弃该陈旧结果。
     */
    data class UpdateWordCount(val text: String?, val bookVersion: Int) : BookInfoUiEvent

    /** book 原地可变后驱动重组 (toggleCanUpdate / toggleSplitLongChapter) */
    object BumpBookTick : BookInfoUiEvent

    /** 封面变更后驱动重载 (coverChangeTo) */
    object BumpCoverTick : BookInfoUiEvent
}
