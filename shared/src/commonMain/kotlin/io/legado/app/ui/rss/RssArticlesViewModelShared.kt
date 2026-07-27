package io.legado.app.ui.rss

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.rss.RssHelp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RSS 文章列表 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照桌面端原 `RssArticlesScreen`: 拉取文章列表 (BookChapter 列表) 的逻辑直接写在
 * Composable 的 `rememberCoroutineScope().launch { withContext(IO) { ... } }` 内,
 * 未走 shared 层。本类把加载流程下沉到 commonMain, 让多端复用同一逻辑。
 *
 * 对照 app 端 `ReadRssActivity` 的章节列表加载 (走 `BaseReadViewModel.upBook` +
 * `WebBook.getChapterListAwait`), 数据源与流程一致。
 *
 * # 加载流程 (委托 [RssHelp.loadRssArticles])
 *
 * 1. 先用 [RssHelp.getCachedArticles] 读本地缓存, 立即显示 (避免联网期间空白)
 * 2. 异步 [RssHelp.loadRssArticles] 拉取最新文章列表, 落库 + 刷新 UI
 * 3. 失败时保留本地缓存; 缓存为空则进入 [ArticlesUiState.Error]
 *
 * # 状态管理
 *
 * 用 [StateFlow] 暴露 [ArticlesUiState], 宿主用 `collectAsState` 订阅。
 * - 初始 [ArticlesUiState.Loading]: 首次进入无缓存, 居中转圈
 * - [ArticlesUiState.Data]: 拿到数据 (缓存或新拉取), 渲染列表 (空列表时 UI 显示 "暂无文章")
 * - [ArticlesUiState.Error]: 加载失败且无缓存, 显示错误信息
 *
 * # 线程
 *
 * DB/网络操作走 [Dispatchers.IO] (与原桌面端 `withContext(Dispatchers.IO)` 一致),
 * StateFlow.value 设置线程安全, 宿主 collectAsState 自动切回 Main 渲染。
 *
 * @param scope 调用方提供的协程作用域
 *   (Android: `lifecycleScope` / desktop: `rememberCoroutineScope()` / iOS: 应用 scope)
 */
class RssArticlesViewModelShared(
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<ArticlesUiState>(ArticlesUiState.Loading)
    /** 文章列表 UI 状态, 宿主 collectAsState 订阅。 */
    val state: StateFlow<ArticlesUiState> = _state.asStateFlow()

    /**
     * 拉取文章列表。
     *
     * 流程 (对照桌面端原 RssArticlesScreen.loadArticles):
     * 1. 先读本地缓存, 非空立即进入 [ArticlesUiState.Data] 显示
     * 2. 联网 [RssHelp.loadRssArticles] 拉取最新, 成功刷新 [ArticlesUiState.Data]
     * 3. 失败回退本地缓存: 缓存非空保持 [ArticlesUiState.Data], 缓存空进入 [ArticlesUiState.Error]
     *
     * 可重复调用 (顶栏刷新按钮触发), 每次都会重新联网拉取。
     *
     * @param book RSS 源对应的 Book (type 含 BookType.rss 位)
     */
    fun loadArticles(book: Book) {
        // 缓存为空时显示 Loading; 有缓存时保持当前 Data, 联网期间不闪烁
        scope.launch {
            withContext(Dispatchers.IO) {
                // 先用本地缓存快速填充, 避免联网期间空白
                val cached = RssHelp.getCachedArticles(book)
                if (cached.isNotEmpty()) {
                    _state.value = ArticlesUiState.Data(cached)
                } else if (_state.value !is ArticlesUiState.Data) {
                    _state.value = ArticlesUiState.Loading
                }
                // 联网拉取最新文章列表
                RssHelp.loadRssArticles(book)
                    .onSuccess { toc ->
                        _state.value = ArticlesUiState.Data(toc)
                    }
                    .onFailure { e ->
                        // 失败时回退本地缓存
                        val fallback = RssHelp.getCachedArticles(book)
                        _state.value = if (fallback.isEmpty()) {
                            ArticlesUiState.Error(e.localizedMessage ?: "加载失败")
                        } else {
                            ArticlesUiState.Data(fallback)
                        }
                    }
            }
        }
    }
}

/**
 * RSS 文章列表 UI 状态 (三态 sealed class)。
 *
 * 供 UI 层 `when` 穷尽分支渲染:
 * - [Loading]: 加载中且无缓存, 居中转圈
 * - [Data]: 拿到数据 (缓存或新拉取), 渲染列表; [Data.articles] 为空时 UI 显示 "暂无文章"
 * - [Error]: 加载失败且无缓存, 显示 [Error.message]
 */
sealed class ArticlesUiState {
    /** 加载中且无缓存 (居中转圈)。 */
    object Loading : ArticlesUiState()

    /** 拿到数据 (缓存或新拉取); articles 为空时 UI 显示 "暂无文章"。 */
    data class Data(val articles: List<BookChapter>) : ArticlesUiState()

    /** 加载失败且无缓存 (显示 message)。 */
    data class Error(val message: String) : ArticlesUiState()
}
