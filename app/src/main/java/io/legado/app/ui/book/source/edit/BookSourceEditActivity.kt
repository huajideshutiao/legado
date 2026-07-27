package io.legado.app.ui.book.source.edit

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import android.content.Intent
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.IntentData
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.theme.applyThemeTree
import io.legado.app.lib.theme.space
import io.legado.app.model.SharedJsScope
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.login.showLoginDialog
import io.legado.app.ui.widget.dialog.showSourceVariableDialog
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.ui.widget.keyboard.KeyboardAssistsConfig
import io.legado.app.ui.widget.keyboard.KeyboardToolbar
import io.legado.app.ui.widget.keyboard.KeyboardToolbarState
import io.legado.app.ui.widget.keyboard.insertAtCursor
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity

class BookSourceEditActivity : BaseComposeActivity() {

    val viewModel by viewModels<BookSourceEditViewModel>()

    private val sourceEntities: ArrayList<EditEntity> = ArrayList()
    private val searchEntities: ArrayList<EditEntity> = ArrayList()
    private val exploreEntities: ArrayList<EditEntity> = ArrayList()
    private val infoEntities: ArrayList<EditEntity> = ArrayList()
    private val tocEntities: ArrayList<EditEntity> = ArrayList()
    private val contentEntities: ArrayList<EditEntity> = ArrayList()
    private val reviewEntities: ArrayList<EditEntity> = ArrayList()
    private val imageStyleSelections by lazy {
        listOf(
            getString(R.string.text_default) to null,
            Book.imgStyleFull to Book.imgStyleFull,
            Book.imgStyleText to Book.imgStyleText,
            Book.imgStyleSingle to Book.imgStyleSingle
        )
    }

    private val editEntityMaxLine = AppConfig.sourceEditMaxLine

    /** 顶部表单状态 (下沉到 shared 的 BookSourceEditState, 由其持有 mutableState 字段) */
    private val editState = BookSourceEditState()

    val toolbarState = KeyboardToolbarState()

    private var lastActiveCodeView: CodeView? = null

    @Composable
    override fun Content() {
        BookSourceEditScreen(
            state = editState,
            callbacks = remember {
                BookSourceEditCallbacks(
                    onBack = { finish() },
                    onSave = { saveSource() },
                    onDebug = { debugSource() },
                    onLogin = { login() },
                    onSearch = { searchSource() },
                    onClearCookie = { clearCookie() },
                    onCopySource = { copySource() },
                    onPasteSource = { pasteSource() },
                    onAutoIndent = { autoIndent() },
                    onSetSourceVariable = { setSourceVariable() },
                    onShareSourceStr = { shareSourceStr() },
                    onHelp = { help(it) },
                    hasLogin = { hasLogin() },
                    onBookSourceTypeChange = { editState.bookSourceTypeIndex = it },
                    onEnabledChange = { editState.enabled = it },
                    onEnabledCookieJarChange = { editState.enabledCookieJar = it },
                    onEnableDangerousApiClick = { onDangerousApiClick(it) },
                    onEnabledReviewChange = { editState.enabledReview = it },
                    onEnabledExploreChange = { editState.enabledExplore = it },
                    onExploreStyleChange = { editState.exploreStyleIndex = it },
                    onExploreColsChange = { editState.exploreColsIndex = it },
                    onTabChange = { editState.currentTab = it },
                )
            },
            editEntities = { editEntities(it) },
            codeEditorSlot = { entity, modifier ->
                AndroidView(
                    factory = { ctx -> createEditField(ctx, entity) },
                    modifier = modifier,
                )
            },
            bottomBar = {
                KeyboardToolbar(
                    state = toolbarState,
                    activeCodeView = { getActiveCodeView() },
                    onSendText = { sendText(it) },
                    onUndo = { undo() },
                    onRedo = { redo() },
                    onShowConfig = { showDialogFragment<KeyboardAssistsConfig>() },
                )
            },
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.navigationBars.union(WindowInsets.ime)
            ),
        )
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!toolbarState.tryConsumeBack { getActiveCodeView()?.clearSearch() }) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        viewModel.initData(intent) {
            upSourceView(viewModel.bookSource)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!LocalConfig.ruleHelpVersionIsLast) {
            showHelp("ruleHelp")
        }
    }

    fun editEntities(tab: Int): List<EditEntity> = when (tab) {
        1 -> searchEntities
        2 -> exploreEntities
        3 -> infoEntities
        4 -> tocEntities
        5 -> contentEntities
        6 -> reviewEntities
        else -> sourceEntities
    }

    // ===== 菜单动作 =====

    fun saveSource() {
        viewModel.save(getSource()) {
            IntentData.source = it
            // origin extra: ChangeBookSourceDialog 靠它对编辑过的源重搜（61503841e 断链修复）
            setResult(RESULT_OK, Intent().putExtra("origin", it.bookSourceUrl))
            finish()
        }
    }

    fun debugSource() {
        viewModel.save(getSource()) { source ->
            startActivity<BookSourceDebugActivity> {
                IntentData.source = source
            }
        }
    }

    fun hasLogin(): Boolean = getSource().hasLogin()

    fun login() {
        viewModel.save(getSource()) { source ->
            source.showLoginDialog(this)
        }
    }

    fun setSourceVariable() {
        viewModel.save(getSource()) { source ->
            source.showSourceVariableDialog(this)
        }
    }

    fun searchSource() {
        viewModel.save(getSource()) { source ->
            startActivity<SearchActivity> {
                putExtra("searchScope", SearchScope(source).toString())
            }
        }
    }

    fun clearCookie() = viewModel.clearCookie(getSource().bookSourceUrl)

    fun copySource() = sendToClip(GSON.toJson(getSource()))

    fun pasteSource() = viewModel.pasteSource { upSourceView(it) }

    fun autoIndent() {
        getActiveCodeView()?.reFormat()
    }

    fun shareSourceStr() = share(GSON.toJson(getSource()))

    fun help(fileName: String) = showHelp(fileName)

    fun onDangerousApiClick(isChecked: Boolean) {
        editState.enableDangerousApi = isChecked
        val originalEnabled = viewModel.bookSource?.enableDangerousApi == true
        if (isChecked != originalEnabled) {
            SharedJsScope.remove(viewModel.bookSource?.jsLib)
        }
        if (isChecked) {
            alert(R.string.enable_dangerous_api) {
                setMessage(R.string.enable_dangerous_api_confirm)
                positiveButton(R.string.ok)
                negativeButton(R.string.cancel) {
                    editState.enableDangerousApi = false
                }
                onCancelled {
                    editState.enableDangerousApi = false
                }
            }
        }
    }

    override fun finish() {
        val source = getSource()
        if (!source.equal(viewModel.bookSource ?: BookSource())) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) {
                    super.finish()
                }
            }
        } else {
            super.finish()
        }
    }

    private fun upSourceView(bookSource: BookSource?) {
        val bs = bookSource ?: BookSource()
        // Header
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
            add(EditEntity("bookSourceUrl", bs.bookSourceUrl, R.string.source_url))
            add(EditEntity("bookSourceName", bs.bookSourceName, R.string.source_name))
            add(EditEntity("bookSourceGroup", bs.bookSourceGroup, R.string.source_group))
            add(EditEntity("bookSourceComment", bs.bookSourceComment, R.string.comment))
            add(EditEntity("loginUrl", bs.loginUrl, R.string.login_url))
            add(EditEntity("loginUi", bs.loginUi, R.string.login_ui))
            add(EditEntity("loginCheckJs", bs.loginCheckJs, R.string.login_check_js))
            add(EditEntity("coverDecodeJs", bs.coverDecodeJs, R.string.cover_decode_js))
            add(EditEntity("bookUrlPattern", bs.bookUrlPattern, R.string.book_url_pattern))
            add(EditEntity("header", bs.header, R.string.source_http_header))
            add(EditEntity("variableComment", bs.variableComment, R.string.variable_comment))
            add(EditEntity("concurrentRate", bs.concurrentRate, R.string.concurrent_rate))
            add(EditEntity("jsLib", bs.jsLib, "jsLib"))
        }
        // 搜索
        val sr = bs.searchRule
        searchEntities.clear()
        searchEntities.apply {
            add(EditEntity("searchUrl", bs.searchUrl, R.string.r_search_url))
            add(EditEntity("checkKeyWord", sr.checkKeyWord, R.string.check_key_word))
            add(EditEntity("bookList", sr.bookList, R.string.r_book_list))
            add(EditEntity("name", sr.name, R.string.r_book_name))
            add(EditEntity("author", sr.author, R.string.r_author))
            add(EditEntity("kind", sr.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", sr.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", sr.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", sr.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", sr.coverUrl, R.string.rule_cover_url))
            add(EditEntity("bookUrl", sr.bookUrl, R.string.r_book_url))
            add(EditEntity("hasMoreRule", sr.hasMoreRule, R.string.rule_has_more))
        }
        // 发现
        val er = bs.exploreRule
        exploreEntities.clear()
        exploreEntities.apply {
            add(EditEntity("exploreUrl", bs.exploreUrl, R.string.r_find_url))
            add(EditEntity("bookList", er.bookList, R.string.r_book_list))
            add(EditEntity("name", er.name, R.string.r_book_name))
            add(EditEntity("author", er.author, R.string.r_author))
            add(EditEntity("kind", er.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", er.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", er.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", er.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", er.coverUrl, R.string.rule_cover_url))
            add(EditEntity("bookUrl", er.bookUrl, R.string.r_book_url))
            add(EditEntity("hasMoreRule", er.hasMoreRule, R.string.rule_has_more))
        }
        // 详情页
        val ir = bs.bookInfoRule
        infoEntities.clear()
        infoEntities.apply {
            add(EditEntity("init", ir.init, R.string.rule_book_info_init))
            add(EditEntity("name", ir.name, R.string.r_book_name))
            add(EditEntity("author", ir.author, R.string.r_author))
            add(EditEntity("kind", ir.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", ir.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", ir.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", ir.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", ir.coverUrl, R.string.rule_cover_url))
            add(EditEntity("tocUrl", ir.tocUrl, R.string.rule_toc_url))
            add(EditEntity("canReName", ir.canReName, R.string.rule_can_re_name))
            add(EditEntity("downloadUrls", ir.downloadUrls, R.string.download_url_rule))
        }
        // 目录页
        val tr = bs.tocRule
        tocEntities.clear()
        tocEntities.apply {
            add(EditEntity("preUpdateJs", tr.preUpdateJs, R.string.pre_update_js))
            add(EditEntity("chapterList", tr.chapterList, R.string.rule_chapter_list))
            add(EditEntity("chapterName", tr.chapterName, R.string.rule_chapter_name))
            add(EditEntity("chapterUrl", tr.chapterUrl, R.string.rule_chapter_url))
            add(EditEntity("isVolume", tr.isVolume, R.string.rule_is_volume))
            add(EditEntity("updateTime", tr.updateTime, R.string.rule_update_time))
            add(EditEntity("isVip", tr.isVip, R.string.rule_is_vip))
            add(EditEntity("isPay", tr.isPay, R.string.rule_is_pay))
            add(EditEntity("nextTocUrl", tr.nextTocUrl, R.string.rule_next_toc_url))
        }
        // 正文页
        val cr = bs.contentRule
        contentEntities.clear()
        contentEntities.apply {
            add(EditEntity("content", cr.content, R.string.rule_book_content))
            add(EditEntity("subContent", cr.subContent, R.string.rule_sub_content))
            add(EditEntity("title", cr.title, R.string.rule_chapter_name))
            add(EditEntity("nextContentUrl", cr.nextContentUrl, R.string.rule_next_content))
            add(
                EditEntity(
                    "shouldOverrideUrlLoading",
                    cr.shouldOverrideUrlLoading,
                    R.string.rule_should_override_url_loading
                )
            )
            add(EditEntity("webJs", cr.webJs, R.string.rule_web_js))
            add(EditEntity("sourceRegex", cr.sourceRegex, R.string.rule_source_regex))
            add(EditEntity("replaceRegex", cr.replaceRegex, R.string.rule_replace_regex))
            add(
                EditEntity(
                    "imageStyle",
                    cr.imageStyle,
                    R.string.rule_image_style,
                    EditEntity.ViewType.spinner,
                    imageStyleSelections
                )
            )
            add(EditEntity("imageDecode", cr.imageDecode, R.string.rule_image_decode))
            add(EditEntity("payAction", cr.payAction, R.string.rule_pay_action))
            add(EditEntity("musicCover", cr.musicCover, R.string.rule_music_cover))
        }
        // 段评
        val rr = bs.reviewRule
        reviewEntities.clear()
        reviewEntities.apply {
            add(EditEntity("reviewCountRule", rr.reviewCountRule, R.string.rule_review_count))
            add(EditEntity("totalCountRule", rr.totalCountRule, R.string.rule_review_total_count))
            add(EditEntity("reviewUrl", rr.reviewUrl, R.string.rule_review_url))
            add(EditEntity("reviewList", rr.reviewList, R.string.rule_review_list))
            add(EditEntity("hasMoreRule", rr.hasMoreRule, R.string.rule_has_more))
            add(EditEntity("reviewIdRule", rr.reviewIdRule, R.string.rule_review_id))
            add(EditEntity("avatarRule", rr.avatarRule, R.string.rule_avatar))
            add(EditEntity("nameRule", rr.nameRule, R.string.rule_review_name))
            add(EditEntity("contentRule", rr.contentRule, R.string.rule_review_content))
            add(EditEntity("postTimeRule", rr.postTimeRule, R.string.rule_post_time))
            add(EditEntity("extraRule", rr.extraRule, R.string.rule_review_extra))
            add(EditEntity("imagesRule", rr.imagesRule, R.string.rule_review_images))
            add(EditEntity("voteUpCountRule", rr.voteUpCountRule, R.string.rule_vote_up_count))
            add(EditEntity("voteUpSelectedRule", rr.voteUpSelectedRule, R.string.rule_vote_up_selected))
            add(EditEntity("voteDownSelectedRule", rr.voteDownSelectedRule, R.string.rule_vote_down_selected))
            add(EditEntity("replyCountRule", rr.replyCountRule, R.string.rule_reply_count))
            add(EditEntity("replyListUrl", rr.replyListUrl, R.string.rule_reply_list_url))
            add(EditEntity("voteUpRule", rr.voteUpRule, R.string.rule_vote_up))
            add(EditEntity("voteDownRule", rr.voteDownRule, R.string.rule_vote_down))
            add(EditEntity("replyRule", rr.replyRule, R.string.rule_reply))
            add(EditEntity("deleteRule", rr.deleteRule, R.string.rule_delete_review))
        }
        editState.currentTab = 0
        editState.sourceVersion++
    }

    fun getSource(): BookSource {
        val source = viewModel.bookSource?.copy() ?: BookSource()
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

    // ===== CodeView / 键盘辅助条桥接 =====

    /** 构造单个规则输入行（TextInputLayout + CodeView），对齐旧 Adapter 的动态构造与整树着色 */
    fun createEditField(ctx: Context, entity: EditEntity): TextInputLayout {
        val root = TextInputLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, ctx.space.xs, 0, 0)
            hint = entity.hint
        }
        val editText = CodeView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(editText)
        editText.isLineNumberEnabled = true
        editText.addLegadoPattern()
        editText.addJsPattern()
        editText.addJsonPattern()
        editText.maxLines = editEntityMaxLine
        editText.onSearchReplaceAction = { toolbarState.showFindReplace(it) }
        editText.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) onCodeViewFocus(v as CodeView)
        }
        editText.setText(entity.value)
        // 视图单实例常驻，无 RecyclerView 复用，直接同步写回，不再需要 500ms 防抖与 detach flush
        editText.doAfterTextChanged { entity.value = it?.toString() }
        // 动态构造的 View 不走 Factory2，整树着色
        root.applyThemeTree()
        return root
    }

    private fun onCodeViewFocus(codeView: CodeView) {
        if (lastActiveCodeView != codeView) {
            lastActiveCodeView?.clearSearch()
        }
        lastActiveCodeView = codeView
    }

    fun getActiveCodeView(): CodeView? {
        val view = window.decorView.findFocus()
            ?: LifecycleHelp.currentActivity?.window?.decorView?.findFocus()
        if (view is CodeView) lastActiveCodeView = view
        return lastActiveCodeView
    }

    fun undo() {
        getActiveCodeView()?.undo()
    }

    fun redo() {
        getActiveCodeView()?.redo()
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        when (toolbarState.panelFocus) {
            1 -> toolbarState.findText = toolbarState.findText.insertAtCursor(text)
            2 -> toolbarState.replaceText = toolbarState.replaceText.insertAtCursor(text)
            else -> {
                var view = window.decorView.findFocus()
                if (view == null) {
                    view = LifecycleHelp.currentActivity?.window?.decorView?.findFocus()
                }
                if (view is EditText) {
                    val start = view.selectionStart
                    val end = view.selectionEnd
                    val edit = view.editableText
                    if ((start < 0) || (start >= edit.length)) {
                        edit.append(text)
                    } else if (start > end) {
                        edit.replace(end, start, text)
                    } else {
                        edit.replace(start, end, text)
                    }
                }
            }
        }
    }

    private fun bookSourceTypeToIndex(type: Int): Int = when (type) {
        BookSourceType.rss -> 5
        BookSourceType.video -> 4
        BookSourceType.file -> 3
        BookSourceType.image -> 2
        BookSourceType.audio -> 1
        else -> 0
    }

    private fun indexToBookSourceType(index: Int): Int = when (index) {
        5 -> BookSourceType.rss
        4 -> BookSourceType.video
        3 -> BookSourceType.file
        2 -> BookSourceType.image
        1 -> BookSourceType.audio
        else -> BookSourceType.default
    }

}
