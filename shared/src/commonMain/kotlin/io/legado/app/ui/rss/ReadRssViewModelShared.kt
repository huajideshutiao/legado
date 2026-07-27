package io.legado.app.ui.rss

import io.legado.app.data.entities.Book
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.rss.RssContentResult
import io.legado.app.model.rss.RssHelp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RSS 文章阅读 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照桌面端原 `ReadRssScreen`: 拉取正文 (intro / WebBook.getContentAwait) 的逻辑直接写在
 * Composable 的 `rememberCoroutineScope().launch { withContext(IO) { ... } }` 内, 未走 shared 层。
 * 本类把加载流程下沉到 commonMain, 让多端复用同一逻辑。
 *
 * 对照 app 端 [io.legado.app.ui.book.rss.ReadRssViewModel.initData] + `loadContent`,
 * 数据源与三态分支 (intro / contentRule / 无规则) 一致。
 *
 * # 加载流程 (委托 [RssHelp.loadRssContent])
 *
 * 1. 取关联 BookSource (`book.origin` = `bookSource.bookSourceUrl`)
 * 2. 取章节 BookChapter (`bookChapterDao.getChapter(bookUrl, chapterIndex)`)
 * 3. 分支:
 *    - `book.originName == "RSS" && !book.intro.isNullOrBlank()` → [RssContentResult.Content] (intro)
 *    - `source.contentRule.content` 为空 → [RssContentResult.Empty] (无正文规则)
 *    - 否则 [WebBook.getContentAwait] 拉正文 → [RssContentResult.Content]
 *
 * # 状态管理
 *
 * 用 [StateFlow] 暴露 [ReadRssUiState], 宿主用 `collectAsState` 订阅。
 * - 初始 [ReadRssUiState.Loading]: 居中转圈
 * - [ReadRssUiState.Content]: 拿到正文 (原始 HTML body), 调用方按平台格式化渲染
 *   (app 端 `clHtml` 包 HTML 给 WebView / 桌面端 `HtmlFormatter.format` 转纯文本)
 * - [ReadRssUiState.Empty]: 无正文规则或内容为空, UI 提示 "无正文规则, 请用浏览器打开"
 * - [ReadRssUiState.Error]: 加载失败, 显示 [ReadRssUiState.Error.message]
 *
 * # 不下沉项 (留各端 UI 层)
 *
 * - 正文格式化: app 端 `clHtml` (WebView 渲染含 webJs/style), 桌面端 `HtmlFormatter.format` (转纯文本)。
 *   本类只返回原始 HTML body, 由调用方格式化。
 * - 浏览器打开外链: 桌面端 `java.awt.Desktop.browse`, app 端 `Intent/Uri`。
 * - TTS 朗读: app 端 `ReadRssViewModel.tts` 依赖 Android TextToSpeech。
 *
 * # 线程
 *
 * DB/网络操作走 [Dispatchers.IO] (与原桌面端 `withContext(Dispatchers.IO)` 一致),
 * StateFlow.value 设置线程安全, 宿主 collectAsState 自动切回 Main 渲染。
 *
 * @param scope 调用方提供的协程作用域
 *   (Android: `lifecycleScope` / desktop: `rememberCoroutineScope()` / iOS: 应用 scope)
 */
class ReadRssViewModelShared(
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<ReadRssUiState>(ReadRssUiState.Loading)
    /** 正文 UI 状态, 宿主 collectAsState 订阅。 */
    val state: StateFlow<ReadRssUiState> = _state.asStateFlow()

    /**
     * 拉取正文。
     *
     * 委托 [RssHelp.loadRssContent] 执行三态分支, 结果映射到 [ReadRssUiState]:
     * - [RssContentResult.Content] → [ReadRssUiState.Content] (body 为原始 HTML, 调用方按平台格式化)
     * - [RssContentResult.Empty] → [ReadRssUiState.Empty]
     * - [RssContentResult.Error] → [ReadRssUiState.Error]
     *
     * 可重复调用 (顶栏刷新按钮触发)。
     *
     * @param book RSS 源对应的 Book (type 含 BookType.rss 位)
     * @param chapterIndex 章节 index (对应 RssArticlesScreen 点击回调)
     */
    fun loadContent(book: Book, chapterIndex: Int) {
        _state.value = ReadRssUiState.Loading
        scope.launch {
            withContext(IoDispatcher) {
                val result = RssHelp.loadRssContent(book, chapterIndex)
                _state.value = when (result) {
                    is RssContentResult.Content -> ReadRssUiState.Content(
                        body = result.body,
                        chapter = result.chapter,
                    )
                    is RssContentResult.Empty -> ReadRssUiState.Empty(chapter = result.chapter)
                    is RssContentResult.Error -> ReadRssUiState.Error(
                        message = result.message,
                        chapter = result.chapter,
                    )
                }
            }
        }
    }
}

/**
 * RSS 正文阅读 UI 状态 (四态 sealed class)。
 *
 * 供 UI 层 `when` 穷尽分支渲染:
 * - [Loading]: 加载中 (居中转圈)
 * - [Content]: 拿到正文 (原始 HTML body), 调用方按平台格式化渲染
 * - [Empty]: 无正文规则或内容为空 (UI 提示用浏览器打开)
 * - [Error]: 加载失败 (显示 message)
 *
 * [chapter] 在各态中供 UI 显示标题 + "浏览器打开" 按钮 (Error 态可能为 null)。
 */
sealed class ReadRssUiState {
    /** 加载中 (居中转圈)。 */
    object Loading : ReadRssUiState()

    /** 拿到正文 (body 为原始 HTML, 调用方按平台格式化: app 端 clHtml / 桌面端 HtmlFormatter.format)。 */
    data class Content(
        val body: String,
        val chapter: io.legado.app.data.entities.BookChapter,
    ) : ReadRssUiState()

    /** 无正文规则或内容为空 (UI 提示用浏览器打开)。 */
    data class Empty(val chapter: io.legado.app.data.entities.BookChapter) : ReadRssUiState()

    /** 加载失败 (message 供 UI 显示; chapter 可能为 null)。 */
    data class Error(
        val message: String,
        val chapter: io.legado.app.data.entities.BookChapter?,
    ) : ReadRssUiState()
}
