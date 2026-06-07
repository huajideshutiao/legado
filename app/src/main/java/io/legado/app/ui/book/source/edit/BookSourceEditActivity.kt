package io.legado.app.ui.book.source.edit

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.databinding.ActivityBookSourceEditBinding
import io.legado.app.help.IntentData
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.negativeButton
import io.legado.app.lib.dialogs.onCancelled
import io.legado.app.lib.dialogs.positiveButton
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.SharedJsScope
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.dialog.UrlOptionDialog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.ui.widget.recycler.NoChildScrollGridLayoutManager
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.share
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding

class BookSourceEditActivity :
    VMBaseActivity<ActivityBookSourceEditBinding, BookSourceEditViewModel>(),
    KeyboardToolPop.CallBack {

    override val binding by viewBinding(ActivityBookSourceEditBinding::inflate)
    override val viewModel by viewModels<BookSourceEditViewModel>()

    private val adapter by lazy { BookSourceEditAdapter() }
    private val sourceEntities: ArrayList<EditEntity> = ArrayList()
    private val searchEntities: ArrayList<EditEntity> = ArrayList()
    private val exploreEntities: ArrayList<EditEntity> = ArrayList()
    private val infoEntities: ArrayList<EditEntity> = ArrayList()
    private val tocEntities: ArrayList<EditEntity> = ArrayList()
    private val contentEntities: ArrayList<EditEntity> = ArrayList()
    private val reviewEntities: ArrayList<EditEntity> = ArrayList()
    private val bookSourceTypeSelections by lazy {
        val labels = resources.getStringArray(R.array.book_type)
        labels.mapIndexed { index, label -> label to index.toString() }
    }
    private val exploreStyleSelections by lazy {
        val labels = resources.getStringArray(R.array.explore_style)
        labels.mapIndexed { index, label -> label to index.toString() }
    }
    private val imageStyleSelections by lazy {
        listOf(
            getString(R.string.text_default) to null,
            Book.imgStyleFull to Book.imgStyleFull,
            Book.imgStyleText to Book.imgStyleText,
            Book.imgStyleSingle to Book.imgStyleSingle
        )
    }
    private val selectDoc = registerHandleFile { result ->
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                sendText(uri.toString())
            } else {
                sendText(uri.path.toString())
            }
        }
    }

    private var lastActiveCodeView: CodeView? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
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

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.source_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = getSource().hasLogin() == true
        menu.findItem(R.id.menu_auto_complete)?.isChecked = viewModel.autoComplete
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> viewModel.save(getSource()) {
                IntentData.source = it
                setResult(RESULT_OK)
                finish()
            }

            R.id.menu_debug_source -> viewModel.save(getSource()) { source ->
                startActivity<BookSourceDebugActivity> {
                    IntentData.source = source
                }
            }

            R.id.menu_clear_cookie -> viewModel.clearCookie(getSource().bookSourceUrl)
            R.id.menu_auto_complete -> viewModel.autoComplete = !viewModel.autoComplete
            R.id.menu_copy_source -> sendToClip(GSON.toJson(getSource()))
            R.id.menu_paste_source -> viewModel.pasteSource { upSourceView(it) }
            R.id.menu_share_str -> share(GSON.toJson(getSource()))

            R.id.menu_help -> showHelp("ruleHelp")
            R.id.menu_login -> viewModel.save(getSource()) { source ->
                source.showLoginDialog(this)
            }

            R.id.menu_set_source_variable -> viewModel.save(getSource()) { source ->
                source.showSourceVariableDialog(this)
            }

            R.id.menu_search -> viewModel.save(getSource()) { source ->
                startActivity<SearchActivity> {
                    putExtra("searchScope", SearchScope(source).toString())
                }
            }

        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_base)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_search)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_find)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_info)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_toc)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_content)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_review)
        })
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        val gridLayoutManager = NoChildScrollGridLayoutManager(this, 2)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return adapter.editEntities.getOrNull(position)?.span ?: 2
            }
        }
        binding.recyclerView.layoutManager = gridLayoutManager
        binding.keyboardTool.setInterface(binding.root, this)
        adapter.onSearchReplaceAction = { binding.keyboardTool.showFindReplace(it) }
        adapter.onCodeViewFocus = { codeView ->
            if (lastActiveCodeView != codeView) {
                lastActiveCodeView?.clearSearch()
            }
            lastActiveCodeView = codeView
        }
        adapter.onCheckedChange = { entity, isChecked, btn ->
            if (entity.key == "enableDangerousApi") {
                val originalEnabled = viewModel.bookSource?.enableDangerousApi == true
                if (isChecked != originalEnabled) {
                    SharedJsScope.remove(viewModel.bookSource?.jsLib)
                }
                if (isChecked) {
                    alert(R.string.enable_dangerous_api) {
                        setMessage(R.string.enable_dangerous_api_confirm)
                        positiveButton(R.string.ok)
                        negativeButton(R.string.cancel) {
                            btn.isChecked = false
                        }
                        onCancelled {
                            btn.isChecked = false
                        }
                    }
                }
            }
        }
        binding.recyclerView.adapter = adapter
        binding.tabLayout.setSelectedTabIndicatorColor(accentColor)
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab?) {

            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {

            }

            override fun onTabSelected(tab: TabLayout.Tab?) {
                setEditEntities(tab?.position)
            }
        })
        binding.recyclerView.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val navigationBarHeight = windowInsets.navigationBarHeight
            view.bottomPadding = navigationBarHeight
            binding.keyboardTool.bottomPadding = navigationBarHeight
            binding.keyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
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

    private fun setEditEntities(tabPosition: Int?) {
        adapter.editEntities = when (tabPosition) {
            1 -> searchEntities
            2 -> exploreEntities
            3 -> infoEntities
            4 -> tocEntities
            5 -> contentEntities
            6 -> reviewEntities
            else -> sourceEntities
        }
        binding.recyclerView.scrollToPosition(0)
    }

    private fun upSourceView(bookSource: BookSource?) {
        val bs = bookSource ?: BookSource()
        // 基本信息
        sourceEntities.clear()
        sourceEntities.apply {
            add(
                EditEntity(
                    "bookSourceType",
                    bookSourceTypeToIndex(bs.bookSourceType).toString(),
                    R.string.book_type,
                    EditEntity.ViewType.spinner,
                    bookSourceTypeSelections,
                    span = 1
                )
            )
            add(
                EditEntity(
                    "enabled",
                    bs.enabled.toString(),
                    R.string.is_enable,
                    EditEntity.ViewType.checkBox,
                    span = 1
                )
            )
            add(
                EditEntity(
                    "enabledCookieJar",
                    (bs.enabledCookieJar ?: false).toString(),
                    R.string.auto_save_cookie,
                    EditEntity.ViewType.checkBox,
                    span = 1
                )
            )
            add(
                EditEntity(
                    "enableDangerousApi",
                    (bs.enableDangerousApi ?: false).toString(),
                    R.string.enable_dangerous_api,
                    EditEntity.ViewType.checkBox,
                    span = 1
                )
            )
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
        val sr = bs.getSearchRule()
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
        val er = bs.getExploreRule()
        exploreEntities.clear()
        exploreEntities.apply {
            add(
                EditEntity(
                    "enabledExplore",
                    bs.enabledExplore.toString(),
                    R.string.discovery,
                    EditEntity.ViewType.checkBox,
                    span = 1
                )
            )
            add(
                EditEntity(
                    "exploreStyle",
                    bs.exploreStyle.coerceIn(0, 3).toString(),
                    R.string.explore_style,
                    EditEntity.ViewType.spinner,
                    exploreStyleSelections,
                    span = 1
                )
            )
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
        val ir = bs.getBookInfoRule()
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
        val tr = bs.getTocRule()
        tocEntities.clear()
        tocEntities.apply {
            add(EditEntity("preUpdateJs", tr.preUpdateJs, R.string.pre_update_js))
            add(EditEntity("chapterList", tr.chapterList, R.string.rule_chapter_list))
            add(EditEntity("chapterName", tr.chapterName, R.string.rule_chapter_name))
            add(EditEntity("chapterUrl", tr.chapterUrl, R.string.rule_chapter_url))
            add(EditEntity("formatJs", tr.formatJs, R.string.format_js_rule))
            add(EditEntity("isVolume", tr.isVolume, R.string.rule_is_volume))
            add(EditEntity("updateTime", tr.updateTime, R.string.rule_update_time))
            add(EditEntity("isVip", tr.isVip, R.string.rule_is_vip))
            add(EditEntity("isPay", tr.isPay, R.string.rule_is_pay))
            add(EditEntity("nextTocUrl", tr.nextTocUrl, R.string.rule_next_toc_url))
        }
        // 正文页
        val cr = bs.getContentRule()
        contentEntities.clear()
        contentEntities.apply {
            add(EditEntity("content", cr.content, R.string.rule_book_content))
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
            add(EditEntity("lrcRule", cr.lrcRule, R.string.rule_lrc_rule))
            add(EditEntity("musicCover", cr.musicCover, R.string.rule_music_cover))
        }
        // 段评
        val rr = bs.getReviewRule()
        reviewEntities.clear()
        reviewEntities.apply {
            add(
                EditEntity(
                    "enabledReview",
                    bs.enabledReview.toString(),
                    R.string.enable_review,
                    EditEntity.ViewType.checkBox
                )
            )
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
            add(EditEntity("replyCountRule", rr.replyCountRule, R.string.rule_reply_count))
            add(EditEntity("replyListUrl", rr.replyListUrl, R.string.rule_reply_list_url))
            add(EditEntity("voteUpRule", rr.voteUpRule, R.string.rule_vote_up))
            add(EditEntity("voteDownRule", rr.voteDownRule, R.string.rule_vote_down))
            add(EditEntity("replyRule", rr.replyRule, R.string.rule_reply))
            add(EditEntity("deleteRule", rr.deleteRule, R.string.rule_delete_review))
        }
        binding.tabLayout.selectTab(binding.tabLayout.getTabAt(0))
        setEditEntities(0)
    }

    private fun getSource(): BookSource {
        val source = viewModel.bookSource?.copy() ?: BookSource()
        val searchRule = SearchRule()
        val exploreRule = ExploreRule()
        val bookInfoRule = BookInfoRule()
        val tocRule = TocRule()
        val contentRule = ContentRule()
        val reviewRule = ReviewRule()
        sourceEntities.forEach {
            when (it.key) {
                "bookSourceType" -> source.bookSourceType = indexToBookSourceType(it.intValue)
                "enabled" -> source.enabled = it.boolValue
                "enabledCookieJar" -> source.enabledCookieJar = it.boolValue
                "enableDangerousApi" -> source.enableDangerousApi = it.boolValue
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
                "enabledExplore" -> source.enabledExplore = it.boolValue
                "exploreStyle" -> source.exploreStyle = it.intValue.coerceIn(0, 3)
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
                "formatJs" -> tocRule.formatJs = it.text
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
                "lrcRule" -> contentRule.lrcRule = it.text
                "musicCover" -> contentRule.musicCover = it.text
            }
        }
        reviewEntities.forEach {
            when (it.key) {
                "enabledReview" -> source.enabledReview = it.boolValue
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
                "replyCountRule" -> reviewRule.replyCountRule = it.text
                "replyListUrl" -> reviewRule.replyListUrl = it.text
                "voteUpRule" -> reviewRule.voteUpRule = it.text
                "voteDownRule" -> reviewRule.voteDownRule = it.text
                "replyRule" -> reviewRule.replyRule = it.text
                "deleteRule" -> reviewRule.deleteRule = it.text
            }
        }
        source.ruleSearch = searchRule
        source.ruleExplore = exploreRule
        source.ruleBookInfo = bookInfoRule
        source.ruleToc = tocRule
        source.ruleContent = contentRule
        source.ruleReview = reviewRule
        return source
    }

    private fun alertGroups() {
        lifecycleScope.launch {
            val groups = withContext(IO) {
                appDb.bookSourceDao.allGroups()
            }
            selector(groups) { _, s, _ ->
                sendText(s)
            }
        }
    }

    override fun helpActions(): List<SelectItem<String>> {
        val helpActions = arrayListOf(
            SelectItem("插入URL参数", "urlOption"),
            SelectItem("书源教程", "ruleHelp"),
            SelectItem("js教程", "jsHelp"),
            SelectItem("正则教程", "regexHelp"),
        )
        val view = window.decorView.findFocus()
        if (view is EditText) {
            when (view.getTag(R.id.tag)) {
                "bookSourceGroup" -> {
                    helpActions.add(
                        SelectItem("插入分组", "addGroup")
                    )
                }

                else -> {
                    helpActions.add(
                        SelectItem("选择文件", "selectFile")
                    )
                }
            }
        }
        return helpActions
    }

    override fun getActiveCodeView(): CodeView? {
        val view = window.decorView.findFocus()
        if (view is CodeView) {
            lastActiveCodeView = view
        }
        return lastActiveCodeView
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "addGroup" -> alertGroups()
            "urlOption" -> UrlOptionDialog.show(supportFragmentManager) { sendText(it) }
            "ruleHelp" -> showHelp("ruleHelp")
            "jsHelp" -> showHelp("jsHelp")
            "regexHelp" -> showHelp("regexHelp")
            "selectFile" -> selectDoc.launch {
                mode = HandleFileContract.FILE
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

    override fun sendText(text: String) {
        if (text.isBlank()) return
        val view = window.decorView.findFocus()
        if (view is EditText) {
            val start = view.selectionStart
            val end = view.selectionEnd
            val edit = view.editableText//获取EditText的文字
            if (start < 0 || start >= edit.length) {
                edit.append(text)
            } else if (start > end) {
                edit.replace(end, start, text)
            } else {
                edit.replace(start, end, text)//光标所在位置插入文字
            }
        }
    }

}
