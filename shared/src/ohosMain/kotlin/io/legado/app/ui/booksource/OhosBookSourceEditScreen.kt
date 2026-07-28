package io.legado.app.ui.booksource

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import io.legado.app.ui.compose.component.AppTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.openURL
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.model.SharedJsScope
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.book.source.edit.BookSourceEditCallbacks

import io.legado.app.ui.book.source.edit.BookSourceEditState
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.launch

/**
 * 鸿蒙端书源编辑 Screen 入口 (包装 shared/sharedUiMain 的 [io.legado.app.ui.book.source.edit.BookSourceEditScreen])。
 *
 * 对照 iOS 端 [io.legado.app.ui.booksource.IosBookSourceEditScreen] / desktop `BookSourceEditScreen.kt` 的包装模式,
 * 鸿蒙端在 [OhosBookSourceScreen] 的 onEdit 回调中用 Dialog 全屏展示本入口。
 *
 * 业务逻辑 (upSourceView / getSource / saveSource) 与 iOS / desktop / app 端一致,
 * 仅做鸿蒙平台适配 (systemCurrentTimeMillis 替代 NSDate/System.currentTimeMillis, openURL 替代 browseUrl)。
 *
 * # 简化项 (与 iOS / desktop 对比)
 *
 * - 复制/粘贴/分享: 鸿蒙端剪贴板为 stub, 暂留 no-op + TODO
 * - 自动缩进: 依赖 CodeView (Android 专属), no-op
 * - codeEditorSlot: 用 [AppTextField] 替代 CodeView (与 iOS 端一致, 鸿蒙端无 AndroidView)
 * - bottomBar: 鸿蒙端无 KeyboardToolbar, 留空
 *
 * @param sourceUrl 书源 URL (空串视为新建)
 * @param onBack 返回回调
 * @param onSaved 保存成功回调, 传回保存后的 bookSourceUrl (对照 app 端 setResult origin)
 * @param onDebugSource 调试回调 (鸿蒙端暂未接入 OhosBookSourceDebugScreen, 默认 no-op)
 */
@Composable
fun OhosBookSourceEditScreen(
    sourceUrl: String,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onDebugSource: (String) -> Unit = {},
) {
    BookSourceEditContent(sourceUrl, onBack, onSaved, onDebugSource)
}

@Composable
private fun BookSourceEditContent(
    sourceUrl: String,
    onBackCallback: () -> Unit,
    onSavedCallback: (String) -> Unit,
    onDebugSource: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    val editState = remember { BookSourceEditState() }

    val sourceEntities = remember { ArrayList<EditEntity>() }
    val searchEntities = remember { ArrayList<EditEntity>() }
    val exploreEntities = remember { ArrayList<EditEntity>() }
    val infoEntities = remember { ArrayList<EditEntity>() }
    val tocEntities = remember { ArrayList<EditEntity>() }
    val contentEntities = remember { ArrayList<EditEntity>() }
    val reviewEntities = remember { ArrayList<EditEntity>() }

    // 图片样式下拉选项 label
    val imageStyleDefaultLabel = rememberString("image_style_default")
    val imageStyleFullLabel = rememberString("image_style_full")
    val imageStyleTextLabel = rememberString("image_style_text")
    val imageStyleSingleLabel = rememberString("image_style_single")
    val imageStyleSelections = remember(
        imageStyleDefaultLabel, imageStyleFullLabel, imageStyleTextLabel, imageStyleSingleLabel,
    ) {
        listOf(
            imageStyleDefaultLabel to null,
            imageStyleFullLabel to Book.imgStyleFull,
            imageStyleTextLabel to Book.imgStyleText,
            imageStyleSingleLabel to Book.imgStyleSingle,
        )
    }

    val bookSourceState = remember { mutableStateOf<BookSource?>(null) }

    // 对话框状态
    var showLoginDialog by remember { mutableStateOf<BookSource?>(null) }
    var showVariableDialog by remember { mutableStateOf(false) }

    // AlertDialog 文案
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    val nonNullNameUrlLabel = rememberString("non_null_name_url")
    val saveFailedLabel = rememberString("save_failed")
    val saveSourceErrorLabel = rememberString("save_source_error")
    val saveBookSourceFailedText = rememberString("save_book_source_failed_log")
    val debugSourceSaveFailedText = rememberString("debug_source_save_failed_log")

    // upSourceView EditEntity label
    val sourceUrlLabel = rememberString("source_url")
    val sourceNameLabel = rememberString("source_name")
    val sourceGroupLabel = rememberString("source_group")
    val commentLabel = rememberString("comment")
    val sourceLoginUrlLabel = rememberString("source_login_url")
    val sourceLoginUiLabel = rememberString("source_login_ui")
    val loginCheckJsLabel = rememberString("login_check_js")
    val coverDecodeJsLabel = rememberString("cover_decode_js")
    val bookUrlPatternLabel = rememberString("book_url_pattern")
    val sourceHttpHeaderLabel = rememberString("source_http_header")
    val variableCommentLabel = rememberString("variable_comment")
    val concurrentRateLabel = rememberString("concurrent_rate")
    val jsLibLabel = rememberString("js_lib")
    val rSearchUrlLabel = rememberString("r_search_url")
    val checkKeyWordLabel = rememberString("check_key_word")
    val rBookListLabel = rememberString("r_book_list")
    val rBookNameLabel = rememberString("r_book_name")
    val rAuthorLabel = rememberString("r_author")
    val ruleBookKindLabel = rememberString("rule_book_kind")
    val ruleWordCountLabel = rememberString("rule_word_count")
    val ruleLastChapterLabel = rememberString("rule_last_chapter")
    val ruleBookIntroLabel = rememberString("rule_book_intro")
    val ruleCoverUrlLabel = rememberString("rule_cover_url")
    val rBookUrlLabel = rememberString("r_book_url")
    val ruleHasMoreLabel = rememberString("rule_has_more")
    val rFindUrlLabel = rememberString("r_find_url")
    val ruleBookInfoInitLabel = rememberString("rule_book_info_init")
    val ruleTocUrlLabel = rememberString("rule_toc_url")
    val ruleCanReNameLabel = rememberString("rule_can_re_name")
    val downloadUrlRuleLabel = rememberString("download_url_rule")
    val preUpdateJsLabel = rememberString("pre_update_js")
    val ruleChapterNameLabel = rememberString("rule_chapter_name")
    val ruleChapterUrlLabel = rememberString("rule_chapter_url")
    val ruleIsVolumeLabel = rememberString("rule_is_volume")
    val ruleUpdateTimeLabel = rememberString("rule_update_time")
    val ruleIsVipLabel = rememberString("rule_is_vip")
    val ruleIsPayLabel = rememberString("rule_is_pay")
    val ruleBookContentLabel = rememberString("rule_book_content")
    val subContentRuleLabel = rememberString("sub_content_rule")
    val ruleShouldOverrideUrlLoadingLabel = rememberString("rule_should_override_url_loading")
    val ruleWebJsLabel = rememberString("rule_web_js")
    val ruleSourceRegexLabel = rememberString("rule_source_regex")
    val ruleReplaceRegexLabel = rememberString("rule_replace_regex")
    val ruleImageStyleLabel = rememberString("rule_image_style")
    val imageDecodeLabel = rememberString("image_decode")
    val rulePayActionLabel = rememberString("rule_pay_action")
    val musicCoverRuleLabel = rememberString("music_cover_rule")
    val reviewCountRuleLabel = rememberString("review_count_rule")
    val totalReviewCountRuleLabel = rememberString("total_review_count_rule")
    val reviewUrlRuleLabel = rememberString("review_url_rule")
    val reviewListRuleLabel = rememberString("review_list_rule")
    val reviewIdRuleLabel = rememberString("review_id_rule")
    val avatarRuleLabel = rememberString("avatar_rule")
    val nicknameRuleLabel = rememberString("nickname_rule")
    val contentRuleLabel = rememberString("content_rule")
    val postTimeRuleLabel = rememberString("post_time_rule")
    val extraRuleLabel = rememberString("extra_rule")
    val imagesRuleLabel = rememberString("images_rule")
    val voteUpCountRuleLabel = rememberString("vote_up_count_rule")
    val voteUpSelectedRuleLabel = rememberString("vote_up_selected_rule")
    val voteDownSelectedRuleLabel = rememberString("vote_down_selected_rule")
    val replyCountRuleLabel = rememberString("reply_count_rule")
    val replyListUrlLabel = rememberString("reply_list_url")
    val voteUpRuleLabel = rememberString("vote_up_rule")
    val voteDownRuleLabel = rememberString("vote_down_rule")
    val replyRuleLabel = rememberString("reply_rule")
    val deleteRuleLabel = rememberString("delete_rule")

    // AlertDialog 显示状态
    var emptyUrlNameDialog by remember { mutableStateOf(false) }
    var saveErrorDialog by remember { mutableStateOf<String?>(null) }

    // ---- 业务辅助函数 ----

    fun bookSourceTypeToIndex(type: Int): Int = when (type) {
        BookSourceType.rss -> 5
        BookSourceType.video -> 4
        BookSourceType.file -> 3
        BookSourceType.image -> 2
        BookSourceType.audio -> 1
        else -> 0
    }

    fun indexToBookSourceType(index: Int): Int = when (index) {
        5 -> BookSourceType.rss
        4 -> BookSourceType.video
        3 -> BookSourceType.file
        2 -> BookSourceType.image
        1 -> BookSourceType.audio
        else -> BookSourceType.default
    }

    // 同步表单状态与字段实体 (对照 app 端 Activity.upSourceView)
    fun upSourceView(bookSource: BookSource?) {
        val bs = bookSource ?: BookSource()
        editState.bookSourceTypeIndex = bookSourceTypeToIndex(bs.bookSourceType)
        editState.enabled = bs.enabled
        editState.enabledCookieJar = bs.enabledCookieJar == true
        editState.enableDangerousApi = bs.enableDangerousApi == true
        editState.enabledExplore = bs.enabledExplore
        editState.enabledReview = bs.enabledReview
        editState.exploreStyleIndex = if (BookSource.exploreStyleIsVideo(bs.exploreStyle)) 1 else 0
        editState.exploreColsIndex = BookSource.exploreStyleCols(bs.exploreStyle).coerceIn(0, 6)
        // 基本信息
        sourceEntities.clear()
        sourceEntities.apply {
            add(EditEntity("bookSourceUrl", bs.bookSourceUrl, sourceUrlLabel))
            add(EditEntity("bookSourceName", bs.bookSourceName, sourceNameLabel))
            add(EditEntity("bookSourceGroup", bs.bookSourceGroup, sourceGroupLabel))
            add(EditEntity("bookSourceComment", bs.bookSourceComment, commentLabel))
            add(EditEntity("loginUrl", bs.loginUrl, sourceLoginUrlLabel))
            add(EditEntity("loginUi", bs.loginUi, sourceLoginUiLabel))
            add(EditEntity("loginCheckJs", bs.loginCheckJs, loginCheckJsLabel))
            add(EditEntity("coverDecodeJs", bs.coverDecodeJs, coverDecodeJsLabel))
            add(EditEntity("bookUrlPattern", bs.bookUrlPattern, bookUrlPatternLabel))
            add(EditEntity("header", bs.header, sourceHttpHeaderLabel))
            add(EditEntity("variableComment", bs.variableComment, variableCommentLabel))
            add(EditEntity("concurrentRate", bs.concurrentRate, concurrentRateLabel))
            add(EditEntity("jsLib", bs.jsLib, jsLibLabel))
        }
        // 搜索
        val sr = bs.searchRule
        searchEntities.clear()
        searchEntities.apply {
            add(EditEntity("searchUrl", bs.searchUrl, rSearchUrlLabel))
            add(EditEntity("checkKeyWord", sr.checkKeyWord, checkKeyWordLabel))
            add(EditEntity("bookList", sr.bookList, rBookListLabel))
            add(EditEntity("name", sr.name, rBookNameLabel))
            add(EditEntity("author", sr.author, rAuthorLabel))
            add(EditEntity("kind", sr.kind, ruleBookKindLabel))
            add(EditEntity("wordCount", sr.wordCount, ruleWordCountLabel))
            add(EditEntity("lastChapter", sr.lastChapter, ruleLastChapterLabel))
            add(EditEntity("intro", sr.intro, ruleBookIntroLabel))
            add(EditEntity("coverUrl", sr.coverUrl, ruleCoverUrlLabel))
            add(EditEntity("bookUrl", sr.bookUrl, rBookUrlLabel))
            add(EditEntity("hasMoreRule", sr.hasMoreRule, ruleHasMoreLabel))
        }
        // 发现
        val er = bs.exploreRule
        exploreEntities.clear()
        exploreEntities.apply {
            add(EditEntity("exploreUrl", bs.exploreUrl, rFindUrlLabel))
            add(EditEntity("bookList", er.bookList, rBookListLabel))
            add(EditEntity("name", er.name, rBookNameLabel))
            add(EditEntity("author", er.author, rAuthorLabel))
            add(EditEntity("kind", er.kind, ruleBookKindLabel))
            add(EditEntity("wordCount", er.wordCount, ruleWordCountLabel))
            add(EditEntity("lastChapter", er.lastChapter, ruleLastChapterLabel))
            add(EditEntity("intro", er.intro, ruleBookIntroLabel))
            add(EditEntity("coverUrl", er.coverUrl, ruleCoverUrlLabel))
            add(EditEntity("bookUrl", er.bookUrl, rBookUrlLabel))
            add(EditEntity("hasMoreRule", er.hasMoreRule, ruleHasMoreLabel))
        }
        // 详情页
        val ir = bs.bookInfoRule
        infoEntities.clear()
        infoEntities.apply {
            add(EditEntity("init", ir.init, ruleBookInfoInitLabel))
            add(EditEntity("name", ir.name, rBookNameLabel))
            add(EditEntity("author", ir.author, rAuthorLabel))
            add(EditEntity("kind", ir.kind, ruleBookKindLabel))
            add(EditEntity("wordCount", ir.wordCount, ruleWordCountLabel))
            add(EditEntity("lastChapter", ir.lastChapter, ruleLastChapterLabel))
            add(EditEntity("intro", ir.intro, ruleBookIntroLabel))
            add(EditEntity("coverUrl", ir.coverUrl, ruleCoverUrlLabel))
            add(EditEntity("tocUrl", ir.tocUrl, ruleTocUrlLabel))
            add(EditEntity("canReName", ir.canReName, ruleCanReNameLabel))
            add(EditEntity("downloadUrls", ir.downloadUrls, downloadUrlRuleLabel))
        }
        // 目录页
        val tr = bs.tocRule
        tocEntities.clear()
        tocEntities.apply {
            add(EditEntity("preUpdateJs", tr.preUpdateJs, preUpdateJsLabel))
            add(EditEntity("chapterList", tr.chapterList, rBookListLabel))
            add(EditEntity("chapterName", tr.chapterName, ruleChapterNameLabel))
            add(EditEntity("chapterUrl", tr.chapterUrl, ruleChapterUrlLabel))
            add(EditEntity("isVolume", tr.isVolume, ruleIsVolumeLabel))
            add(EditEntity("updateTime", tr.updateTime, ruleUpdateTimeLabel))
            add(EditEntity("isVip", tr.isVip, ruleIsVipLabel))
            add(EditEntity("isPay", tr.isPay, ruleIsPayLabel))
            add(EditEntity("nextTocUrl", tr.nextTocUrl, ruleHasMoreLabel))
        }
        // 正文页
        val cr = bs.contentRule
        contentEntities.clear()
        contentEntities.apply {
            add(EditEntity("content", cr.content, ruleBookContentLabel))
            add(EditEntity("subContent", cr.subContent, subContentRuleLabel))
            add(EditEntity("title", cr.title, ruleChapterNameLabel))
            add(EditEntity("nextContentUrl", cr.nextContentUrl, ruleHasMoreLabel))
            add(EditEntity("shouldOverrideUrlLoading", cr.shouldOverrideUrlLoading, ruleShouldOverrideUrlLoadingLabel))
            add(EditEntity("webJs", cr.webJs, ruleWebJsLabel))
            add(EditEntity("sourceRegex", cr.sourceRegex, ruleSourceRegexLabel))
            add(EditEntity("replaceRegex", cr.replaceRegex, ruleReplaceRegexLabel))
            add(
                EditEntity(
                    "imageStyle",
                    cr.imageStyle,
                    ruleImageStyleLabel,
                    EditEntity.ViewType.spinner,
                    imageStyleSelections,
                ),
            )
            add(EditEntity("imageDecode", cr.imageDecode, imageDecodeLabel))
            add(EditEntity("payAction", cr.payAction, rulePayActionLabel))
            add(EditEntity("musicCover", cr.musicCover, musicCoverRuleLabel))
        }
        // 段评
        val rr = bs.reviewRule
        reviewEntities.clear()
        reviewEntities.apply {
            add(EditEntity("reviewCountRule", rr.reviewCountRule, reviewCountRuleLabel))
            add(EditEntity("totalCountRule", rr.totalCountRule, totalReviewCountRuleLabel))
            add(EditEntity("reviewUrl", rr.reviewUrl, reviewUrlRuleLabel))
            add(EditEntity("reviewList", rr.reviewList, reviewListRuleLabel))
            add(EditEntity("hasMoreRule", rr.hasMoreRule, ruleHasMoreLabel))
            add(EditEntity("reviewIdRule", rr.reviewIdRule, reviewIdRuleLabel))
            add(EditEntity("avatarRule", rr.avatarRule, avatarRuleLabel))
            add(EditEntity("nameRule", rr.nameRule, nicknameRuleLabel))
            add(EditEntity("contentRule", rr.contentRule, contentRuleLabel))
            add(EditEntity("postTimeRule", rr.postTimeRule, postTimeRuleLabel))
            add(EditEntity("extraRule", rr.extraRule, extraRuleLabel))
            add(EditEntity("imagesRule", rr.imagesRule, imagesRuleLabel))
            add(EditEntity("voteUpCountRule", rr.voteUpCountRule, voteUpCountRuleLabel))
            add(EditEntity("voteUpSelectedRule", rr.voteUpSelectedRule, voteUpSelectedRuleLabel))
            add(EditEntity("voteDownSelectedRule", rr.voteDownSelectedRule, voteDownSelectedRuleLabel))
            add(EditEntity("replyCountRule", rr.replyCountRule, replyCountRuleLabel))
            add(EditEntity("replyListUrl", rr.replyListUrl, replyListUrlLabel))
            add(EditEntity("voteUpRule", rr.voteUpRule, voteUpRuleLabel))
            add(EditEntity("voteDownRule", rr.voteDownRule, voteDownRuleLabel))
            add(EditEntity("replyRule", rr.replyRule, replyRuleLabel))
            add(EditEntity("deleteRule", rr.deleteRule, deleteRuleLabel))
        }
        editState.currentTab = 0
        editState.sourceVersion++
    }

    // 从表单收集字段构造 BookSource (对照 app 端 Activity.getSource)
    fun getSource(): BookSource {
        val source = bookSourceState.value?.copy() ?: BookSource()
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
                "subContent" -> contentRule.subContent = it.text
                "title" -> contentRule.title = it.text
                "nextContentUrl" -> contentRule.nextContentUrl = it.text
                "shouldOverrideUrlLoading" -> contentRule.shouldOverrideUrlLoading = it.text
                "webJs" -> contentRule.webJs = it.text
                "sourceRegex" -> contentRule.sourceRegex = it.text
                "replaceRegex" -> contentRule.replaceRegex = it.text
                "imageStyle" -> contentRule.imageStyle = it.text
                "imageDecode" -> contentRule.imageDecode = it.text
                "payAction" -> contentRule.payAction = it.text
                "musicCover" -> contentRule.musicCover = it.text
            }
        }
        reviewEntities.forEach {
            when (it.key) {
                "reviewCountRule" -> reviewRule.reviewCountRule = it.text
                "totalCountRule" -> reviewRule.totalCountRule = it.text
                "reviewUrl" -> reviewRule.reviewUrl = it.text
                "reviewList" -> reviewRule.reviewList = it.text
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

    // 保存书源 (用 systemCurrentTimeMillis 替代 NSDate/System.currentTimeMillis)
    fun saveSource() {
        val source = getSource()
        if (source.bookSourceUrl.isBlank() || source.bookSourceName.isBlank()) {
            emptyUrlNameDialog = true
            return
        }
        scope.launch {
            try {
                val oldSource = bookSourceState.value ?: BookSource()
                if (!source.equal(oldSource)) {
                    source.lastUpdateTime = systemCurrentTimeMillis()
                    if (oldSource.exploreUrl != source.exploreUrl) {
                        oldSource.clearExploreKindsCache()
                    }
                    if (oldSource.jsLib != source.jsLib) {
                        // 清缓存失败不应伪装成"保存失败" (provider 未注册时 remove 会抛), 单独兜住
                        runCatching { SharedJsScope.remove(oldSource.jsLib) }
                    }
                }
                val oldKey = oldSource.bookSourceUrl.takeIf { it.isNotBlank() }
                if (oldKey != null && oldKey != source.bookSourceUrl) {
                    SourceHelp.deleteBookSource(oldKey)
                }
                AppDbProviders.get().bookSourceDao.insert(source)
                bookSourceState.value = source
                onSavedCallback(source.bookSourceUrl)
            } catch (e: Throwable) {
                AppLog.put(saveBookSourceFailedText, e)
                saveErrorDialog = e.localizedMessage ?: saveFailedLabel
            }
        }
    }

    // 调试书源 (对照 app 端 Activity.debugSource: 先保存再跳转)
    fun debugSource() {
        val source = getSource()
        scope.launch {
            try {
                AppDbProviders.get().bookSourceDao.insert(source)
                bookSourceState.value = source
                if (source.bookSourceUrl.isNotBlank()) {
                    onDebugSource(source.bookSourceUrl)
                }
            } catch (e: Throwable) {
                AppLog.put(debugSourceSaveFailedText, e)
            }
        }
    }

    fun hasLogin(): Boolean = getSource().hasLogin()

    fun help(fileName: String) {
        val url = when (fileName) {
            "ruleHelp" -> "https://github.com/gedoor/legado/wiki/书源制作"
            "jsHelp" -> "https://github.com/gedoor/legado/wiki/Js规则"
            "regexHelp" -> "https://www.runoob.com/regexp/regexp-tutorial.html"
            else -> return
        }
        openURL(url)
    }

    // 按 tab 返回字段列表 (对照 app 端 Activity.editEntities)
    fun editEntities(tab: Int): List<EditEntity> = when (tab) {
        1 -> searchEntities
        2 -> exploreEntities
        3 -> infoEntities
        4 -> tocEntities
        5 -> contentEntities
        6 -> reviewEntities
        else -> sourceEntities
    }

    // 数据加载 (对照 app 端 onActivityCreated)
    LaunchedEffect(sourceUrl) {
        val source = if (sourceUrl.isNotBlank()) {
            AppDbProviders.get().bookSourceDao.getBookSource(sourceUrl)
        } else {
            null
        }
        bookSourceState.value = source
        upSourceView(source)
    }

    val callbacks = remember(onBackCallback, onSavedCallback, onDebugSource) {
        BookSourceEditCallbacks(
            onBack = { onBackCallback() },
            onSave = { saveSource() },
            onDebug = { debugSource() },
            onLogin = {
                showLoginDialog = bookSourceState.value
            },
            onSearch = {
                // TODO: 依赖搜索路由, 鸿蒙端暂未接入
            },
            onClearCookie = {
                // TODO: 依赖 CookieStore, 鸿蒙端未下沉 Cookie 管理
            },
            onCopySource = {
                // TODO: 鸿蒙端剪贴板为 stub, 后续接入 ohos pasteboard
            },
            onPasteSource = {
                // TODO: 鸿蒙端剪贴板为 stub, 后续接入 ohos pasteboard
            },
            onAutoIndent = {
                // TODO: 依赖 CodeView (Android 专属), 鸿蒙端 AppTextField 无对应能力
            },
            onSetSourceVariable = {
                showVariableDialog = true
            },
            onShareSourceStr = {
                // TODO: 鸿蒙端无分享面板, 后续接入
            },
            onHelp = { help(it) },
            hasLogin = { hasLogin() },
            onBookSourceTypeChange = { editState.bookSourceTypeIndex = it },
            onEnabledChange = { editState.enabled = it },
            onEnabledCookieJarChange = { editState.enabledCookieJar = it },
            onEnableDangerousApiClick = { editState.enableDangerousApi = it },
            onEnabledReviewChange = { editState.enabledReview = it },
            onEnabledExploreChange = { editState.enabledExplore = it },
            onExploreStyleChange = { editState.exploreStyleIndex = it },
            onExploreColsChange = { editState.exploreColsIndex = it },
            onTabChange = { editState.currentTab = it },
        )
    }

    io.legado.app.ui.book.source.edit.BookSourceEditScreen(
        state = editState,
        callbacks = callbacks,
        editEntities = { tab -> editEntities(tab) },
        codeEditorSlot = { entity, modifier ->
            AppTextField(
                value = entity.value.orEmpty(),
                onValueChange = { entity.value = it },
                modifier = modifier.fillMaxWidth(),
                label = entity.hint,
                textStyle = TextStyle(fontSize = 13.sp),
                minLines = 2,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        },
        bottomBar = {
            // 鸿蒙端无 KeyboardToolbar, 留空
        },
    )

    // ---- AlertDialog 渲染 ----

    // saveSource 校验失败: URL/Name 为空
    if (emptyUrlNameDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { emptyUrlNameDialog = false },
            title = { Text(saveFailedLabel) },
            text = { Text(nonNullNameUrlLabel) },
            confirmButton = {
                TextButton(onClick = { emptyUrlNameDialog = false }) {
                    Text(okLabel)
                }
            },
        )
    }

    // saveSource catch: 保存异常
    saveErrorDialog?.let { errorMsg ->
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { saveErrorDialog = null },
            title = { Text(saveSourceErrorLabel) },
            text = { Text(errorMsg) },
            confirmButton = {
                TextButton(onClick = { saveErrorDialog = null }) {
                    Text(okLabel)
                }
            },
        )
    }

    // 书源登录对话框
    showLoginDialog?.let { src ->
        SourceLoginDialog(
            source = src,
            onDismiss = { showLoginDialog = null },
            onOpenUrl = { url -> openURL(url) },
        )
    }

    // 变量编辑对话框 (书源编辑场景仅编辑源变量, bookVariables 传空 Map)
    if (showVariableDialog) {
        val src = bookSourceState.value
        VariableDialog(
            sourceVariables = decodeStringMapOrNull(src?.getVariable()) ?: emptyMap(),
            bookVariables = emptyMap(),
            onConfirm = { newSourceVars, _ ->
                scope.launch {
                    src?.setVariable(encodeStringMap(newSourceVars))
                }
                showVariableDialog = false
            },
            onDismiss = { showVariableDialog = false },
        )
    }
}
