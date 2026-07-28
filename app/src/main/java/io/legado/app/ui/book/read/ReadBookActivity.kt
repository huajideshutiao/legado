package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Xml
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.ComposeDialog
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.IntentData
import io.legado.app.help.TTS
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.delete
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.migrateTo
import io.legado.app.help.book.removeType
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.searchResultList
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.fileBook.FileBook.getHandler
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.receiver.TimeBatteryReceiver
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.changesource.ChangeChapterSourceDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.read.config.AutoReadDialog
import io.legado.app.ui.book.read.config.MoreConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.config.ReadStyleDialog
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.searchContent.SearchContentActivity
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.dict.DictDialog
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.login.showLoginDialog
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.ui.widget.PopupAction
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.LogUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.dismissDialogFragment
import io.legado.app.utils.invisible
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isNetworkAvailable
import io.legado.app.utils.isTrue
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.sysScreenOffTime
import io.legado.app.utils.throttle
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * 阅读界面
 */
class ReadBookActivity : BaseReadBookActivity(),
    View.OnTouchListener,
    ReadView.CallBack,
    TextActionMenu.CallBack,
    ContentTextView.CallBack,
    ReadBookCallback,
    ChangeBookSourceDialog.CallBack,
    ChangeChapterSourceDialog.CallBack,
    ReadBook.CallBack,
    TxtTocRuleDialog.CallBack,
    LayoutProgressListener {

    // ---- 渲染层：AndroidView 包裹，构造一次，翻页/选区链路全走 View 直调 ----
    private val readView: ReadView by lazy {
        // ReadView 构造签名要求非空 AttributeSet(渲染层零改动)：借编译期 XML 的零属性根标签充当空属性集
        val parser = resources.getXml(R.xml.file_paths)
        while (parser.next() != XmlPullParser.START_TAG) {
            // 推进到 <paths>
        }
        ReadView(this, Xml.asAttributeSet(parser))
    }
    private val cursorLeft: ImageView by lazy {
        ImageView(this).apply {
            setImageResource(io.legado.shared.R.drawable.ic_cursor_left)
            contentDescription = getString(R.string.select_start)
            visibility = View.INVISIBLE
        }
    }
    private val cursorRight: ImageView by lazy {
        ImageView(this).apply {
            setImageResource(io.legado.shared.R.drawable.ic_cursor_right)
            contentDescription = getString(R.string.select_end)
            visibility = View.INVISIBLE
        }
    }
    private val textMenuPosition: View by lazy {
        View(this).apply { visibility = View.INVISIBLE }
    }
    private val renderLayer: FrameLayout by lazy {
        FrameLayout(this).apply {
            addView(
                readView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(textMenuPosition, FrameLayout.LayoutParams(0, 0))
            addView(
                cursorLeft,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(
                cursorRight,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // ---- 菜单层：Compose 状态持有者 ----
    val readMenu: ReadMenu by lazy { ReadMenu(this) }
    val searchMenu: SearchMenu by lazy { SearchMenu(this) }

    override val menuLayoutIsVisible: Boolean
        get() = bottomDialog > 0 || readMenu.isVisible || searchMenu.bottomMenuVisible

    @Composable
    override fun Content() {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { renderLayer }, modifier = Modifier.fillMaxSize())
            ReadMenuOverlay(readMenu)
            SearchMenuOverlay(searchMenu)
            NavigationBarStrip()
        }
    }

    private val tocActivity =
        registerForActivityResult(TocActivityResult()) {
            it?.let {
                viewModel.openChapter(it.first, it.second)
            }
        }
    private val sourceEditActivity =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upBookSource {
                    upMenuView()
                }
            }
        }
    private val replaceActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                viewModel.replaceRuleChanged()
            }
        }
    private val searchContentActivity =
        registerForActivityResult(StartActivityContract(SearchContentActivity::class.java)) {
            val data = it.data ?: return@registerForActivityResult
            val key = data.getLongExtra("key", System.currentTimeMillis())
            val index = data.getIntExtra("index", 0)
            val searchResult = IntentData.get<SearchResult>("searchResult$key")
            val searchResultList = IntentData.get<List<SearchResult>>("searchResultList$key")
            if (searchResult != null && searchResultList != null) {
                viewModel.searchContentQuery = searchResult.query
                searchMenu.upSearchResultList(searchResultList)
                isShowingSearchResult = true
                viewModel.searchResultIndex = index
                searchMenu.updateSearchResultIndex(index)
                searchMenu.selectedSearchResult?.let { currentResult ->
                    ReadBook.saveCurrentBookProgress() //退出全文搜索恢复此时进度
                    skipToSearch(currentResult)
                    showActionMenu()
                }
            }
        }
    private val bookInfoActivity =
        registerForActivityResult(StartActivityContract(BookInfoActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_DELETED)
                super.finish()
            } else {
                ReadBook.loadOrUpContent()
            }
        }
    private val selectImageDir = registerHandleFile {
        it.uri?.let { uri ->
            ACache.get().put(AppConst.imagePathKey, uri.toString())
            viewModel.saveImage(it.value, uri)
        }
    }
    private val keyHandler = ReadBookKeyHandler()
    private val menuHandler = ReadBookMenuHandler()
    private val eventHandler = ReadBookEventHandler()
    private var backupJob: Job? = null
    private var tts: TTS? = null
    val textActionMenu: TextActionMenu by lazy {
        TextActionMenu(this, this)
    }
    private val popupAction: PopupAction by lazy {
        PopupAction()
    }
    override val isInitFinish: Boolean get() = viewModel.isInitFinish
    override val isScroll: Boolean get() = readView.isScroll
    private val isAutoPage get() = readView.isAutoPage
    var isShowingSearchResult = false
    override var isSelectingSearchResult = false
        set(value) {
            field = value && isShowingSearchResult
        }
    private val timeBatteryReceiver = TimeBatteryReceiver()
    private var loadStates: Boolean = false
    override val pageFactory get() = readView.pageFactory
    override val pageDelegate get() = readView.pageDelegate
    override val headerHeight: Int get() = readView.curPage.headerHeight
    private var bookChanged = false
    private var pageChanged = false
    private val executor = ReadBook.executor
    private val upSeekBarThrottle = throttle(200) {
        runOnUiThread {
            upSeekBarProgress()
            readMenu.upSeekBar()
        }
    }

    //恢复跳转前进度对话框的交互结果
    private var confirmRestoreProcess: Boolean? = null
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }
    private var justInitData: Boolean = false
    private var syncDialog: ComposeDialog? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        cursorLeft.setColorFilter(accentColor)
        cursorRight.setColorFilter(accentColor)
        cursorLeft.setOnTouchListener(this)
        cursorRight.setOnTouchListener(this)
        window.setBackgroundDrawable(null)
        eventHandler.upScreenTimeOut()
        ReadBook.register(this)
        onBackPressedDispatcher.addCallback(this) {
            if (isShowingSearchResult) {
                exitSearchMenu()
                restoreLastBookProcess()
                return@addCallback
            }
            //拦截返回供恢复阅读进度
            if (ReadBook.lastBookProgress != null && confirmRestoreProcess != false) {
                restoreLastBookProcess()
                return@addCallback
            }
            if (BaseReadAloudService.isPlay()) {
                ReadAloud.pause(this@ReadBookActivity)
                toastOnUi(R.string.read_aloud_pause)
                return@addCallback
            }
            if (isAutoPage) {
                autoPageStop()
                return@addCallback
            }
            finish()
        }
        viewModel.initData(intent) { applyBookmarkPosition(intent) }
        justInitData = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.initData(intent) { applyBookmarkPosition(intent) }
    }

    private fun applyBookmarkPosition(intent: Intent) {
        val targetIndex = intent.getIntExtra("chapterIndex", -1)
        if (targetIndex < 0) return
        val targetPos = intent.getIntExtra("chapterPos", 0)
        intent.removeExtra("chapterIndex")
        intent.removeExtra("chapterPos")
        if (ReadBook.durChapterIndex != targetIndex || ReadBook.durChapterPos != targetPos) {
            ReadBook.saveCurrentBookProgress()
            viewModel.openChapter(targetIndex, targetPos)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        upSystemUiVisibility()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        upSystemUiVisibility()
        readView.upStatusBar()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        if (!isTopResumedActivity) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        ReadTimeRecorder.start(ReadTimeRecorder.Source.READ_BOOK, ReadBook.book?.name ?: "")
        if (bookChanged) {
            bookChanged = false
            ReadBook.callBack = this
            viewModel.initData(intent)
            justInitData = true
        } else {
            //web端阅读时，app处于阅读界面，本地记录会覆盖web保存的进度，在此处恢复
            ReadBook.webBookProgress?.let {
                ReadBook.setProgress(it)
                ReadBook.webBookProgress = null
            }
        }
        upSystemUiVisibility()
        registerReceiver(timeBatteryReceiver, timeBatteryReceiver.filter)
        readView.upTime()
        eventHandler.screenOffTimerStart()
        // 网络监听，当从无网切换到网络环境时同步进度（注意注册的同时就会收到监听，因此界面激活时无需重复执行同步操作）
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && isNetworkAvailable() && !justInitData && ReadBook.inBookshelf) {
                ReadBook.book?.let { book ->
                    viewModel.syncProgress(
                        book,
                        newProgressAction = { progress -> sureNewProgress(progress) })
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        autoPageStop()
        backupJob?.cancel()
        ReadTimeRecorder.end(ReadTimeRecorder.Source.READ_BOOK)
        ReadBook.saveRead()
        ReadBook.cancelPreDownloadTask()
        unregisterReceiver(timeBatteryReceiver)
        upSystemUiVisibility()
        if (!BuildConfig.DEBUG && ReadBook.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadBook.book?.let { viewModel.syncProgress(it) }
            } else {
                ReadBook.uploadProgress()
            }
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
        justInitData = false
        networkChangedListener.unRegister()
    }

    /** 顶栏菜单动作入口(Compose ReadMenu 调用) */
    fun onTopMenuAction(action: ReadMenuAction) {
        menuHandler.onMenuAction(action)
    }

    /**
     * 按键拦截,显示菜单
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyHandler.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * 鼠标滚轮事件
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (0 != (event.source and InputDevice.SOURCE_CLASS_POINTER)) {
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val axisValue = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                LogUtils.d("onGenericMotionEvent", "axisValue = $axisValue")
                // 获得垂直坐标上的滚动方向
                if (axisValue < 0.0f) { // 滚轮向下滚
                    keyHandler.mouseWheelPage(PageDirection.NEXT)
                } else { // 滚轮向上滚
                    keyHandler.mouseWheelPage(PageDirection.PREV)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * 按键事件
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyHandler.onKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 松开按键事件
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyHandler.onKeyUp(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    /**
     * view触摸,文字选择
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!readView.isTextSelected) {
            return false
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> textActionMenu.dismiss()
            MotionEvent.ACTION_MOVE -> {
                when (v) {
                    cursorLeft -> if (!readView.curPage.getReverseStartCursor()) {
                        readView.curPage.selectStartMove(
                            event.rawX + cursorLeft.width,
                            event.rawY - cursorLeft.height
                        )
                    } else {
                        readView.curPage.selectEndMove(
                            event.rawX - cursorRight.width,
                            event.rawY - cursorRight.height
                        )
                    }

                    cursorRight -> if (readView.curPage.getReverseEndCursor()) {
                        readView.curPage.selectStartMove(
                            event.rawX + cursorLeft.width,
                            event.rawY - cursorLeft.height
                        )
                    } else {
                        readView.curPage.selectEndMove(
                            event.rawX - cursorRight.width,
                            event.rawY - cursorRight.height
                        )
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                readView.curPage.resetReverseCursor()
                showTextActionMenu()
            }
        }
        return true
    }

    /**
     * 更新文字选择开始位置
     */
    override fun upSelectedStart(x: Float, y: Float, top: Float) {
        cursorLeft.x = x - cursorLeft.width
        cursorLeft.y = y
        cursorLeft.visible(true)
        textMenuPosition.x = x
        textMenuPosition.y = top
    }

    /**
     * 更新文字选择结束位置
     */
    override fun upSelectedEnd(x: Float, y: Float) {
        cursorRight.x = x
        cursorRight.y = y
        cursorRight.visible(true)
    }

    /**
     * 取消文字选择
     */
    override fun onCancelSelect() {
        cursorLeft.invisible()
        cursorRight.invisible()
        textActionMenu.dismiss()
        popupAction.dismiss()
        readView.isImageMenuShowing = false
    }

    override fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean {
        return readView.onTouchEvent(event)
    }

    /**
     * 显示文本操作菜单
     */
    override fun showTextActionMenu() {
        textActionMenu.show(
            readView,
            textMenuPosition.x.toInt(),
            textMenuPosition.y.toInt(),
            cursorRight.x.toInt(),
            cursorRight.y.toInt() + cursorRight.height
        )
    }

    /**
     * 当前选择的文本
     */
    override val selectedText: String get() = readView.getSelectText()

    /**
     * 文本选择菜单操作
     */
    override fun onMenuItemSelected(itemId: Int): Boolean {
        when (itemId) {
            R.id.menu_aloud -> when (AppConfig.contentSelectSpeakMod) {
                1 -> lifecycleScope.launch {
                    readView.aloudStartSelect()
                }

                else -> speak(readView.getSelectText())
            }

            R.id.menu_bookmark -> readView.curPage.let {
                val bookmark = it.createBookmark()
                if (bookmark == null) {
                    toastOnUi(R.string.create_bookmark_error)
                } else {
                    showDialogFragment(BookmarkDialog(bookmark))
                }
                return true
            }

            R.id.menu_replace -> {
                val scopes = arrayListOf<String>()
                ReadBook.book?.name?.let {
                    scopes.add(it)
                }
                ReadBook.bookSource?.bookSourceUrl?.let {
                    scopes.add(it)
                }
                val text = selectedText.lineSequence().joinToString("\n") { it.trim() }
                replaceActivity.launch(
                    ReplaceEditActivity.startIntent(
                        this,
                        pattern = text,
                        scope = scopes.joinToString(";")
                    )
                )
                return true
            }

            R.id.menu_search_content -> {
                viewModel.searchContentQuery = selectedText
                openSearchActivity(selectedText)
                return true
            }

            R.id.menu_dict -> {
                showDialogFragment(DictDialog(selectedText))
                return true
            }
        }
        return false
    }

    /**
     * 文本选择菜单操作完成
     */
    override fun onMenuActionFinally() {
        textActionMenu.dismiss()
        readView.cancelSelect()
    }

    private fun speak(text: String) {
        if (tts == null) {
            tts = TTS()
        }
        tts?.speak(text)
    }

    fun upMenuView() {
        eventHandler.handler.post {
            readMenu.upTopMenu()
            readMenu.upBookView()
        }
    }

    private fun loadChapterList(book: Book) {
        ReadBook.upMsg(getString(R.string.toc_updateing))
        viewModel.loadChapterList(book)
    }

    /**
     * 内容加载完成
     */
    override fun contentLoadFinish() {
        if (intent.getBooleanExtra("readAloud", false)) {
            intent.removeExtra("readAloud")
            ReadBook.readAloud()
        }
        loadStates = true
        readView.invalidateTextPage()
    }

    /**
     * 更新内容
     */
    override fun upContent(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) {
        lifecycleScope.launch {
            readView.upContent(relativePosition, resetPageOffset)
            if (relativePosition == 0) {
                upSeekBarProgress()
            }
            loadStates = false
            success?.invoke()
        }
    }

    override suspend fun upContentAwait(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) = withContext(Main.immediate) {
        readView.upContent(relativePosition, resetPageOffset)
        if (relativePosition == 0) {
            upSeekBarProgress()
        }
        loadStates = false
    }

    fun upPageAnim(upRecorder: Boolean = false) {
        lifecycleScope.launch {
            readView.upPageAnim(upRecorder)
        }
    }

    override fun notifyBookChanged() {
        bookChanged = true
        if (!ReadBook.inBookshelf) {
            viewModel.removeFromBookshelf { super.finish() }
        }
    }

    override fun cancelSelect() {
        runOnUiThread {
            readView.cancelSelect()
        }
    }

    /**
     * 页面改变
     */
    override fun pageChanged() {
        pageChanged = true
        readView.onPageChange()
        eventHandler.handler.post {
            upSeekBarProgress()
        }
        executor.execute {
            startBackupJob()
        }
    }

    /**
     * 更新进度条位置
     */
    private fun upSeekBarProgress() {
        val progress = when (AppConfig.progressBarBehavior) {
            "page" -> ReadBook.durPageIndex
            else /* chapter */ -> ReadBook.durChapterIndex
        }
        readMenu.setSeekPage(progress)
    }

    /**
     * 显示菜单
     */
    override fun showMenuBar() {
        readMenu.runMenuIn()
    }

    override val oldBook: Book?
        get() = ReadBook.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (!book.isAudio) {
            viewModel.changeTo(source, book, toc)
        } else {
            ReadAloud.stop(this)
            lifecycleScope.launch {
                withContext(IO) {
                    ReadBook.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    ReadBook.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun replaceContent(content: String) {
        ReadBook.book?.let {
            viewModel.saveContent(it, content)
        }
    }

    override fun showActionMenu() {
        when {
            BaseReadAloudService.isRun -> showReadAloudDialog()
            isAutoPage -> showDialogFragment<AutoReadDialog>()
            isShowingSearchResult -> searchMenu.runMenuIn()
            else -> readMenu.runMenuIn()
        }
    }

    /**
     * 显示朗读菜单
     */
    override fun showReadAloudDialog() {
        showDialogFragment<ReadAloudDialog>()
    }

    /**
     * 自动翻页
     */
    override fun autoPage() {
        ReadAloud.stop(this)
        if (isAutoPage) {
            autoPageStop()
        } else {
            readView.autoPager.start()
            readMenu.autoPage = true
            eventHandler.screenTimeOut = -1L
            eventHandler.screenOffTimerStart()
        }
    }

    override fun autoPageStop() {
        if (isAutoPage) {
            readView.autoPager.stop()
            readMenu.autoPage = false
            dismissDialogFragment<AutoReadDialog>()
            eventHandler.upScreenTimeOut()
        }
    }

    override fun openSourceEditActivity() {
        ReadBook.bookSource?.let {
            IntentData.source = it
            sourceEditActivity.launch {}
        }
    }

    override fun openBookInfoActivity() {
        ReadBook.book?.let {
            bookInfoActivity.launch {
                putExtra("name", it.name)
                putExtra("author", it.author)
                IntentData.book = it
            }
        }
    }

    /**
     * 替换
     */
    override fun openReplaceRule() {
        showDialogFragment<EffectiveReplacesDialog>()
    }

    /**
     * 打开目录
     */
    override fun openChapterList() {
        IntentData.book = ReadBook.book
        IntentData.chapterList = ReadBook.chapterList
        tocActivity.launch("")
    }

    /**
     * 打开搜索界面
     */
    override fun openSearchActivity(searchWord: String?) {
        val book = ReadBook.book ?: return
        searchContentActivity.launch {
            IntentData.book = book
            putExtra("searchWord", searchWord ?: viewModel.searchContentQuery)
            putExtra("searchResultIndex", viewModel.searchResultIndex)
            viewModel.searchResultList?.first()?.let {
                if (it.query == viewModel.searchContentQuery) {
                    IntentData.searchResultList = viewModel.searchResultList
                }
            }
        }
    }

    /**
     * 禁用书源
     */
    override fun disableSource() {
        viewModel.disableSource()
    }

    /**
     * 显示阅读样式配置
     */
    override fun showReadStyle() {
        showDialogFragment<ReadStyleDialog>()
    }

    /**
     * 显示更多设置
     */
    override fun showMoreSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    /**
     * 更新状态栏,导航栏
     */
    override fun upSystemUiVisibility() {
        upSystemUiVisibility(isInMultiWindow, !menuLayoutIsVisible, bottomDialog > 0)
        upNavigationBarColor()
    }

    // 退出全文搜索
    override fun exitSearchMenu() {
        if (isShowingSearchResult) {
            isShowingSearchResult = false
            searchMenu.invisible()
            ReadBook.clearSearchResult()
            readView.cancelSelect(true)
        }
    }

    /* 恢复到 全文搜索/进度条跳转前的位置 */
    private fun restoreLastBookProcess() {
        if (confirmRestoreProcess == true) {
            ReadBook.restoreLastBookProgress()
        } else if (confirmRestoreProcess == null) {
            alert(R.string.draw) {
                setMessage(R.string.restore_last_book_process)
                yesButton {
                    confirmRestoreProcess = true
                    ReadBook.restoreLastBookProgress() //恢复启动全文搜索前的进度
                }
                noButton {
                    ReadBook.lastBookProgress = null
                    confirmRestoreProcess = false
                }
                onCancelled {
                    ReadBook.lastBookProgress = null
                    confirmRestoreProcess = false
                }
            }
        }
    }

    override fun showLogin() {
        ReadBook.bookSource?.let {
            IntentData.book = ReadBook.book
            IntentData.chapter = ReadBook.chapterList?.get(ReadBook.durChapterIndex)
            it.showLoginDialog(this)
        }
    }

    override fun payAction() {
        val book = ReadBook.book ?: return
        if (book.isLocal) return
        val chapter = runBlocking { appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) }
        if (chapter == null) {
            toastOnUi("no chapter")
            return
        }
        alert(R.string.chapter_pay) {
            setMessage(chapter.title)
            yesButton {
                Coroutine.async(lifecycleScope) {
                    val source =
                        ReadBook.bookSource ?: throw NoStackTraceException("no book source")
                    val payAction = source.contentRule.payAction
                    if (payAction.isNullOrBlank()) {
                        throw NoStackTraceException("no pay action")
                    }
                    val analyzeRule = AnalyzeRule(book, source)
                    analyzeRule.coroutineContext = coroutineContext
                    analyzeRule.setBaseUrl(chapter.url)
                    analyzeRule.chapter = chapter
                    analyzeRule.evalJS(payAction).toString()
                }.onSuccess(IO) {
                    if (it.isAbsUrl()) {
                        startActivity<WebViewActivity> {
                            val bookSource = ReadBook.bookSource
                            putExtra("title", getString(R.string.chapter_pay))
                            putExtra("url", it)
                            putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                            putExtra("sourceName", bookSource?.bookSourceName)
                            putExtra("sourceType", bookSource?.getSourceType())
                        }
                    } else if (it.isTrue()) {
                        //购买成功后刷新目录
                        ReadBook.book?.let {
                            ReadBook.curTextChapter = null
                            BookHelp.delContent(book, chapter)
                            loadChapterList(book)
                        }
                    }
                }.onError {
                    AppLog.put("执行购买操作出错\n${it.localizedMessage}", it, true)
                }
            }
            noButton()
        }
    }

    /**
     * 朗读按钮
     */
    override fun onClickReadAloud() {
        autoPageStop()
        when {
            !BaseReadAloudService.isRun -> {
                ReadAloud.upReadAloudClass()
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim) {
                    val pos = readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadBook.readAloud()
                }
            }

            BaseReadAloudService.pause -> {
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    pageChanged = false
                    val pos = readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadAloud.resume(this)
                }
            }

            else -> ReadAloud.pause(this)
        }
    }

    override fun showHelp() {
        showHelp("readMenuHelp")
    }

    override fun onImageClick(src: String, onClick: String) {
        val book = ReadBook.book ?: return
        // 章节列表异步加载时 durChapterIndex 可能越界, 用 getOrNull 兜底避免 IndexOutOfBoundsException
        val chapter = ReadBook.chapterList?.getOrNull(ReadBook.durChapterIndex) ?: runBlocking { appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) }
        Coroutine.async(lifecycleScope) {
            val source =
                ReadBook.bookSource ?: throw NoStackTraceException("no book source")
            val analyzeRule = AnalyzeRule(book, source)
            analyzeRule.coroutineContext = coroutineContext
            analyzeRule.setBaseUrl(chapter!!.url)
            analyzeRule.chapter = chapter
            analyzeRule.evalJS(onClick).toString()
        }.start()
    }

    override fun onReviewClick(chapter: BookChapter, paragraphIndex: Int) {
        val book = ReadBook.book ?: return
        showDialogFragment(ReviewListDialog(book, chapter, paragraphIndex))
    }

    /**
     * 长按图片
     */
    @SuppressLint("RtlHardcoded")
    override fun onImageLongPress(x: Float, y: Float, src: String) {
        readView.isTextSelected = true
        readView.isImageMenuShowing = true
        popupAction.setItems(
            listOf(
                SelectItem(getString(R.string.show), "show"),
                SelectItem(getString(R.string.refresh), "refresh"),
                SelectItem(getString(R.string.action_save), "save"),
                SelectItem(getString(R.string.select_folder), "selectFolder")
            )
        )
        popupAction.onActionClick = {
            when (it) {
                "show" -> showDialogFragment(PhotoDialog(src))
                "refresh" -> viewModel.refreshImage(src)
                "save" -> {
                    val path = ACache.get().getAsString(AppConst.imagePathKey)
                    if (path.isNullOrEmpty()) {
                        selectImageDir.launch {
                            value = src
                        }
                    } else {
                        viewModel.saveImage(src, path.toUri())
                    }
                }

                "selectFolder" -> selectImageDir.launch()
            }
            popupAction.dismiss()
            readView.cancelSelect()
        }
        popupAction.onDismiss = {
            readView.cancelSelect()
        }
        popupAction.show(readView, x.toInt(), y.toInt())
    }

    override fun onTocRegexDialogResult(tocRegex: String) {
        ReadBook.book?.let {
            it.tocUrl = tocRegex
            loadChapterList(it)
        }
    }

    /* 进度条跳转到指定章节 */
    override fun skipToChapter(index: Int) {
        ReadBook.saveCurrentBookProgress() //退出章节跳转恢复此时进度
        viewModel.openChapter(index)
    }

    /* 全文搜索跳转 */
    override fun navigateToSearch(searchResult: SearchResult, index: Int) {
        viewModel.searchResultIndex = index
        skipToSearch(searchResult)
    }

    override fun onMenuShow() {
        readView.autoPager.pause()
    }

    override fun onMenuHide() {
        readView.autoPager.resume()
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upSeekBarThrottle.invoke()
        readView.onLayoutPageCompleted(index, page)
    }

    /* 全文搜索跳转 */
    private fun skipToSearch(searchResult: SearchResult) {
        if (searchResult.chapterIndex != ReadBook.durChapterIndex) {
            viewModel.openChapter(searchResult.chapterIndex) {
                jumpToPosition(searchResult)
            }
        } else {
            jumpToPosition(searchResult)
        }
    }

    private fun jumpToPosition(searchResult: SearchResult) {
        val curTextChapter = ReadBook.curTextChapter ?: return
        searchMenu.updateSearchInfo()
        val pos = viewModel.searchResultPositions(curTextChapter, searchResult)
        ReadBook.skipToPage(pos.pageIndex) {
            isSelectingSearchResult = true
            readView.curPage.selectStartMoveIndex(0, pos.lineIndex, pos.charIndex)
            when (pos.addLine) {
                0 -> readView.curPage.selectEndMoveIndex(
                    0,
                    pos.lineIndex,
                    pos.charIndex + viewModel.searchContentQuery.length - 1
                )

                1 -> readView.curPage.selectEndMoveIndex(
                    0, pos.lineIndex + 1, pos.charIndex2
                )
                //consider change page, jump to scroll position
                -1 -> readView.curPage.selectEndMoveIndex(1, 0, pos.charIndex2)
            }
            readView.isTextSelected = true
            isSelectingSearchResult = false
        }
    }

    override fun addBookmark() {
        val book = ReadBook.book
        val page = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex)
        if (book != null && page != null) {
            val bookmark = Bookmark(bookName = book.name, bookAuthor = book.author).apply {
                chapterIndex = ReadBook.durChapterIndex
                chapterPos = ReadBook.durChapterPos
                chapterName = page.title
                bookText = page.text.trim()
            }
            showDialogFragment(BookmarkDialog(bookmark))
        }
    }

    override fun changeReplaceRuleState() {
        ReadBook.book?.let {
            it.config.useReplaceRule = !it.getUseReplaceRule()
            readMenu.topMenu.enableReplaceChecked = it.getUseReplaceRule()
            viewModel.replaceRuleChanged()
        }
    }

    private fun startBackupJob() {
        backupJob?.cancel()
        backupJob = lifecycleScope.launch(IO) {
            delay(BACKUP_DELAY_MS)
            ReadBook.book?.let {
                AppWebDav.uploadBookProgress(it)
                ensureActive()
                it.update()
                Backup.autoBack(this@ReadBookActivity)
            }
        }
    }

    private fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog = alert(R.string.sync_book_progress_t) {
            setMessage(R.string.cloud_progress_exceeds_current)
            okButton {
                ReadBook.setProgress(progress)
            }
            noButton()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.READ_BOOK)
        }
        tts?.clearTts()
        textActionMenu.dismiss()
        popupAction.dismiss()
        readView.onDestroy()
        ReadBook.unregister(this)
        if (!ReadBook.inBookshelf && !isChangingConfigurations) {
            viewModel.removeFromBookshelf(null)
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    override fun observeLiveBus() {
        eventHandler.observeLiveBus()
    }

    /**
     * 重置黑屏时间
     */
    override fun screenOffTimerStart() {
        eventHandler.screenOffTimerStart()
    }

    inner class ReadBookKeyHandler {
        private val nextPageDebounce by lazy { throttle { keyPage(PageDirection.NEXT) } }
        private val prevPageDebounce by lazy { throttle { keyPage(PageDirection.PREV) } }

        fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val keyCode = event.keyCode
            val action = event.action
            val isDown = action == 0

            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (isDown && !readMenu.canShowMenu) {
                    readMenu.runMenuIn()
                    return true
                }
                if (!isDown && !readMenu.canShowMenu) {
                    readMenu.canShowMenu = true
                    return true
                }
            }
            return false
        }

        fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (menuLayoutIsVisible || event.repeatCount > 0) {
                return false
            }
            when {
                isPrevKey(keyCode) -> {
                    handleKeyPage(PageDirection.PREV)
                    return true
                }

                isNextKey(keyCode) -> {
                    handleKeyPage(PageDirection.NEXT)
                    return true
                }
            }
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeyPage(PageDirection.PREV)) {
                    return true
                }

                KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeyPage(PageDirection.NEXT)) {
                    return true
                }

                KeyEvent.KEYCODE_PAGE_UP -> {
                    handleKeyPage(PageDirection.PREV)
                    return true
                }

                KeyEvent.KEYCODE_PAGE_DOWN -> {
                    handleKeyPage(PageDirection.NEXT)
                    return true
                }

                KeyEvent.KEYCODE_SPACE -> {
                    handleKeyPage(PageDirection.NEXT)
                    return true
                }
            }
            return false
        }

        fun onKeyUp(keyCode: Int): Boolean {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (volumeKeyPage(PageDirection.NONE)) {
                        return true
                    }
                }

            }
            return false
        }

        /**
         * 鼠标滚轮翻页
         */
        fun mouseWheelPage(direction: PageDirection) {
            if (menuLayoutIsVisible || !AppConfig.mouseWheelPage) {
                return
            }
            keyPageDebounce(direction, mouseWheel = true)
        }

        /**
         * 音量键翻页
         */
        private fun volumeKeyPage(direction: PageDirection): Boolean {
            if (!AppConfig.volumeKeyPageOnPlay && BaseReadAloudService.isPlay()) {
                return false
            }
            handleKeyPage(direction)
            return true
        }

        private fun handleKeyPage(direction: PageDirection) {
            if (direction == PageDirection.NONE) {
                keyPage(direction)
            } else {
                keyPageDebounce(direction)
            }
        }

        private fun keyPageDebounce(
            direction: PageDirection,
            mouseWheel: Boolean = false
        ) {
            nextPageDebounce.apply {
                wait = 200L
                maxWait = 200L
                leading = !mouseWheel
                trailing = mouseWheel
            }
            prevPageDebounce.apply {
                wait = 200L
                maxWait = 200L
                leading = !mouseWheel
                trailing = mouseWheel
            }
            when (direction) {
                PageDirection.NEXT -> nextPageDebounce.invoke()
                PageDirection.PREV -> prevPageDebounce.invoke()
                else -> {}
            }
        }

        private fun keyPage(direction: PageDirection) {
            readView.cancelSelect()
            readView.pageDelegate?.isCancel = false
            readView.pageDelegate?.keyTurnPage(direction)
        }
    }

    inner class ReadBookMenuHandler {

        /**
         * 菜单(原 onCompatOptionsItemSelected，MenuItem 改为 ReadMenuAction)
         */
        fun onMenuAction(action: ReadMenuAction) {
            when (action) {
                ReadMenuAction.CHANGE_SOURCE,
                ReadMenuAction.BOOK_CHANGE_SOURCE -> {
                    readMenu.runMenuOut()
                    ReadBook.book?.let {
                        showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                    }
                }

                ReadMenuAction.CHAPTER_CHANGE_SOURCE -> lifecycleScope.launch {
                    val book = ReadBook.book ?: return@launch
                    val chapter =
                        appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                            ?: return@launch
                    readMenu.runMenuOut()
                    showDialogFragment(
                        ChangeChapterSourceDialog(
                            book.name,
                            book.author,
                            chapter.index,
                            chapter.title
                        )
                    )
                }

                ReadMenuAction.REFRESH,
                ReadMenuAction.REFRESH_DUR -> {
                    if (ReadBook.bookSource == null) {
                        upContent()
                    } else {
                        ReadBook.book?.let {
                            ReadBook.curTextChapter = null
                            readView.upContent()
                            viewModel.refreshContentDur(it)
                        }
                    }
                }

                ReadMenuAction.REFRESH_AFTER -> {
                    if (ReadBook.bookSource == null) {
                        upContent()
                    } else {
                        ReadBook.book?.let {
                            ReadBook.clearTextChapter()
                            readView.upContent()
                            viewModel.refreshContentAfter(it)
                        }
                    }
                }

                ReadMenuAction.REFRESH_ALL -> {
                    if (ReadBook.bookSource == null) {
                        upContent()
                    } else {
                        ReadBook.book?.let {
                            refreshContentAll(it)
                        }
                    }
                }

                ReadMenuAction.DOWNLOAD -> showDownloadDialog()
                ReadMenuAction.ADD_BOOKMARK -> addBookmark()
                ReadMenuAction.SIMULATED_READING -> showSimulatedReading()
                ReadMenuAction.EDIT_CONTENT -> showDialogFragment(ContentEditDialog())
                ReadMenuAction.UPDATE_TOC -> ReadBook.book?.let {
                    it.getHandler().clear()
                    if (it.isEpub) BookHelp.clearCache(it)
                    loadChapterList(it)
                }

                ReadMenuAction.ENABLE_REPLACE -> changeReplaceRuleState()
                ReadMenuAction.RE_SEGMENT -> ReadBook.book?.let {
                    it.config.reSegment = !it.config.reSegment
                    readMenu.topMenu.reSegmentChecked = it.config.reSegment
                    ReadBook.loadContent(false)
                }

                ReadMenuAction.DEL_RUBY_TAG -> ReadBook.book?.let {
                    val checked = !readMenu.topMenu.delRubyChecked
                    readMenu.topMenu.delRubyChecked = checked
                    if (checked) {
                        it.config.delTag = it.config.delTag or Book.rubyTag
                    } else {
                        it.config.delTag = it.config.delTag and Book.rubyTag.inv()
                    }
                    refreshContentAll(it)
                }

                ReadMenuAction.DEL_H_TAG -> ReadBook.book?.let {
                    val checked = !readMenu.topMenu.delHChecked
                    readMenu.topMenu.delHChecked = checked
                    if (checked) {
                        it.config.delTag = it.config.delTag or Book.hTag
                    } else {
                        it.config.delTag = it.config.delTag and Book.hTag.inv()
                    }
                    refreshContentAll(it)
                }

                ReadMenuAction.PAGE_ANIM -> showPageAnimConfig {
                    readView.upPageAnim()
                    ReadBook.loadContent(false)
                }

                ReadMenuAction.LOG -> showDialogFragment<AppLogDialog>()
                ReadMenuAction.TOC_REGEX -> showDialogFragment(
                    TxtTocRuleDialog(ReadBook.book?.tocUrl)
                )

                ReadMenuAction.SET_CHARSET -> showCharsetConfig()
                ReadMenuAction.IMAGE_STYLE -> {
                    val imgStyles =
                        arrayListOf(
                            Book.imgStyleDefault, Book.imgStyleFull, Book.imgStyleText,
                            Book.imgStyleSingle
                        )
                    selector(
                        R.string.image_style,
                        imgStyles
                    ) { _, index ->
                        val imageStyle = imgStyles[index]
                        ReadBook.book?.let { it.config.imageStyle = imageStyle }
                        if (imageStyle == Book.imgStyleSingle) {
                            readView.upPageAnim()
                        }
                        ReadBook.loadContent(false)
                    }
                }

                ReadMenuAction.SYNC_PROGRESS -> ReadBook.book?.let { book ->
                    viewModel.syncProgress(
                        book = book,
                        newProgressAction = { progress -> sureNewProgress(progress) },
                        uploadSuccessAction = { toastOnUi(R.string.upload_book_success) },
                        syncSuccessAction = { toastOnUi(R.string.sync_book_progress_success) },
                        manual = true,
                    )
                }

                ReadMenuAction.SAME_TITLE_REMOVED -> {
                    ReadBook.book?.let {
                        val contentProcessor = ContentProcessor.get(it)
                        val textChapter = ReadBook.curTextChapter
                        if (textChapter != null
                            && !textChapter.sameTitleRemoved
                            && !contentProcessor.removeSameTitleCache.contains(
                                textChapter.chapter.getFileName("nr")
                            )
                        ) {
                            toastOnUi("未找到可移除的重复标题")
                        }
                    }
                    viewModel.reverseRemoveSameTitle()
                }

                ReadMenuAction.REVIEW -> viewModel.openCommentDialog(this@ReadBookActivity)

                ReadMenuAction.HELP -> showHelp()
            }
        }

        private fun refreshContentAll(book: Book) {
            ReadBook.clearTextChapter()
            readView.upContent()
            viewModel.refreshContentAll(book)
        }
    }

    inner class ReadBookEventHandler {
        var screenTimeOut: Long = 0
        val handler by lazy { buildMainHandler() }
        private val screenOffRunnable by lazy { Runnable { keepScreenOn(false) } }

        /** 外部 EventBus 广播的单一入口 + read/ 内部 ReadBookEvents 收集 */
        fun observeLiveBus() {
            observeEvent<String>(EventBus.TIME_CHANGED) { readView.upTime() }
            observeEvent<Int>(EventBus.BATTERY_CHANGED) { readView.upBattery(it) }
            observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
                if (it) {
                    onClickReadAloud()
                } else {
                    ReadBook.readAloud(!BaseReadAloudService.pause)
                }
            }
            observeEvent<Int>(EventBus.ALOUD_STATE) {
                ReadBookEvents.postAloudState(it)
                if (it == Status.STOP || it == Status.PAUSE) {
                    ReadBook.curTextChapter?.let { textChapter ->
                        val page = textChapter.getPageByReadPos(ReadBook.durChapterPos)
                        if (page != null) {
                            page.removePageAloudSpan()
                            readView.upContent(resetPageOffset = false)
                        }
                    }
                }
            }
            observeEvent<Int>(EventBus.READ_ALOUD_DS) {
                ReadBookEvents.postReadAloudDs(it)
            }
            observeEventSticky<Int>(EventBus.TTS_PROGRESS) { chapterStart ->
                lifecycleScope.launch(IO) {
                    if (BaseReadAloudService.isPlay()) {
                        ReadBook.curTextChapter?.let { textChapter ->
                            ReadBook.durChapterPos = chapterStart
                            val pageIndex = ReadBook.durPageIndex
                            val aloudSpanStart = chapterStart - textChapter.getReadLength(pageIndex)
                            textChapter.getPage(pageIndex)
                                ?.upPageAloudSpan(aloudSpanStart)
                            upContent()
                        }
                    }
                }
            }
            observeEvent<List<SearchResult>>(EventBus.SEARCH_RESULT) {
                viewModel.searchResultList = it
            }
            ReadBookEvents.configChange.observe(this@ReadBookActivity) { changes ->
                changes.forEach { change ->
                    when (change) {
                        ReadConfigChange.SYSTEM_UI -> upSystemUiVisibility()
                        ReadConfigChange.BG -> readView.upBg()
                        ReadConfigChange.STYLE -> readView.upStyle()
                        ReadConfigChange.BG_ALPHA -> readView.upBgAlpha()
                        ReadConfigChange.PAGE_SLOP -> readView.upPageSlopSquare()
                        ReadConfigChange.LOAD_CONTENT -> if (isInitFinish) ReadBook.loadContent(
                            resetPageOffset = false
                        )

                        ReadConfigChange.UP_CONTENT -> readView.upContent(resetPageOffset = false)
                        ReadConfigChange.CHAPTER_STYLE -> ChapterProvider.upStyle()
                        ReadConfigChange.INVALIDATE_TEXT_PAGE -> readView.invalidateTextPage()
                        ReadConfigChange.CHAPTER_LAYOUT -> ChapterProvider.upLayout()
                        ReadConfigChange.RENDER_TASK -> readView.submitRenderTask()
                        ReadConfigChange.PAGE_ANIM -> upPageAnim()
                    }
                }
            }
            ReadBookEvents.actionBarChange.observe(this@ReadBookActivity) {
                readMenu.reset()
            }
            ReadBookEvents.seekBarChange.observe(this@ReadBookActivity) {
                readMenu.upSeekBar()
            }
            ReadBookEvents.keepLightChange.observe(this@ReadBookActivity) {
                upScreenTimeOut()
            }
            ReadBookEvents.menuRefresh.observe(this@ReadBookActivity) {
                upMenuView()
            }
            ReadBookEvents.loadChapterList.observe(this@ReadBookActivity) {
                loadChapterList(it)
            }
            ReadBookEvents.newProgressConfirm.observe(this@ReadBookActivity) {
                sureNewProgress(it)
            }
        }

        fun upScreenTimeOut() {
            val keepLightPrefer = AppConfig.keepLight?.toInt() ?: 0
            screenTimeOut = keepLightPrefer * 1000L
            screenOffTimerStart()
        }

        /**
         * 重置黑屏时间
         */
        fun screenOffTimerStart() {
            handler.post {
                if (screenTimeOut < 0) {
                    keepScreenOn(true)
                    return@post
                }
                val t = screenTimeOut - sysScreenOffTime
                if (t > 0) {
                    keepScreenOn(true)
                    handler.removeCallbacks(screenOffRunnable)
                    handler.postDelayed(screenOffRunnable, screenTimeOut)
                } else {
                    keepScreenOn(false)
                }
            }
        }
    }

    companion object {
        const val RESULT_DELETED = 100
        private const val BACKUP_DELAY_MS = 300000L
    }

}
