package io.legado.app.ui.book.source.edit

import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.widget.text.EditEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 书源编辑页 ScreenModel: 托管 [BookSourceEditUiState] 与编辑事件分发。
 *
 * 落库核心委托 [BookSourceEditViewModelShared] (initData / save / pasteSource / clearCookie)。
 * 宿主 Activity 负责表单字段 ([BookSourceEditState]) + 平台专属行为
 * (IntentData / showDialogFragment / 路由跳转 / CookieStore 等)。
 *
 * @param sharedFactory 用本类自管的 scope 构造 shared VM (对照 ReplaceEditScreenModel: scope
 *   随 ScreenModel 生命周期, [onCleared] 时取消)
 */
class BookSourceEditScreenModel(
    sharedFactory: (CoroutineScope) -> BookSourceEditViewModelShared,
) : ScreenModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val shared: BookSourceEditViewModelShared = sharedFactory(scope)

    private val _state = MutableStateFlow(BookSourceEditUiState())
    val state: StateFlow<BookSourceEditUiState> = _state.asStateFlow()

    /** 当前编辑的书源 (委托 shared.bookSource, Activity 只读访问组装表单)。 */
    val bookSource: BookSource? get() = shared.bookSource

    // 各 tab 实体列表 (对照 app 端 BookSourceEditActivity 的 ArrayList<EditEntity>)
    private val sourceEntities = mutableListOf<EditEntity>()
    private val searchEntities = mutableListOf<EditEntity>()
    private val exploreEntities = mutableListOf<EditEntity>()
    private val infoEntities = mutableListOf<EditEntity>()
    private val tocEntities = mutableListOf<EditEntity>()
    private val contentEntities = mutableListOf<EditEntity>()
    private val reviewEntities = mutableListOf<EditEntity>()

    // 图片样式选项 (对照 app 端 imageStyleSelections)
    // first 是展示名: "text_default" 为资源 key (对照 getString(R.string.text_default)),
    // 其余为字面值, 由 SpinnerField 的 rememberString 统一解析
    private val imageStyleSelections = listOf(
        "text_default" to null,
        Book.imgStyleFull to Book.imgStyleFull,
        Book.imgStyleText to Book.imgStyleText,
        Book.imgStyleSingle to Book.imgStyleSingle,
    )

    override fun onCleared() {
        scope.cancel()
    }

    fun dispatch(event: BookSourceEditUiEvent) {
        when (event) {
            is BookSourceEditUiEvent.Init -> shared.initData(event.sourceUrl) {
                _state.update {
                    it.copy(
                        bookSource = shared.bookSource,
                        isAdd = event.sourceUrl == null,
                        editType = shared.bookSource?.bookSourceType ?: 0,
                    )
                }
                // 构建实体列表 (对照 app 端 upSourceView)
                upSourceView(shared.bookSource)
                event.onFinally()
            }

            is BookSourceEditUiEvent.Save -> shared.save(event.source, event.onSuccess)

            is BookSourceEditUiEvent.PasteSource -> shared.pasteSource { source ->
                // 对照 app 端 pasteSource { upSourceView(it) }: 粘贴成功后重建实体列表
                upSourceView(source)
                event.onSuccess(source)
            }

            is BookSourceEditUiEvent.ClearCookie -> shared.clearCookie(event.url)
        }
    }

    /** 按 tab 返回当前页实体列表 (对照 app 端 editEntities(tab))。 */
    fun editEntities(tab: Int): List<EditEntity> = when (tab) {
        1 -> searchEntities
        2 -> exploreEntities
        3 -> infoEntities
        4 -> tocEntities
        5 -> contentEntities
        6 -> reviewEntities
        else -> sourceEntities
    }

    /**
     * 从 [BookSourceEditState] + 各 tab 实体列表组装当前编辑的 [BookSource] (对照 app 端 getSource)。
     *
     * @param editState 宿主持有的表单状态 (header 字段)
     * @return 组装后的 BookSource, 可传给 [BookSourceEditUiEvent.Save] / 平台能力
     */
    fun getSource(editState: BookSourceEditState): BookSource {
        val source = shared.bookSource?.copy() ?: BookSource()
        val searchRule = SearchRule()
        val exploreRule = ExploreRule()
        val bookInfoRule = BookInfoRule()
        val tocRule = TocRule()
        val contentRule = ContentRule()
        val reviewRule = ReviewRule()
        source.bookSourceType = indexToBookSourceType(editState.bookSourceTypeIndex)
        source.enabled = editState.enabled
        source.enabledCookieJar = editState.enabledCookieJar
        source.enableDangerousApi = editState.enableDangerousApi
        source.enabledExplore = editState.enabledExplore
        source.enabledReview = editState.enabledReview
        val exploreVideo = editState.exploreStyleIndex == 1
        source.exploreStyle = (if (exploreVideo) BookSource.EXPLORE_STYLE_VIDEO_FLAG else 0) or
            (editState.exploreColsIndex and BookSource.EXPLORE_STYLE_COLS_MASK)
        sourceEntities.forEach {
            when (it.key) {
                "bookSourceUrl" -> source.bookSourceUrl = it.text.orEmpty()
                "bookSourceName" -> source.bookSourceName = it.text.orEmpty()
                "bookSourceGroup" -> source.bookSourceGroup = it.text
                "loginUrl" -> source.loginUrl = it.text
                "loginUi" -> source.loginUi = it.text
                "loginCheckJs" -> source.loginCheckJs = it.text
                "coverDecodeJs" -> source.coverDecodeJs = it.text
                "bookUrlPattern" -> source.bookUrlPattern = it.text
                "header" -> source.header = it.text
                "bookSourceComment" -> source.bookSourceComment = it.text
                "concurrentRate" -> source.concurrentRate = it.text
                "variableComment" -> source.variableComment = it.text
                "jsLib" -> source.jsLib = it.text
            }
        }
        searchEntities.forEach {
            when (it.key) {
                "searchUrl" -> source.searchUrl = it.text
                "checkKeyWord" -> searchRule.checkKeyWord = it.text
                "bookList" -> searchRule.bookList = it.text
                "name" -> searchRule.name = it.text
                "author" -> searchRule.author = it.text
                "kind" -> searchRule.kind = it.text
                "intro" -> searchRule.intro = it.text
                "wordCount" -> searchRule.wordCount = it.text
                "lastChapter" -> searchRule.lastChapter = it.text
                "coverUrl" -> searchRule.coverUrl = it.text
                "bookUrl" -> searchRule.bookUrl = it.text
                "hasMoreRule" -> searchRule.hasMoreRule = it.text
            }
        }
        exploreEntities.forEach {
            when (it.key) {
                "exploreUrl" -> source.exploreUrl = it.text
                "bookList" -> exploreRule.bookList = it.text
                "name" -> exploreRule.name = it.text
                "author" -> exploreRule.author = it.text
                "kind" -> exploreRule.kind = it.text
                "intro" -> exploreRule.intro = it.text
                "wordCount" -> exploreRule.wordCount = it.text
                "lastChapter" -> exploreRule.lastChapter = it.text
                "coverUrl" -> exploreRule.coverUrl = it.text
                "bookUrl" -> exploreRule.bookUrl = it.text
                "hasMoreRule" -> exploreRule.hasMoreRule = it.text
            }
        }
        infoEntities.forEach {
            when (it.key) {
                "init" -> bookInfoRule.init = it.text
                "name" -> bookInfoRule.name = it.text
                "author" -> bookInfoRule.author = it.text
                "kind" -> bookInfoRule.kind = it.text
                "intro" -> bookInfoRule.intro = it.text
                "wordCount" -> bookInfoRule.wordCount = it.text
                "lastChapter" -> bookInfoRule.lastChapter = it.text
                "coverUrl" -> bookInfoRule.coverUrl = it.text
                "tocUrl" -> bookInfoRule.tocUrl = it.text
                "canReName" -> bookInfoRule.canReName = it.text
                "downloadUrls" -> bookInfoRule.downloadUrls = it.text
            }
        }
        tocEntities.forEach {
            when (it.key) {
                "preUpdateJs" -> tocRule.preUpdateJs = it.text
                "chapterList" -> tocRule.chapterList = it.text
                "chapterName" -> tocRule.chapterName = it.text
                "chapterUrl" -> tocRule.chapterUrl = it.text
                "isVolume" -> tocRule.isVolume = it.text
                "updateTime" -> tocRule.updateTime = it.text
                "isVip" -> tocRule.isVip = it.text
                "isPay" -> tocRule.isPay = it.text
                "nextTocUrl" -> tocRule.nextTocUrl = it.text
            }
        }
        contentEntities.forEach {
            when (it.key) {
                "content" -> contentRule.content = it.text
                "title" -> contentRule.title = it.text
                "nextContentUrl" -> contentRule.nextContentUrl = it.text
                "shouldOverrideUrlLoading" -> contentRule.shouldOverrideUrlLoading = it.text
                "webJs" -> contentRule.webJs = it.text
                "sourceRegex" -> contentRule.sourceRegex = it.text
                "replaceRegex" -> contentRule.replaceRegex = it.text
                "imageStyle" -> contentRule.imageStyle = it.text
                "imageDecode" -> contentRule.imageDecode = it.text
                "payAction" -> contentRule.payAction = it.text
                "subContent" -> contentRule.subContent = it.text
                "musicCover" -> contentRule.musicCover = it.text
            }
        }
        reviewEntities.forEach {
            when (it.key) {
                "reviewUrl" -> reviewRule.reviewUrl = it.text
                "reviewList" -> reviewRule.reviewList = it.text
                "reviewCountRule" -> reviewRule.reviewCountRule = it.text
                "totalCountRule" -> reviewRule.totalCountRule = it.text
                "hasMoreRule" -> reviewRule.hasMoreRule = it.text
                "reviewIdRule" -> reviewRule.reviewIdRule = it.text
                "avatarRule" -> reviewRule.avatarRule = it.text
                "nameRule" -> reviewRule.nameRule = it.text
                "contentRule" -> reviewRule.contentRule = it.text
                "postTimeRule" -> reviewRule.postTimeRule = it.text
                "extraRule" -> reviewRule.extraRule = it.text
                "imagesRule" -> reviewRule.imagesRule = it.text
                "voteUpCountRule" -> reviewRule.voteUpCountRule = it.text
                "voteUpSelectedRule" -> reviewRule.voteUpSelectedRule = it.text
                "voteDownSelectedRule" -> reviewRule.voteDownSelectedRule = it.text
                "replyCountRule" -> reviewRule.replyCountRule = it.text
                "replyListUrl" -> reviewRule.replyListUrl = it.text
                "voteUpRule" -> reviewRule.voteUpRule = it.text
                "voteDownRule" -> reviewRule.voteDownRule = it.text
                "replyRule" -> reviewRule.replyRule = it.text
                "deleteRule" -> reviewRule.deleteRule = it.text
            }
        }
        source.searchRule = searchRule
        source.exploreRule = exploreRule
        source.bookInfoRule = bookInfoRule
        source.tocRule = tocRule
        source.contentRule = contentRule
        source.reviewRule = reviewRule
        return source
    }

    /** tab 下标转书源类型 (对照 app 端 indexToBookSourceType)。 */
    private fun indexToBookSourceType(index: Int): Int = when (index) {
        5 -> BookSourceType.rss
        4 -> BookSourceType.video
        3 -> BookSourceType.file
        2 -> BookSourceType.image
        1 -> BookSourceType.audio
        else -> BookSourceType.default
    }

    /** 构建各 tab 实体列表 (对照 app 端 BookSourceEditActivity.upSourceView)。 */
    private fun upSourceView(bookSource: BookSource?) {
        val bs = bookSource ?: BookSource()
        // 基本信息
        sourceEntities.clear()
        sourceEntities.apply {
            add(EditEntity("bookSourceUrl", bs.bookSourceUrl, "source_url"))
            add(EditEntity("bookSourceName", bs.bookSourceName, "source_name"))
            add(EditEntity("bookSourceGroup", bs.bookSourceGroup, "source_group"))
            add(EditEntity("bookSourceComment", bs.bookSourceComment, "comment"))
            add(EditEntity("loginUrl", bs.loginUrl, "login_url"))
            add(EditEntity("loginUi", bs.loginUi, "login_ui"))
            add(EditEntity("loginCheckJs", bs.loginCheckJs, "login_check_js"))
            add(EditEntity("coverDecodeJs", bs.coverDecodeJs, "cover_decode_js"))
            add(EditEntity("bookUrlPattern", bs.bookUrlPattern, "book_url_pattern"))
            add(EditEntity("header", bs.header, "source_http_header"))
            add(EditEntity("variableComment", bs.variableComment, "variable_comment"))
            add(EditEntity("concurrentRate", bs.concurrentRate, "concurrent_rate"))
            add(EditEntity("jsLib", bs.jsLib, "jsLib"))
        }
        // 搜索
        val sr = bs.searchRule
        searchEntities.clear()
        searchEntities.apply {
            add(EditEntity("searchUrl", bs.searchUrl, "r_search_url"))
            add(EditEntity("checkKeyWord", sr.checkKeyWord, "check_key_word"))
            add(EditEntity("bookList", sr.bookList, "r_book_list"))
            add(EditEntity("name", sr.name, "r_book_name"))
            add(EditEntity("author", sr.author, "r_author"))
            add(EditEntity("kind", sr.kind, "rule_book_kind"))
            add(EditEntity("wordCount", sr.wordCount, "rule_word_count"))
            add(EditEntity("lastChapter", sr.lastChapter, "rule_last_chapter"))
            add(EditEntity("intro", sr.intro, "rule_book_intro"))
            add(EditEntity("coverUrl", sr.coverUrl, "rule_cover_url"))
            add(EditEntity("bookUrl", sr.bookUrl, "r_book_url"))
            add(EditEntity("hasMoreRule", sr.hasMoreRule, "rule_has_more"))
        }
        // 发现
        val er = bs.exploreRule
        exploreEntities.clear()
        exploreEntities.apply {
            add(EditEntity("exploreUrl", bs.exploreUrl, "r_find_url"))
            add(EditEntity("bookList", er.bookList, "r_book_list"))
            add(EditEntity("name", er.name, "r_book_name"))
            add(EditEntity("author", er.author, "r_author"))
            add(EditEntity("kind", er.kind, "rule_book_kind"))
            add(EditEntity("wordCount", er.wordCount, "rule_word_count"))
            add(EditEntity("lastChapter", er.lastChapter, "rule_last_chapter"))
            add(EditEntity("intro", er.intro, "rule_book_intro"))
            add(EditEntity("coverUrl", er.coverUrl, "rule_cover_url"))
            add(EditEntity("bookUrl", er.bookUrl, "r_book_url"))
            add(EditEntity("hasMoreRule", er.hasMoreRule, "rule_has_more"))
        }
        // 详情页
        val ir = bs.bookInfoRule
        infoEntities.clear()
        infoEntities.apply {
            add(EditEntity("init", ir.init, "rule_book_info_init"))
            add(EditEntity("name", ir.name, "r_book_name"))
            add(EditEntity("author", ir.author, "r_author"))
            add(EditEntity("kind", ir.kind, "rule_book_kind"))
            add(EditEntity("wordCount", ir.wordCount, "rule_word_count"))
            add(EditEntity("lastChapter", ir.lastChapter, "rule_last_chapter"))
            add(EditEntity("intro", ir.intro, "rule_book_intro"))
            add(EditEntity("coverUrl", ir.coverUrl, "rule_cover_url"))
            add(EditEntity("tocUrl", ir.tocUrl, "rule_toc_url"))
            add(EditEntity("canReName", ir.canReName, "rule_can_re_name"))
            add(EditEntity("downloadUrls", ir.downloadUrls, "download_url_rule"))
        }
        // 目录页
        val tr = bs.tocRule
        tocEntities.clear()
        tocEntities.apply {
            add(EditEntity("preUpdateJs", tr.preUpdateJs, "pre_update_js"))
            add(EditEntity("chapterList", tr.chapterList, "rule_chapter_list"))
            add(EditEntity("chapterName", tr.chapterName, "rule_chapter_name"))
            add(EditEntity("chapterUrl", tr.chapterUrl, "rule_chapter_url"))
            add(EditEntity("isVolume", tr.isVolume, "rule_is_volume"))
            add(EditEntity("updateTime", tr.updateTime, "rule_update_time"))
            add(EditEntity("isVip", tr.isVip, "rule_is_vip"))
            add(EditEntity("isPay", tr.isPay, "rule_is_pay"))
            add(EditEntity("nextTocUrl", tr.nextTocUrl, "rule_next_toc_url"))
        }
        // 正文页
        val cr = bs.contentRule
        contentEntities.clear()
        contentEntities.apply {
            add(EditEntity("content", cr.content, "rule_book_content"))
            add(EditEntity("subContent", cr.subContent, "rule_sub_content"))
            add(EditEntity("title", cr.title, "rule_chapter_name"))
            add(EditEntity("nextContentUrl", cr.nextContentUrl, "rule_next_content"))
            add(
                EditEntity(
                    "shouldOverrideUrlLoading",
                    cr.shouldOverrideUrlLoading,
                    "rule_should_override_url_loading"
                )
            )
            add(EditEntity("webJs", cr.webJs, "rule_web_js"))
            add(EditEntity("sourceRegex", cr.sourceRegex, "rule_source_regex"))
            add(EditEntity("replaceRegex", cr.replaceRegex, "rule_replace_regex"))
            add(
                EditEntity(
                    "imageStyle",
                    cr.imageStyle,
                    "rule_image_style",
                    EditEntity.ViewType.spinner,
                    imageStyleSelections
                )
            )
            add(EditEntity("imageDecode", cr.imageDecode, "rule_image_decode"))
            add(EditEntity("payAction", cr.payAction, "rule_pay_action"))
            add(EditEntity("musicCover", cr.musicCover, "rule_music_cover"))
        }
        // 段评
        val rr = bs.reviewRule
        reviewEntities.clear()
        reviewEntities.apply {
            add(EditEntity("reviewCountRule", rr.reviewCountRule, "rule_review_count"))
            add(EditEntity("totalCountRule", rr.totalCountRule, "rule_review_total_count"))
            add(EditEntity("reviewUrl", rr.reviewUrl, "rule_review_url"))
            add(EditEntity("reviewList", rr.reviewList, "rule_review_list"))
            add(EditEntity("hasMoreRule", rr.hasMoreRule, "rule_has_more"))
            add(EditEntity("reviewIdRule", rr.reviewIdRule, "rule_review_id"))
            add(EditEntity("avatarRule", rr.avatarRule, "rule_avatar"))
            add(EditEntity("nameRule", rr.nameRule, "rule_review_name"))
            add(EditEntity("contentRule", rr.contentRule, "rule_review_content"))
            add(EditEntity("postTimeRule", rr.postTimeRule, "rule_post_time"))
            add(EditEntity("extraRule", rr.extraRule, "rule_review_extra"))
            add(EditEntity("imagesRule", rr.imagesRule, "rule_review_images"))
            add(EditEntity("voteUpCountRule", rr.voteUpCountRule, "rule_vote_up_count"))
            add(EditEntity("voteUpSelectedRule", rr.voteUpSelectedRule, "rule_vote_up_selected"))
            add(
                EditEntity(
                    "voteDownSelectedRule",
                    rr.voteDownSelectedRule,
                    "rule_vote_down_selected"
                )
            )
            add(EditEntity("replyCountRule", rr.replyCountRule, "rule_reply_count"))
            add(EditEntity("replyListUrl", rr.replyListUrl, "rule_reply_list_url"))
            add(EditEntity("voteUpRule", rr.voteUpRule, "rule_vote_up"))
            add(EditEntity("voteDownRule", rr.voteDownRule, "rule_vote_down"))
            add(EditEntity("replyRule", rr.replyRule, "rule_reply"))
            add(EditEntity("deleteRule", rr.deleteRule, "rule_delete_review"))
        }
    }
}

data class BookSourceEditUiState(
    val bookSource: BookSource? = null,
    val isAdd: Boolean = false,
    val editType: Int = 0,
)

sealed interface BookSourceEditUiEvent {
    /** sourceUrl 为 null 表示新建 (对照 app 端 intent 无 sourceUrl extra)。 */
    data class Init(
        val sourceUrl: String?,
        val onFinally: () -> Unit,
    ) : BookSourceEditUiEvent

    data class Save(
        val source: BookSource,
        val onSuccess: (BookSource) -> Unit,
    ) : BookSourceEditUiEvent

    data class PasteSource(
        val onSuccess: (BookSource) -> Unit,
    ) : BookSourceEditUiEvent

    data class ClearCookie(val url: String) : BookSourceEditUiEvent
}
