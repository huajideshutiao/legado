package io.legado.app.ui.book.read

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Review
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 段评列表 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端 `ReviewListDialog.ReviewViewModel` 与 desktop 端 `ReviewListScreen.DesktopReviewViewModel`:
 * 两端 VM 业务逻辑 (分页抓取 / 回复 / 点赞 / 点踩 / 删除 / 规则执行) 完全一致, 均复用 shared
 * [WebBook.getReviewListAwait] / [WebBook.getReviewRepliesAwait] / [WebBook.evalReviewActionAwait],
 * 属于重复实现。本类把该业务逻辑下沉到 shared 单一数据源, 解除 desktop 端复制 app 端 VM 的阻塞点。
 *
 * # 状态 (用 StateFlow 替代 LiveData, KMP 友好)
 *
 * - [reviews]: 当前段评列表 (首页替换 / 翻页追加均由本类内部维护, 消费方只读)
 * - [totalCount]: 段评总数文本 (仅首页非回复模式发, 对应 app 端 `totalCountLiveData`)
 * - [loading]: 抓取中标志 (对应 app 端 `loading` + UI `footerLoading`)
 * - [hasMore]: 是否还有下一页 (对应 app 端 `hasMore` + UI `footerHasMore`)
 * - [sortState]: 排序方式 0=最热 1=最新 (对应 app 端 `sort` + UI `sortState`)
 * - [votedIds] / [votedDownIds] / [expandedKeys]: 点赞/点踩/展开态, 委托 [voteHelper]
 *
 * # 与 app 端 ReviewViewModel 的差异
 *
 * - app 端用 `MutableLiveData` 分发 `reviewsLiveData` (替换) / `appendReviewsLiveData` (追加批次);
 *   本类合并为单一 [reviews] StateFlow, 追加由本类内部 `_reviews + list` 完成 (消费方无需拼接两路流)。
 * - app 端 `context.toastOnUi(...)` 走 Android Context; 本类经 [ReviewPlatform.toastOnUi] 注入,
 *   actual 平台提供实现 (Android=Toast / 桌面=Toasters)。
 * - app 端 `b.getBookSource()` 是 app 专属扩展; 本类走 [AppDbProviders.get].bookSourceDao.getBookSource,
 *   与 desktop 原实现一致, KMP 通用。
 * - app 端 `execute(context = IO)` 走 BaseViewModel; 本类用注入的 [scope] + [Dispatchers.IO]。
 *
 * # 互斥与回滚 (严格对齐 app 端语义)
 *
 * voteUp/voteDown 先乐观翻转本地态 ([ReviewVoteHelper.toggleVoteUp] / [toggleVoteDown]),
 * 规则执行失败时 [ReviewVoteHelper.revertVoteUp] / [revertVoteDown] 回滚;
 * 点赞与点踩互斥 (点赞时移除点踩, 反之亦然), 由 [ReviewVoteHelper] 保证。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = `rememberCoroutineScope` 或应用主作用域)
 * @param platform 平台专属依赖 (Toast + 错误文案本地化)
 */
@Suppress("MemberVisibilityCanBePrivate")
class ReviewViewModelShared(
    private val scope: CoroutineScope,
    private val platform: ReviewPlatform,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    // ---- 输入参数 (调用方透传, 不查 DB, 与 app 端一致) ----

    var book: Book? = null
    var chapter: BookChapter? = null
    var paragraphIndex: Int = 0

    /**
     * 回复列表模式时的楼主段评 id; null=段评列表模式, 非null=回复列表模式。
     * 决定 [fetchPage] 走 [WebBook.getReviewRepliesAwait] 还是 [WebBook.getReviewListAwait]。
     */
    var replyReviewId: String? = null

    /** 回复列表模式的楼主段评 (仅用于 UI 标题/回复数显示, 不参与抓取逻辑)。 */
    var parentReview: Review? = null

    // ---- 状态流: 外部只读 StateFlow, 适配 Compose 重组 / Android asLiveData 桥接 ----

    private val _reviews = MutableStateFlow<List<Review>>(emptyList())
    val reviews: StateFlow<List<Review>> = _reviews.asStateFlow()

    private val _totalCount = MutableStateFlow<String?>(null)
    val totalCount: StateFlow<String?> = _totalCount.asStateFlow()

    /**
     * 抓取中标志。初始 true 使首帧即显示 footer loading (对照 app 端 UI `footerLoading = true` 初值)。
     */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _sortState = MutableStateFlow(0)
    val sortState: StateFlow<Int> = _sortState.asStateFlow()

    /** 排序方式 (对照 app 端 `var sort`), 读写委托 [sortState]。 */
    var sort: Int
        get() = _sortState.value
        set(value) { _sortState.value = value }

    // ---- 点赞/点踩/展开态: 委托 [voteHelper] ----

    private val voteHelper = ReviewVoteHelper()

    val votedIds: StateFlow<Set<String>> get() = voteHelper.votedIds
    val votedDownIds: StateFlow<Set<String>> get() = voteHelper.votedDownIds
    val expandedKeys: StateFlow<Set<String>> get() = voteHelper.expandedKeys

    // ---- 分页内部态 ----

    private var currentPage = 1
    private var isLoading = false

    /**
     * 加载首页 (对照 app 端 `load`)。
     *
     * 重置分页为第 1 页, [hasMore]=true, 调 [fetchPage] 抓取。
     * 调用前不清空 [reviews] (由 [fetchPage] 成功时替换 / 失败时清空, 与 app 端一致)。
     */
    fun load(sort: Int = _sortState.value) {
        _sortState.value = sort
        currentPage = 1
        _hasMore.value = true
        fetchPage(append = false)
    }

    /**
     * 加载下一页 (对照 app 端 `loadMore`)。
     *
     * [hasMore]=false 或正在加载时直接返回 (防重复触发)。
     */
    fun loadMore() {
        if (!_hasMore.value || isLoading) return
        currentPage += 1
        fetchPage(append = true)
    }

    /**
     * 切换排序 (对照 desktop 端 `changeSort`)。
     *
     * 立刻清空 [reviews] + 置 [loading]=true (footer 重新转起来当首屏 loading), 再 [load]。
     */
    fun changeSort(newSort: Int) {
        if (newSort == _sortState.value) return
        _reviews.value = emptyList()
        _loading.value = true
        load(newSort)
    }

    private fun fetchPage(append: Boolean) {
        val b = book ?: run {
            platform.toastOnUi(platform.noCurrentBook())
            return
        }
        _loading.value = true
        isLoading = true
        scope.launch(IoDispatcher) {
            try {
                val source = appDb.bookSourceDao.getBookSource(b.origin)
                    ?: throw IllegalStateException(platform.noSource())
                val page = currentPage
                val result = if (replyReviewId != null) {
                    WebBook.getReviewRepliesAwait(source, b, chapter, paragraphIndex, replyReviewId!!, page)
                        .getOrThrow()
                } else {
                    WebBook.getReviewListAwait(source, b, chapter, paragraphIndex, page, _sortState.value)
                        .getOrThrow()
                }
                _hasMore.value = result.hasNextPage
                val list = result.reviews
                if (list.isEmpty()) {
                    if (!append) _reviews.value = emptyList()
                } else {
                    // 抓取到的每条 item 先灌入 voted/votedDown 初始态 (对照 app 端 observer 内 seedVoteFromItem)
                    list.forEach { voteHelper.seedVote(it) }
                    if (append) _reviews.value = _reviews.value + list
                    else _reviews.value = list
                }
                // 段评总数仅首页发 (首页 = !append), 回复模式不发 (与 app 端一致)
                if (!append && replyReviewId == null) {
                    _totalCount.value = result.totalCount
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (append) currentPage -= 1
                platform.toastOnUi(platform.loadFailed(e.message))
                if (!append) _reviews.value = emptyList()
                _hasMore.value = true
            } finally {
                isLoading = false
                _loading.value = false
            }
        }
    }

    /**
     * 回复评论 (对照 app 端 `reply`)。
     *
     * @param content 回复内容
     * @param reviewId 楼主段评 id; 顶层段评为空
     * @param onHandled 规则返回 true 类值时回调 (在 [load] 重载触发后调用, 调用方可在此做乐观 UI 更新,
     *   如递增 parentReview.replyCount)
     */
    fun reply(content: String, reviewId: String?, onHandled: (() -> Unit)? = null) {
        runRule(
            ruleSelector = { it.replyRule },
            content = content,
            reviewId = reviewId,
            reloadOnSuccess = true,
            onSuccess = { onHandled?.invoke() },
        )
    }

    /**
     * 点赞 (对照 app 端 `voteUp`)。
     *
     * 先 [ReviewVoteHelper.toggleVoteUp] 乐观翻转本地态, 规则失败时 [ReviewVoteHelper.revertVoteUp] 回滚。
     * selected = 翻转后的"是否已点赞"取反, 即传给书源的"调用前是否已赞" (与 app 端 `!target` 一致)。
     */
    fun voteUp(review: Review) {
        val id = review.id ?: return
        val target = voteHelper.toggleVoteUp(id)
        runRule(
            ruleSelector = { it.voteUpRule },
            reviewId = id,
            selected = !target,
            onError = { voteHelper.revertVoteUp(id) },
        )
    }

    /**
     * 点踩 (对照 app 端 `voteDown`)。
     */
    fun voteDown(review: Review) {
        val id = review.id ?: return
        val target = voteHelper.toggleVoteDown(id)
        runRule(
            ruleSelector = { it.voteDownRule },
            reviewId = id,
            selected = !target,
            onError = { voteHelper.revertVoteDown(id) },
        )
    }

    /**
     * 删除评论 (对照 app 端 `delete`)。
     *
     * @param onRemoved 规则返回 true 类值时回调, 携带被删 id; 调用方据此本地移除 (本类已自动从
     *   [reviews] 过滤, 回调供额外 UI 副作用如关闭确认对话框)
     */
    fun delete(review: Review, onRemoved: (String) -> Unit) {
        val id = review.id ?: return
        runRule(
            ruleSelector = { it.deleteRule },
            reviewId = id,
            onSuccess = {
                _reviews.value = _reviews.value.filterNot { it.id == id }
                onRemoved(id)
            },
        )
    }

    /** 翻转段评展开/收起态 (委托 [voteHelper])。 */
    fun toggleExpand(key: String) = voteHelper.toggleExpand(key)

    /** 把书源返回的 voted/votedDown 初始态灌入本地集合 (委托 [voteHelper], 供 UI 在设置 parentReview 时调用)。 */
    fun seedVote(item: Review) = voteHelper.seedVote(item)

    /**
     * 执行一个段评动作规则 (对照 app 端 `runRule`)。
     *
     * 仅返回 true 类值 ([asBoolean]) 表示规则"已处理", 走 [onSuccess] (并按需 [load] 重载);
     * 其它返回值表示规则"不想处理", 静默放过 (不视为失败也不触发 onSuccess);
     * 只有抛异常才算失败, 走 [onError] + toast。
     */
    private fun runRule(
        ruleSelector: (ReviewRule) -> String?,
        content: String? = null,
        reviewId: String? = null,
        selected: Boolean? = null,
        reloadOnSuccess: Boolean = false,
        onSuccess: ((Any?) -> Unit)? = null,
        onError: (() -> Unit)? = null,
    ) {
        val b = book ?: return
        scope.launch(IoDispatcher) {
            try {
                val source = appDb.bookSourceDao.getBookSource(b.origin) ?: return@launch
                if (source.ruleReview.isNullOrEmpty()) return@launch
                val rule = source.reviewRule
                val ruleText = ruleSelector(rule)
                if (ruleText.isNullOrBlank()) {
                    platform.toastOnUi(platform.noActionRule())
                    onError?.invoke()
                    return@launch
                }
                val result = WebBook.evalReviewActionAwait(
                    bookSource = source,
                    book = b,
                    bookChapter = chapter,
                    rule = ruleText,
                    paragraphIndex = paragraphIndex,
                    reviewId = reviewId,
                    contentText = content,
                    selected = selected,
                ).getOrThrow()
                if (!asBoolean(result)) return@launch
                if (reloadOnSuccess) load()
                onSuccess?.invoke(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                platform.toastOnUi(platform.operationFailed(e.message))
                onError?.invoke()
            }
        }
    }

    /**
     * 规则返回值转 Boolean (对照 app 端 `asBoolean`)。
     *
     * Boolean 按值; Number 非 0 为 true; 字符串非空且非 "false" (忽略大小写) 为 true。
     */
    private fun asBoolean(v: Any?): Boolean {
        if (v == null) return false
        if (v is Boolean) return v
        if (v is Number) return v.toDouble() != 0.0
        val s = v.toString().trim()
        return s.isNotEmpty() && !s.equals("false", ignoreCase = true)
    }
}

/**
 * 段评 ViewModel 平台专属依赖聚合 (对照 [io.legado.app.ui.book.changesource.ChangeBookSourcePlatform] 模式)。
 *
 * actual 平台提供实现:
 * - Android: 走 `context.toastOnUi` + `getString(R.string.xxx)` (或硬编码中文, 与原 app 端一致)
 * - 桌面: 走 `Toasters.get().toast` + `jvmGetString`
 *
 * 文案与 app 端 `ReviewListDialog.ReviewViewModel` 硬编码中文语义一一对应:
 * - [noCurrentBook] ↔ "无当前书籍"
 * - [noSource] ↔ "无书源"
 * - [loadFailed] ↔ "评论加载失败: ${cause}"
 * - [operationFailed] ↔ "操作失败: ${cause}"
 * - [noActionRule] ↔ "书源未配置此操作规则"
 */
interface ReviewPlatform {

    /** 显示 Toast (对照 `context.toastOnUi(msg)`)。 */
    fun toastOnUi(msg: String)

    /** "无当前书籍" (load 时 book 为空)。 */
    fun noCurrentBook(): String

    /** "无书源" (book.origin 对应书源不存在)。 */
    fun noSource(): String

    /** "评论加载失败: {cause}" (fetchPage 抛异常)。 */
    fun loadFailed(cause: String?): String

    /** "操作失败: {cause}" (runRule 抛异常)。 */
    fun operationFailed(cause: String?): String

    /** "书源未配置此操作规则" (runRule 时 ruleText 为空)。 */
    fun noActionRule(): String
}
