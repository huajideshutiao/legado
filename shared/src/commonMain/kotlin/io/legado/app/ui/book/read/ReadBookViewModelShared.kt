package io.legado.app.ui.book.read

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.model.ReadBookShared
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.ui.book.read.page.PageDelegateShared
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.SimpleChapterLayout
import io.legado.app.ui.book.read.page.provider.SimpleTextMeasurer
import io.legado.app.ui.book.searchContent.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * KMP 版阅读 ViewModel：用 Compose 状态流替代 app 端 `ReadBookActivity` 持有的
 * `ReadBook` + `TextPageFactory` + `ChapterProvider` 编排链路。
 *
 * 与 app 端 `ReadBook` 单例 / `TextPageFactory` 的对应：
 * - [curTextPage] / [prevTextPage] / [nextTextPage] 对应 app 端
 *   `pageFactory.curPage/prevPage/nextPage`，KMP 版改为 `StateFlow<TextPage?>` 适配 Compose 重组。
 * - [loadChapter] / [nextPage] / [prevPage] 对应 app 端 `ReadBook.loadContent` / `moveToNextPage` /
 *   `moveToPrevPage`。KP2-D 起 [loadChapter] 已接入 [SimpleChapterLayout] 真实排版，
 *   [nextPage] / [prevPage] 仍为简化状态切换，待 actual 平台补全翻页动画。
 * - 用 [AppDbProviders.get].bookChapterDao 读章节列表，与 app 端 `appDb.bookChapterDao` 等价。
 * - 用 [BookStorageProviders.get].getContent 读本地章节缓存正文，与 app 端
 *   `BookHelp.getContent` 等价；桌面端需在 Main.kt 注册 `JvmBookStorage`。
 *
 * 持有 [pageDelegate] 引用：[PageDelegateShared] 接口（commonMain 平台无关 API）+
 * [io.legado.app.ui.book.read.page.delegate.PageDelegate] 抽象基类（sharedUiMain
 * Compose 渲染入口）由 actual 平台注入。KP2-D 已实现 [io.legado.app.ui.book.read.page.delegate.CoverPageDelegate]
 * / [io.legado.app.ui.book.read.page.delegate.SlidePageDelegate] / [io.legado.app.ui.book.read.page.delegate.SimulationPageDelegate]
 * / [io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate] / [io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegate]
 * 五种翻页 delegate，覆盖 app 端全部翻页模式。
 *
 * @param readBook 跨平台阅读状态承载类（已下沉 commonMain）
 * @param scope 协程作用域，actual 平台注入（Android=viewModelScope / 桌面=应用主作用域）
 * @param layoutConfig 排版几何 / 字号配置，actual 平台按窗口尺寸 / ReadBookConfig 注入；
 *   默认 [LayoutConfig.DEFAULT] 为桌面 720x1080 等价近似值，确保无注入时也能跑通
 */
class ReadBookViewModelShared(
    private val readBook: ReadBookShared,
    private val scope: CoroutineScope,
    private val layoutConfig: LayoutConfig = LayoutConfig.DEFAULT,
) {
    // region 页面状态流：外部只读 StateFlow，适配 Compose 重组
    private val _curTextPage = MutableStateFlow<TextPage?>(null)
    val curTextPage: StateFlow<TextPage?> = _curTextPage.asStateFlow()

    private val _prevTextPage = MutableStateFlow<TextPage?>(null)
    val prevTextPage: StateFlow<TextPage?> = _prevTextPage.asStateFlow()

    private val _nextTextPage = MutableStateFlow<TextPage?>(null)
    val nextTextPage: StateFlow<TextPage?> = _nextTextPage.asStateFlow()
    // endregion

    /**
     * 当前章节已排版的全部页列表（内存缓存）。
     *
     * [loadChapter] 排版完成后填充；[nextPage] / [prevPage] 翻页时从这里取相邻页。
     * 与 app 端 `TextChapter.pages` 对应。
     */
    private val pageList: MutableList<TextPage> = arrayListOf()

    /**
     * 当前页在 [pageList] 中的索引。与 app 端 `ReadBook.durPageIndex` 对应。
     */
    private var pageIndex: Int = 0

    /**
     * 翻页动画委托，由 actual 平台注入 [PageDelegateShared]（或其子类）实现。
     *
     * KP2-D：桌面端注入 [io.legado.app.ui.book.read.page.delegate.CoverPageDelegate]
     * / [io.legado.app.ui.book.read.page.delegate.SlidePageDelegate]
     * / [io.legado.app.ui.book.read.page.delegate.SimulationPageDelegate]
     * / [io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate]
     * / [io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegate]
     * （Compose Animatable + Modifier.offset 实现各种翻页动画）。
     *
     * 默认 null：未注入时 [io.legado.app.ui.book.read.page.ReadViewComposable] 不绘制动画层，
     * 仅支持点击翻页（[nextPage] / [prevPage] 同步切换 TextPage 状态）。
     *
     * 章节边界联动（KP2-D P0-5）：[nextPage] / [prevPage] 返回 false 时由调用方
     * （[io.legado.app.ui.book.read.page.delegate.PageDelegate.onAnimStop] 等）
     * 调 [moveToNextChapter] / [moveToPrevChapter] 切章。
     */
    var pageDelegate: PageDelegateShared? = null

    // region readBook 状态对外暴露 (readBook 私有, 桌面端 TTS Navigator 等外部消费者通过本区域访问)
    /**
     * 当前书籍 (委托 [readBook.book])。桌面端 TTS Navigator 用其取 bookUrl 查本地缓存。
     */
    val book: StateFlow<Book?> get() = readBook.book

    /**
     * 章节列表 (委托 [readBook.chapterList])。桌面端 TTS Navigator 用其按 index 取章节。
     */
    val chapterList: StateFlow<List<BookChapter>> get() = readBook.chapterList

    /**
     * 章节总数 (委托 [readBook.chapterSize])。桌面端 TTS Navigator 用其判定章节边界。
     */
    val chapterSize: Int get() = readBook.chapterSize

    /**
     * 当前章节索引 (委托 [readBook.durChapterIndex])。
     *
     * KP2-D P1: 桌面端 TocDrawerContent 用其高亮当前章节 + 跳转后自动滚动定位。
     * 切章时 [moveToPrevChapter] / [moveToNextChapter] / [loadChapter] 会通过
     * [ReadBookShared.updateDurChapterIndex] 推送新值, Compose 自动重组刷新高亮。
     */
    val durChapterIndex: StateFlow<Int> get() = readBook.durChapterIndex
    // endregion

    /**
     * 加载指定章节内容。
     *
     * KP2-D 实现流程（对照 app 端 `ReadBook.loadContent` → `ChapterProvider.getTextChapterAsync`
     * → `TextChapterLayout.getTextChapter`）：
     * 1. `readBook.loadChapter(index)` 同步 shared 状态（durChapterIndex / callback 通知）
     * 2. 从 [AppDbProviders] 取 `bookChapterDao.getBookChapterList` 读章节列表
     * 3. `readBook.updateChapterList(chapterList)` 同步 chapterSize
     * 4. 通过 [BookStorageProviders.get].getContent 读本地缓存正文
     *    （对应 app 端 `BookHelp.getContent`；桌面端需注册 `JvmBookStorage`）
     * 5. 正文按 `\n` 切段，调 [SimpleChapterLayout.layout] 排版产出 [TextPage] 列表
     * 6. 取首页写入 [_curTextPage]，次页写入 [_nextTextPage]，末页前一章暂不预取
     *
     * 简化项：
     * - KP2-D P0-B 已接入 `WebBook.getContentAwait` 联网拉取正文（缓存未命中时触发，
     *   对应 app 端 `CacheBook.getChapterContent` 编排）
     * - 不预取上一章 / 下一章（app 端 `prevChapter` / `nextChapter` 排版留 actual）
     * - 不做图片 / 段评 / 替换规则处理（[SimpleChapterLayout] 简化）
     * - 缓存未注册 / 文件不存在时写入"加载失败"占位页（保持 UI 可用）
     */
    fun loadChapter(index: Int) {
        scope.launch {
            // 1. 同步 shared 状态字段（durChapterIndex、callback 通知）
            readBook.loadChapter(index)

            // 2. 读章节列表（与 app 端 `appDb.bookChapterDao.getChapterList` 等价简化版）
            // BookChapterDao 仅暴露 getChapterList(bookUrl)，无 app 端的 getBookChapterList 别名
            val book = readBook.book.value ?: return@launch
            val chapterList: List<BookChapter> = runCatching {
                AppDbProviders.get().bookChapterDao.getChapterList(book.bookUrl)
            }.getOrDefault(emptyList())
            readBook.updateChapterList(chapterList)

            val chapter = chapterList.getOrNull(index)
            if (chapter == null) {
                // 章节序号越界：显示占位页
                updatePages(listOf(placeholderPage("无章节内容", index, chapterList.size)))
                return@launch
            }

            // 3. 读本地缓存正文（与 app 端 BookHelp.getContent 等价）
            var content: String? = runCatching {
                BookStorageProviders.get().getContent(book, chapter)
            }.getOrNull()

            if (content.isNullOrBlank()) {
                // 缓存未命中：联网拉取正文（与 app 端 CacheBook.getChapterContent →
                // WebBook.getContentAwait 编排一致，KP2-D P0-B 接入）
                val bookSource = runCatching {
                    AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
                }.getOrNull()
                if (bookSource == null) {
                    updatePages(
                        listOf(
                            placeholderPage(
                                "未找到书源（${book.origin}），无法联网拉取正文",
                                index,
                                chapterList.size,
                                chapter.title,
                            ),
                        ),
                    )
                    return@launch
                }

                // 下一章 URL（app 端 CacheBook 同样取 index+1 章节链接作 nextChapterUrl
                // 供正文规则正逆向截断用；越界时为 null）
                val nextChapterUrl = runCatching {
                    AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, index + 1)?.url
                }.getOrNull()

                val fetchedContent = runCatching {
                    WebBook.getContentAwait(
                        bookSource = bookSource,
                        book = book,
                        bookChapter = chapter,
                        nextChapterUrl = nextChapterUrl,
                        needSave = true,
                    )
                }.getOrNull()
                if (fetchedContent.isNullOrBlank()) {
                    updatePages(
                        listOf(
                            placeholderPage(
                                "联网拉取正文失败\n\n（请检查书源 / 网络后重试）",
                                index,
                                chapterList.size,
                                chapter.title,
                            ),
                        ),
                    )
                    return@launch
                }
                content = fetchedContent
            }

            // 4. 按 \n 切段，过滤空行，调 SimpleChapterLayout 排版
            // 此处 content 必非空（缓存命中或联网拉取成功，否则上方已 return@launch）
            val paragraphs = content!!.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val layout = buildLayout()
            val pages = layout.layout(
                displayTitle = chapter.title ?: "",
                contents = paragraphs,
                chapterIndex = index,
                chapterSize = chapterList.size,
            )

            // 5. 写入 pageList + 当前页 / 下一页状态流
            updatePages(pages)
        }
    }

    /**
     * 翻到下一页。
     *
     * 简化实现：从 [pageList] 取下一页写入 [_curTextPage]；
     * 已到本章节末页时返回 false（需 actual 触发 moveToNextChapter）。
     *
     * 与 app 端 `ReadBook.moveToNextPage` / `TextPageFactory.moveToNext` 的差异：
     * - 不调 `readBook.nextPage()`（shared 状态在 [pageIndex] 变化时同步）
     * - 不预取下一章（app 端 `nextChapter` 排版留 actual）
     */
    fun nextPage(): Boolean {
        if (pageIndex + 1 >= pageList.size) return false
        pageIndex++
        val cur = pageList[pageIndex]
        _curTextPage.value = cur
        _prevTextPage.value = pageList.getOrNull(pageIndex - 1)
        _nextTextPage.value = pageList.getOrNull(pageIndex + 1)
        readBook.updateDurChapterPos(cur.chapterPosition)
        return true
    }

    /**
     * 翻到上一页。
     *
     * 简化实现：从 [pageList] 取上一页写入 [_curTextPage]；
     * 已到本章节首页时返回 false（需 actual 触发 moveToPrevChapter）。
     */
    fun prevPage(): Boolean {
        if (pageIndex - 1 < 0) return false
        pageIndex--
        val cur = pageList[pageIndex]
        _curTextPage.value = cur
        _prevTextPage.value = pageList.getOrNull(pageIndex - 1)
        _nextTextPage.value = pageList.getOrNull(pageIndex + 1)
        readBook.updateDurChapterPos(cur.chapterPosition)
        return true
    }

    /**
     * 切到下一章。
     *
     * 与 app 端 `ReadBook.moveToNextChapter` 对应：
     * 1. 校验 `durChapterIndex + 1 < chapterSize`，越界返回 false
     * 2. 调 [ReadBookShared.updateDurChapterIndex] 同步 shared 状态（callback 通知）
     * 3. 调 [loadChapter] 拉取并排版新章节
     *
     * KP2-D P0-A：桌面端 [ReaderScreen] 下一章按钮直接调用本方法。
     * KP2-D P0-5：[io.legado.app.ui.book.read.page.delegate.PageDelegate.onAnimStop]
     * 翻到章节末页时也调用本方法切章。
     *
     * @return true 表示已触发切章；false 表示已到末章
     */
    fun moveToNextChapter(): Boolean {
        val cur = readBook.durChapterIndex.value
        if (cur + 1 >= readBook.chapterSize) return false
        readBook.updateDurChapterIndex(cur + 1)
        loadChapter(cur + 1)
        return true
    }

    /**
     * 是否可以切到下一章（不实际触发切章）。
     *
     * 供 [io.legado.app.ui.book.read.page.delegate.PageDelegateShared.hasNext] 判定用：
     * 章节末页时本章节无下一页，但若有下一章则仍允许翻页动画继续（动画停止时调
     * [moveToNextChapter] 切章）。
     */
    fun canMoveToNextChapter(): Boolean {
        val cur = readBook.durChapterIndex.value
        return cur + 1 < readBook.chapterSize
    }

    /**
     * 是否可以切到上一章（不实际触发切章）。
     *
     * 供 [io.legado.app.ui.book.read.page.delegate.PageDelegateShared.hasPrev] 判定用：
     * 章节首页时本章节无上一页，但若有上一章则仍允许翻页动画继续（动画停止时调
     * [moveToPrevChapter] 切章）。
     */
    fun canMoveToPrevChapter(): Boolean {
        val cur = readBook.durChapterIndex.value
        return cur - 1 >= 0
    }

    /**
     * 切到上一章。
     *
     * 与 app 端 `ReadBook.moveToPrevChapter` 对应：
     * 1. 校验 `durChapterIndex - 1 >= 0`，越界返回 false
     * 2. 调 [ReadBookShared.updateDurChapterIndex] 同步 shared 状态（callback 通知）
     * 3. 调 [loadChapter] 拉取并排版新章节
     *
     * KP2-D P0-A：桌面端 [ReaderScreen] 上一章按钮直接调用本方法。
     *
     * @return true 表示已触发切章；false 表示已到首页
     */
    fun moveToPrevChapter(): Boolean {
        val cur = readBook.durChapterIndex.value
        if (cur - 1 < 0) return false
        readBook.updateDurChapterIndex(cur - 1)
        loadChapter(cur - 1)
        return true
    }

    /**
     * 持久化阅读进度到 books 表。
     *
     * 与 app 端 `ReadBookActivity.onStop` → `appDb.bookDao.updateProgress` 编排对应：
     * 取 [ReadBookShared] 当前 durChapterIndex / durChapterPos / 章节标题，PATCH 进
     * books 表（避免整行 update 冲掉后台 updateToc/refreshBookInfo 写入的最新元数据）。
     *
     * KP2-D P0-C：桌面端 [ReaderScreen] `DisposableEffect.onDispose` 调用本方法。
     */
    fun saveProgress() {
        val book = readBook.book.value ?: return
        scope.launch {
            runCatching {
                AppDbProviders.get().bookDao.updateProgress(
                    bookUrl = book.bookUrl,
                    durChapterIndex = readBook.durChapterIndex.value,
                    durChapterPos = readBook.durChapterPos.value,
                    durChapterTime = systemCurrentTimeMillis(),
                    durChapterTitle = readBook.chapterList.value
                        .getOrNull(readBook.durChapterIndex.value)?.title,
                )
            }
        }
    }

    /**
     * 禁用当前书源 (对照 app 端 ReadBookViewModel.disableSource)。
     *
     * 取 readBook.book.value.origin 查 BookSource, 设 enabled=false 后 update 入库。
     * actual 平台若需附加 UI 反馈 (Toast / 退出阅读等), 在调用方处理。
     */
    fun disableSource() {
        val book = readBook.book.value ?: return
        scope.launch {
            runCatching {
                val source = AppDbProviders.get().bookSourceDao.getBookSource(book.origin) ?: return@launch
                source.enabled = false
                AppDbProviders.get().bookSourceDao.update(source)
            }
        }
    }

    /**
     * 用 [layoutConfig] 构造 [SimpleChapterLayout] 实例。
     *
     * 每次调用新建（排版参数可能动态变化，如窗口尺寸变化后）。
     * 测量器用 [SimpleTextMeasurer] 等宽近似实现，后续 actual 平台可替换为更精确实现。
     */
    private fun buildLayout(): SimpleChapterLayout {
        val cfg = layoutConfig
        val measurer = SimpleTextMeasurer(
            textSizePx = cfg.textSizePx,
            letterSpacingPx = cfg.letterSpacingPx,
            descent = cfg.textSizePx * 0.2f,
        )
        return SimpleChapterLayout(
            measurer = measurer,
            visibleWidth = cfg.visibleWidth,
            visibleHeight = cfg.visibleHeight,
            paddingLeft = cfg.paddingLeft,
            paddingTop = cfg.paddingTop,
            textHeight = cfg.textSizePx * cfg.lineSpacingExtra,
            descent = cfg.textSizePx * 0.2f,
            lineSpacingExtra = cfg.lineSpacingExtra,
            paragraphSpacing = cfg.paragraphSpacing,
            titleTopSpacing = cfg.titleTopSpacing,
            titleBottomSpacing = cfg.titleBottomSpacing,
            paragraphIndent = cfg.paragraphIndent,
            textFullJustify = cfg.textFullJustify,
            useZhLayout = cfg.useZhLayout,
        )
    }

    /**
     * 用 [pages] 替换 [pageList]，重置 [pageIndex]=0，刷新三个状态流。
     *
     * 与 app 端 `TextChapterLayout` 排版完成后 `textPages.clear() + addAll(pages)`
     * + `ReadBook.callBack.contentLoadFinish()` 行为对齐。
     */
    private fun updatePages(pages: List<TextPage>) {
        pageList.clear()
        pageList.addAll(pages)
        pageIndex = 0
        _curTextPage.value = pageList.getOrNull(0)
        _prevTextPage.value = null
        _nextTextPage.value = pageList.getOrNull(1)
    }

    /**
     * 构造占位 [TextPage]（章节越界 / 缓存未命中的兜底页）。
     *
     * @param msg 显示文本
     * @param chapterIndex 章节序号
     * @param chapterSize 章节总数
     * @param title 章节标题（可选，默认 "提示"）
     */
    private fun placeholderPage(
        msg: String,
        chapterIndex: Int,
        chapterSize: Int,
        title: String? = "提示",
    ): TextPage = TextPage(
        text = msg,
        title = title ?: "",
        chapterIndex = chapterIndex,
        chapterSize = chapterSize,
    ).apply {
        isCompleted = true
        isMsgPage = true
    }

    /**
     * 排版几何 / 字号配置。
     *
     * actual 平台按窗口尺寸 / ReadBookConfig 注入；默认 [DEFAULT] 为桌面 720x1080
     * 等价近似值，确保无注入时也能跑通排版链路。
     *
     * @param viewWidth 视图总宽（含 padding，px）
     * @param viewHeight 视图总高（含 padding，px）
     * @param paddingLeft/Top/Right/Bottom 内边距（px）
     * @param textSizePx 文字大小（px，对应 app 端 `ChapterProvider.contentPaint.textSize`）
     * @param letterSpacingPx 字间距（px，默认 0）
     * @param lineSpacingExtra 行高乘数（如 1.2，对应 app 端 `ChapterProvider.lineSpacingExtra`）
     * @param paragraphSpacing 段间距（对应 app 端 `ChapterProvider.paragraphSpacing`）
     * @param titleTopSpacing 标题顶部留白（px）
     * @param titleBottomSpacing 标题底部留白（px）
     * @param paragraphIndent 段落缩进字符串（默认全角空格 `　　`）
     * @param textFullJustify 是否两端对齐
     * @param useZhLayout 是否启用 ZhLineBreaker 中文避头尾断行
     */
    data class LayoutConfig(
        val viewWidth: Int = 720,
        val viewHeight: Int = 1080,
        val paddingLeft: Int = 32,
        val paddingTop: Int = 24,
        val paddingRight: Int = 32,
        val paddingBottom: Int = 24,
        val textSizePx: Float = 40f,
        val letterSpacingPx: Float = 0f,
        val lineSpacingExtra: Float = 1.2f,
        val paragraphSpacing: Int = 2,
        val titleTopSpacing: Int = 16,
        val titleBottomSpacing: Int = 24,
        val paragraphIndent: String = "　　",
        val textFullJustify: Boolean = true,
        val useZhLayout: Boolean = true,
    ) {
        /** 可视区宽度（px，扣除左右内边距） */
        val visibleWidth: Int get() = viewWidth - paddingLeft - paddingRight

        /** 可视区高度（px，扣除上下内边距） */
        val visibleHeight: Int get() = viewHeight - paddingTop - paddingBottom

        companion object {
            /** 默认配置：桌面 720x1080 + 20sp 字号近似（textSizePx=40 @ 2x density） */
            val DEFAULT = LayoutConfig()
        }
    }
}

/**
 * 搜索结果定位信息 (KMP 共享)。
 *
 * 原 app 端 `ReadBookViewModel.SearchPosition` 下沉至 commonMain,
 * 供 app 端 ReadBookViewModel 及桌面端共享 [searchResultPositions] 算法产物。
 * 纯数据类, 无 Android 依赖。
 */
data class SearchPosition(
    val pageIndex: Int,
    val lineIndex: Int,
    val charIndex: Int,
    val addLine: Int,
    val charIndex2: Int
)

/**
 * 内容搜索跳转定位算法 (KMP 共享)。
 *
 * 原 app 端 `ReadBookViewModel.searchResultPositions` 纯算法下沉:
 * 根据 [searchResult] 在章节正文中第 N 次出现的位置, 反推其所在页 / 行 / 字符偏移,
 * 并处理跨行 / 跨页修正。零 Android 依赖, 外部依赖全部参数化:
 * - `textChapter.pages` → [pages]
 * - `textChapter.getContent()` → [content]
 * - `searchContentQuery` (ViewModel 字段) → [query]
 *
 * 实现逻辑与 app 端原方法完全一致, 仅做位置迁移与参数化, 未改变任何计算步骤。
 *
 * @param pages 章节已排版的页列表 (对应 TextChapter.pages)
 * @param content 章节正文全文 (对应 TextChapter.getContent())
 * @param query 搜索关键字 (对应 ReadBookViewModel.searchContentQuery)
 * @param searchResult 单个搜索结果 (含 resultCountWithinChapter 用于定位第 N 次出现)
 * @return 计算出的 [SearchPosition]
 */
fun searchResultPositions(
    pages: List<TextPage>,
    content: String,
    query: String,
    searchResult: SearchResult
): SearchPosition {
    // calculate search result's pageIndex
    val queryLength = query.length

    var count = 0
    var index = content.indexOf(query)
    while (count != searchResult.resultCountWithinChapter) {
        index = content.indexOf(query, index + queryLength)
        count += 1
    }
    val contentPosition = index
    var pageIndex = 0
    var length = pages[pageIndex].text.length
    while (length < contentPosition && pageIndex + 1 < pages.size) {
        pageIndex += 1
        length += pages[pageIndex].text.length
    }

    // calculate search result's lineIndex
    val currentPage = pages[pageIndex]
    val curTextLines = currentPage.lines
    var lineIndex = 0
    var curLine = curTextLines[lineIndex]
    length = length - currentPage.text.length + curLine.text.length
    if (curLine.isParagraphEnd) length++
    while (length <= contentPosition && lineIndex + 1 < curTextLines.size) {
        lineIndex += 1
        curLine = curTextLines[lineIndex]
        length += curLine.text.length
        if (curLine.isParagraphEnd) length++
    }

    // charIndex
    val currentLine = currentPage.lines[lineIndex]
    var curLineLength = currentLine.text.length
    if (currentLine.isParagraphEnd) curLineLength++
    length -= curLineLength

    val charIndex = contentPosition - length
    var addLine = 0
    var charIndex2 = 0
    // change line
    if ((charIndex + queryLength) > curLineLength) {
        addLine = 1
        charIndex2 = charIndex + queryLength - curLineLength - 1
    }
    // changePage
    if ((lineIndex + addLine + 1) > currentPage.lines.size) {
        addLine = -1
        charIndex2 = charIndex + queryLength - curLineLength - 1
    }
    return SearchPosition(pageIndex, lineIndex, charIndex, addLine, charIndex2)
}
