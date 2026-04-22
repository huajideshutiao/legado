package io.legado.app.ui.book.source.edit

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.databinding.ActivityBookSourceEditBinding
import io.legado.app.help.IntentData
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.SharedJsScope
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.dialog.UrlOptionDialog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.ui.widget.recycler.NoChildScrollLinearLayoutManager
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

    //    private val reviewEntities: ArrayList<EditEntity> = ArrayList()
    private val selectDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
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
        menu.findItem(R.id.menu_login)?.isVisible = !getSource().loginUrl.isNullOrBlank()
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
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = NoChildScrollLinearLayoutManager(this)
        binding.keyboardTool.setInterface(binding.root, this)
        adapter.onSearchReplaceAction = { binding.keyboardTool.showFindReplace(it) }
        adapter.onCodeViewFocus = { codeView ->
            if (lastActiveCodeView != codeView) {
                lastActiveCodeView?.clearSearch()
            }
            lastActiveCodeView = codeView
        }
        binding.recyclerView.adapter = adapter
        binding.tabLayout.setBackgroundColor(backgroundColor)
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
//            6 -> reviewEntities
            else -> sourceEntities
        }
        binding.recyclerView.scrollToPosition(0)
    }

    private fun upSourceView(bookSource: BookSource?) {
        val bs = bookSource ?: BookSource()
        bs.let {
            binding.cbIsEnable.isChecked = it.enabled
            binding.cbIsEnableExplore.isChecked = it.enabledExplore
            binding.cbIsEnableCookie.isChecked = it.enabledCookieJar ?: false
            binding.cbIsEnableDangerousApi.isChecked = it.enableDangerousApi ?: false
            binding.spType.setSelection(
                when (it.bookSourceType) {
                    BookSourceType.rss -> 5
                    BookSourceType.video -> 4
                    BookSourceType.file -> 3
                    BookSourceType.image -> 2
                    BookSourceType.audio -> 1
                    else -> 0
                }
            )
            binding.cbIsEnableDangerousApi.setOnCheckedChangeListener { btn, isChecked ->
                if (isChecked != it.enableDangerousApi) {
                    SharedJsScope.remove(it.jsLib)
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
        }
        // 发现
        val er = bs.getExploreRule()
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
            add(EditEntity("imageStyle", cr.imageStyle, R.string.rule_image_style))
            add(EditEntity("imageDecode", cr.imageDecode, R.string.rule_image_decode))
            add(EditEntity("payAction", cr.payAction, R.string.rule_pay_action))
            add(EditEntity("lrcRule", cr.lrcRule, R.string.rule_lrc_rule))
            add(EditEntity("musicCover", cr.musicCover, R.string.rule_music_cover))
        }
        binding.tabLayout.selectTab(binding.tabLayout.getTabAt(0))
        setEditEntities(0)
    }

    private fun getSource(): BookSource {
        val source = viewModel.bookSource?.copy() ?: BookSource()
        source.enabled = binding.cbIsEnable.isChecked
        source.enabledExplore = binding.cbIsEnableExplore.isChecked
        source.enabledCookieJar = binding.cbIsEnableCookie.isChecked
        source.enableDangerousApi = binding.cbIsEnableDangerousApi.isChecked
        source.bookSourceType = when (binding.spType.selectedItemPosition) {
            5 -> BookSourceType.rss
            4 -> BookSourceType.video
            3 -> BookSourceType.file
            2 -> BookSourceType.image
            1 -> BookSourceType.audio
            else -> BookSourceType.default
        }
        val searchRule = SearchRule()
        val exploreRule = ExploreRule()
        val bookInfoRule = BookInfoRule()
        val tocRule = TocRule()
        val contentRule = ContentRule()
//        val reviewRule = ReviewRule()
        sourceEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "bookSourceUrl" -> source.bookSourceUrl = it.value ?: ""
                "bookSourceName" -> source.bookSourceName = it.value ?: ""
                "bookSourceGroup" -> source.bookSourceGroup = it.value
                "loginUrl" -> source.loginUrl = it.value
                "loginUi" -> source.loginUi = it.value
                "loginCheckJs" -> source.loginCheckJs = it.value
                "coverDecodeJs" -> source.coverDecodeJs = it.value
                "bookUrlPattern" -> source.bookUrlPattern = it.value
                "header" -> source.header = it.value
                "bookSourceComment" -> source.bookSourceComment = it.value
                "concurrentRate" -> source.concurrentRate = it.value
                "variableComment" -> source.variableComment = it.value
                "jsLib" -> source.jsLib = it.value
            }
        }
        searchEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "searchUrl" -> source.searchUrl = it.value
                "checkKeyWord" -> searchRule.checkKeyWord = it.value
                "bookList" -> searchRule.bookList = it.value
                "name" -> searchRule.name = it.value
                "author" -> searchRule.author = it.value
                "kind" -> searchRule.kind = it.value
                "intro" -> searchRule.intro = it.value
                "wordCount" -> searchRule.wordCount = it.value
                "lastChapter" -> searchRule.lastChapter = it.value
                "coverUrl" -> searchRule.coverUrl = it.value
                "bookUrl" -> searchRule.bookUrl = it.value
            }
        }
        exploreEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "exploreUrl" -> source.exploreUrl = it.value
                "bookList" -> exploreRule.bookList = it.value
                "name" -> exploreRule.name = it.value
                "author" -> exploreRule.author = it.value
                "kind" -> exploreRule.kind = it.value
                "intro" -> exploreRule.intro = it.value
                "wordCount" -> exploreRule.wordCount = it.value
                "lastChapter" -> exploreRule.lastChapter = it.value
                "coverUrl" -> exploreRule.coverUrl = it.value
                "bookUrl" -> exploreRule.bookUrl = it.value
            }
        }
        infoEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "init" -> bookInfoRule.init = it.value
                "name" -> bookInfoRule.name = it.value
                "author" -> bookInfoRule.author = it.value
                "kind" -> bookInfoRule.kind = it.value
                "intro" -> bookInfoRule.intro = it.value
                "wordCount" -> bookInfoRule.wordCount = it.value
                "lastChapter" -> bookInfoRule.lastChapter = it.value
                "coverUrl" -> bookInfoRule.coverUrl = it.value
                "tocUrl" -> bookInfoRule.tocUrl = it.value
                "canReName" -> bookInfoRule.canReName = it.value
                "downloadUrls" -> bookInfoRule.downloadUrls = it.value
            }
        }
        tocEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "preUpdateJs" -> tocRule.preUpdateJs = it.value
                "chapterList" -> tocRule.chapterList = it.value
                "chapterName" -> tocRule.chapterName = it.value
                "chapterUrl" -> tocRule.chapterUrl = it.value
                "formatJs" -> tocRule.formatJs = it.value
                "isVolume" -> tocRule.isVolume = it.value
                "updateTime" -> tocRule.updateTime = it.value
                "isVip" -> tocRule.isVip = it.value
                "isPay" -> tocRule.isPay = it.value
                "nextTocUrl" -> tocRule.nextTocUrl = it.value
            }
        }
        contentEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "content" -> contentRule.content = it.value
                "title" -> contentRule.title = it.value
                "nextContentUrl" -> contentRule.nextContentUrl = it.value
                "shouldOverrideUrlLoading" -> contentRule.shouldOverrideUrlLoading = it.value
                "webJs" -> contentRule.webJs = it.value
                "sourceRegex" -> contentRule.sourceRegex = it.value
                "replaceRegex" -> contentRule.replaceRegex = it.value
                "imageStyle" -> contentRule.imageStyle = it.value
                "imageDecode" -> contentRule.imageDecode = it.value
                "payAction" -> contentRule.payAction = it.value
                "lrcRule" -> contentRule.lrcRule = it.value
                "musicCover" -> contentRule.musicCover = it.value
            }
        }
        source.ruleSearch = searchRule
        source.ruleExplore = exploreRule
        source.ruleBookInfo = bookInfoRule
        source.ruleToc = tocRule
        source.ruleContent = contentRule
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
            "urlOption" -> UrlOptionDialog(this) { sendText(it) }.show()
            "ruleHelp" -> showHelp("ruleHelp")
            "jsHelp" -> showHelp("jsHelp")
            "regexHelp" -> showHelp("regexHelp")
            "selectFile" -> selectDoc.launch {
                mode = HandleFileContract.FILE
            }
        }
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
