package io.legado.app.ui.book.read

import android.app.DatePickerDialog
import android.os.BatteryManager
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.SourceLoginContext
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.save
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.compose.component.AppNumberField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AndroidReaderPlatformProvider(
    private val activity: MainActivity,
) : ReaderPlatformProvider {

    // Activity 生命周期观察者：桥接到 ReaderScreenModel.onPause/onResume (对照 app 端 ReadBookActivity.onPause/onResume)
    // onEnter 注册、onExit 解注册；ON_PAUSE 落库+取消预下载，ON_RESUME 留扩展点
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = AndroidReaderMenuController(navigator, screenModel, activity)

    override fun getBatteryLevel(): Int {
        val manager = appCtx.getSystemService(BatteryManager::class.java) ?: return -1
        return runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
    }

    override fun onLongPress(screenModel: ReaderScreenModel) {
        activity.showReaderTextSelection(
            chapterName = screenModel.currentChapter?.title.orEmpty(),
            content = screenModel.currentChapterText,
        )
    }

    override fun onEnter(screenModel: ReaderScreenModel) {
        activity.enterReaderWindow()
        // 注册生命周期观察者: 桥接 Activity onPause/onResume 到 shared ScreenModel
        val observer = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                screenModel.onPause()
            }

            override fun onResume(owner: LifecycleOwner) {
                screenModel.onResume()
            }
        }
        activity.lifecycle.addObserver(observer)
        lifecycleObserver = observer
    }

    override fun onExit(screenModel: ReaderScreenModel) {
        activity.exitReaderWindow()
        lifecycleObserver?.let { activity.lifecycle.removeObserver(it) }
        lifecycleObserver = null
    }

    override fun readAloudControls(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadAloudControls = AndroidReadAloudControls(navigator, screenModel, activity)
}

/**
 * app 端朗读控制桥: 全部落到现有 [ReadAloud] 门面 + [BaseReadAloudService] 静态态,
 * 与 ReadBookActivity 那套自有 [io.legado.app.ui.book.read.config.ReadAloudDialog] 同一条链路。
 */
private class AndroidReadAloudControls(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
    private val activity: MainActivity,
) : ReadAloudControls {

    override val isPlaying: Boolean get() = !BaseReadAloudService.pause

    override val timerMinute: Int
        get() = BaseReadAloudService.timeMinute.takeIf { it > 0 } ?: AppConfig.ttsTimer

    override val speechRate: Int get() = AppConfig.ttsSpeechRate

    override val followSys: Boolean get() = AppConfig.ttsFlowSys

    override fun playPause() {
        when {
            !BaseReadAloudService.isRun -> {
                ReadAloud.upReadAloudClass()
                ReadAloud.play(activity)
            }

            BaseReadAloudService.pause -> ReadAloud.resume(activity)
            else -> ReadAloud.pause(activity)
        }
    }

    override fun stop() = ReadAloud.stop(activity)

    override fun prevChapter() {
        screenModel.viewModel.moveToPrevChapter()
    }

    override fun nextChapter() {
        screenModel.viewModel.moveToNextChapter()
    }

    override fun prevParagraph() = ReadAloud.prevParagraph(activity)

    override fun nextParagraph() = ReadAloud.nextParagraph(activity)

    override fun setTimer(minute: Int) {
        ReadAloud.setTimer(activity, minute)
    }

    override fun setSpeechRate(rate: Int) {
        AppConfig.ttsSpeechRate = rate.coerceIn(0, 45)
        upTtsSpeechRate()
    }

    override fun setFollowSys(follow: Boolean) {
        AppConfig.ttsFlowSys = follow
        upTtsSpeechRate()
    }

    /** 对照原版 ReadAloudDialog.upTtsSpeechRate: 新语速要 pause+resume 才作用到当前段 */
    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(activity)
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(activity)
            ReadAloud.resume(activity)
        }
    }

    override fun openChapterList() {
        screenModel.currentBook?.let {
            navigator.push(AppRoute.Toc(it.toRouteRef()), RouteResults.TOC)
        }
    }

    override fun openSettings() {
        // 对照原版 ReadAloudDialog 设置按钮 → ReadAloudConfigDialog
        screenModel.postDialogEvent(ReaderDialogEvent.ReadAloudConfig)
    }

    override fun toBackstage() {
        navigator.pop()
    }
}

private class AndroidReaderMenuController(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
    private val activity: MainActivity,
) : ReadMenuController {
    override val state: ReadMenuState =
        AndroidReaderMenuState(navigator, screenModel, this, activity)
    override fun showMenu() {
        (state as AndroidReaderMenuState).show()
    }

    override fun hideMenu() {
        (state as AndroidReaderMenuState).hide()
    }
}

private class AndroidReaderMenuState(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
    private val controller: AndroidReaderMenuController,
    private val activity: MainActivity,
) : ReadMenuState {
    override val visibleState = MutableTransitionState(false)
    override var animate: Boolean = true
        private set
    override val isVisible: Boolean get() = visibleState.currentState || visibleState.targetState
    override val canShowMenu: Boolean get() = true

    // 沉浸式菜单色彩 (对照 app 端 ReadMenu.upColorConfig)
    override var immersive by mutableStateOf(false)
        private set
    override var bgColor by mutableIntStateOf(0)
        private set
    override var textColor by mutableIntStateOf(0)
        private set
    override var hasBgImage by mutableStateOf(false)
        private set

    // 顶栏
    override val title: String? get() = screenModel.currentBook?.name
    override val chapterName: String? get() = screenModel.currentChapter?.title
    override val chapterUrl: String? get() = screenModel.currentChapter?.url
    override val chapterNameVisible: Boolean get() = !chapterName.isNullOrEmpty()
    override val chapterUrlVisible: Boolean
        get() = !chapterUrl.isNullOrEmpty() &&
            screenModel.currentBook?.isLocal == false
    override var sourceActionText by mutableStateOf("")
        private set
    override var sourceActionVisible by mutableStateOf(false)
        private set
    override val titleBarAdditionVisible: Boolean get() = AppConfig.showReadTitleBarAddition
    override val topMenu = TopMenuState()

    // 底栏
    override val seekMax: Int
        get() = (screenModel.viewModel.simulatedChapterSize - 1).coerceAtLeast(
            0
        )
    override val seekValue: Int get() = screenModel.viewModel.durChapterIndex.value
    override val prevEnabled: Boolean get() = screenModel.viewModel.canMoveToPrevChapter()
    override val nextEnabled: Boolean get() = screenModel.viewModel.canMoveToNextChapter()
    override var autoPage by mutableStateOf(false)
    override var isNightTheme by mutableStateOf(AppConfig.isNightTheme)
        private set

    fun show() {
        animate = !AppConfig.isEInkMode
        upColorConfig()
        upSourceAction()
        upTopMenu()
        isNightTheme = AppConfig.isNightTheme
        visibleState.targetState = true
        // 菜单显示时状态栏/导航栏恢复显示 (对照原版 runMenuIn → upSystemUiVisibility)
        activity.upReaderSystemBars(menuVisible = true)
    }

    fun hide() {
        visibleState.targetState = false
        // 菜单收起后按 hideStatusBar/hideNavigationBar 配置恢复
        activity.upReaderSystemBars(menuVisible = false)
    }

    // 沉浸式色彩配置 (对照 app 端 ReadMenu.upColorConfig)
    private fun upColorConfig() {
        immersive = ReadBookConfig.durConfig.curBgType() == 0
        bgColor = if (immersive) {
            runCatching {
                ReadBookConfig.durConfig.curBgStr().toColorInt()
            }.getOrDefault(activity.bottomBackground)
        } else {
            activity.bottomBackground
        }
        textColor = if (immersive) {
            ReadBookConfig.durConfig.curTextColor()
        } else {
            activity.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
        }
        hasBgImage = !ThemeConfig.curBgImagePath.isNullOrBlank()
    }

    // 书源操作按钮 (对照 app 端 ReadMenu.runMenuIn sourceAction 赋值)
    private fun upSourceAction() {
        val book = screenModel.viewModel.book.value
        val source = screenModel.viewModel.bookSource.value
        sourceActionText = source?.bookSourceName ?: activity.getString(R.string.book_source)
        sourceActionVisible = book?.let { !it.isLocal } ?: false
    }

    // 顶栏菜单可见/勾选状态 (对照 app 端 ReadMenu.upTopMenu)
    private fun upTopMenu() {
        val book = screenModel.viewModel.book.value ?: return
        topMenu.onLine = !book.isLocal
        topMenu.isLocalTxt = book.isLocalTxt
        topMenu.isEpub = book.isEpub
        topMenu.enableReplaceChecked = book.getUseReplaceRule()
        topMenu.reSegmentChecked = book.config.reSegment
        topMenu.delRubyChecked = book.config.delTag and Book.rubyTag == Book.rubyTag
        topMenu.delHChecked = book.config.delTag and Book.hTag == Book.hTag
    }

    override fun onTransitionIdle(shown: Boolean) = Unit
    override fun onBgClick() = hide()

    // 章节链接点击: 浏览器或内置 WebView (对照 app 端 ReadMenu.onChapterViewClick)
    override fun onChapterViewClick() {
        val book = screenModel.viewModel.book.value ?: return
        if (book.isLocal) return
        val url = chapterUrl.orEmpty()
        if (AppConfig.readUrlInBrowser) {
            activity.openUrl(url.substringBefore(",{"))
        } else {
            // 传原始 chapterUrl (可能含 `,{...}` 请求头) + 书源信息, 由 WebViewRoute 解析
            navigator.push(
                AppRoute.WebView(
                    url = url,
                    sourceKey = book.origin,
                    sourceName = book.originName,
                )
            )
        }
    }

    // 章节链接长按: 切换浏览器打开方式 (对照 app 端 ReadMenu.onChapterViewLongClick)
    override fun onChapterViewLongClick() {
        val book = screenModel.viewModel.book.value ?: return
        if (book.isLocal) return
        activity.alert(R.string.open_fun) {
            setMessage(R.string.use_browser_open)
            okButton { AppConfig.readUrlInBrowser = true }
            noButton { AppConfig.readUrlInBrowser = false }
        }
    }

    // 溢出菜单展开时刷新动态项 (对照 app 端 ReadMenu.onOverflowOpened)
    override fun onOverflowOpened() {
        val source = screenModel.viewModel.bookSource.value
        topMenu.reviewVisible = source?.reviewRule?.reviewUrl.isNullOrBlank() == false
    }

    override fun sourceLoginVisible(): Boolean =
        screenModel.viewModel.bookSource.value?.hasLogin() == true

    override fun sourcePayVisible(): Boolean =
        screenModel.viewModel.bookSource.value?.hasLogin() == true

    override fun onSourceAction(action: SourceAction) {
        when (action) {
            SourceAction.LOGIN -> {
                val source = screenModel.viewModel.bookSource.value ?: return
                // 带上当前书与当前章, 供登录 JS 的 book/chapter 绑定 (对照原版 showLogin 预置 IntentData)
                val dataKey = SourceLoginContext.put(
                    source,
                    screenModel.currentBook,
                    screenModel.currentChapter,
                )
                if (source.loginUi.isNullOrEmpty()) {
                    // URL 登录: 对照原版 showLoginDialog 的 WebViewActivity 分支, 开登录页
                    navigator.push(AppRoute.Login(source.getKey(), dataKey))
                } else {
                    // 表单登录: 对照原版 showDialogFragment<SourceLoginDialog>, Overlay 弹对话框
                    navigator.showOverlay(
                        AppOverlay.Dialog(key = "sourceLogin", payload = dataKey)
                    )
                }
            }

            SourceAction.EDIT_SOURCE -> {
                val origin = screenModel.viewModel.book.value?.origin ?: return
                navigator.push(AppRoute.BookSourceEdit(origin), RouteResults.BOOK_SOURCE_EDIT)
            }

            SourceAction.DISABLE_SOURCE -> screenModel.viewModel.disableSource()
            else -> Unit
        }
    }

    override fun openBookInfoActivity() {
        screenModel.currentBook?.let {
            navigator.push(AppRoute.BookInfo(it.toRouteRef()), RouteResults.BOOK_INFO)
        }
    }

    override fun supportFinishAfterTransition() {
        navigator.pop()
    }

    override fun onTopMenuAction(action: ReadMenuAction) {
        when (action) {
            ReadMenuAction.CHANGE_SOURCE,
            ReadMenuAction.BOOK_CHANGE_SOURCE -> screenModel.currentBook?.let {
                navigator.push(AppRoute.ChangeSource(it.toRouteRef()), RouteResults.CHANGE_SOURCE)
            }

            ReadMenuAction.CHAPTER_CHANGE_SOURCE -> screenModel.currentBook?.let {
                navigator.push(AppRoute.ChangeChapterSource(it.toRouteRef()))
            }

            ReadMenuAction.REFRESH_DUR -> screenModel.viewModel.refreshCurrentChapter()

            ReadMenuAction.ADD_BOOKMARK -> {
                val book = screenModel.viewModel.book.value ?: return
                val page = screenModel.viewModel.curTextPage.value
                val bookmark = Bookmark(bookName = book.name, bookAuthor = book.author).apply {
                    chapterIndex = screenModel.viewModel.durChapterIndex.value
                    chapterPos = screenModel.viewModel.durChapterPos.value
                    chapterName = page?.title ?: screenModel.currentChapter?.title ?: ""
                    bookText = page?.text?.trim() ?: ""
                }
                screenModel.postDialogEvent(ReaderDialogEvent.AddBookmark(bookmark))
            }

            ReadMenuAction.EDIT_CONTENT -> screenModel.postDialogEvent(ReaderDialogEvent.EditContent)

            ReadMenuAction.LOG -> screenModel.postDialogEvent(ReaderDialogEvent.Log)

            // ===== 溢出菜单动作 (对照原版 ReadBookActivity.menuHandler.onMenuAction) =====

            // 翻页动画: 6 项选择器, 选中后刷新 (对照原版 showPageAnimConfig:
            // 选择器回调忽略索引, 实际动画值在界面设置弹窗配置, 此处只触发 upPageAnim + 重载)
            ReadMenuAction.PAGE_ANIM -> {
                val items = arrayListOf(
                    activity.getString(R.string.btn_default_s),
                    activity.getString(R.string.page_anim_cover),
                    activity.getString(R.string.page_anim_slide),
                    activity.getString(R.string.page_anim_simulation),
                    activity.getString(R.string.page_anim_scroll),
                    activity.getString(R.string.page_anim_none),
                )
                activity.selector(R.string.page_anim, items) { _, _ ->
                    ReadBookEvents.postConfig(
                        ReadConfigChange.PAGE_ANIM, ReadConfigChange.LOAD_CONTENT
                    )
                }
            }

            // 模拟阅读: 开关 + 起始日期 + 起始章节/每日章数 (对照原版 showSimulatedReading)
            ReadMenuAction.SIMULATED_READING -> showSimulatedReading()

            // 启用替换: 翻转 useReplaceRule + 刷新替换规则缓存 (对照原版 changeReplaceRuleState)
            ReadMenuAction.ENABLE_REPLACE -> {
                val book = screenModel.viewModel.book.value ?: return
                book.config.useReplaceRule = !book.getUseReplaceRule()
                upTopMenu()
                activity.lifecycleScope.launch(IO) {
                    runCatching {
                        ContentProcessor.get(book).upReplaceRules()
                        appDb.bookDao.update(book)
                    }
                    // 对照原版 ReadBook.loadContent(resetPageOffset = false): 同章重载保留进度
                    screenModel.viewModel.loadChapter(screenModel.viewModel.durChapterIndex.value)
                }
            }

            // 去重: 无重复标题可去时提示, 然后翻转当前章去重标记 (对照原版 menu_same_title_removed)
            ReadMenuAction.SAME_TITLE_REMOVED -> {
                val vm = screenModel.viewModel
                val book = vm.book.value ?: return
                val textChapter = vm.curTextChapter.value ?: return
                val chapter = vm.chapterList.value.getOrNull(textChapter.chapterIndex)
                    ?: runBlocking {
                        appDb.bookChapterDao.getChapter(book.bookUrl, textChapter.chapterIndex)
                    } ?: return
                val contentProcessor = ContentProcessor.get(book)
                if (!textChapter.sameTitleRemoved
                    && !contentProcessor.removeSameTitleCache.contains(
                        chapter.getFileName("nr")
                    )
                ) {
                    activity.toastOnUi("未找到可移除的重复标题")
                }
                BookHelp.setRemoveSameTitle(book, chapter, !textChapter.sameTitleRemoved)
                vm.loadChapter(textChapter.chapterIndex)
            }

            // 重新分段: 翻转 reSegment (对照原版 menu_re_segment)
            ReadMenuAction.RE_SEGMENT -> {
                val book = screenModel.viewModel.book.value ?: return
                book.config.reSegment = !book.config.reSegment
                upTopMenu()
                activity.lifecycleScope.launch(IO) {
                    appDb.bookDao.update(book)
                    screenModel.viewModel.loadChapter(screenModel.viewModel.durChapterIndex.value)
                }
            }

            // 图片样式: 4 项选择器, 单选样式后重载 (对照原版 menu_image_style;
            // SINGLE 样式需要重建翻页委托)
            ReadMenuAction.IMAGE_STYLE -> {
                val imgStyles = arrayListOf(
                    Book.imgStyleDefault, Book.imgStyleFull, Book.imgStyleText, Book.imgStyleSingle
                )
                activity.selector(R.string.image_style, imgStyles) { _, index ->
                    val imageStyle = imgStyles[index]
                    val book = screenModel.viewModel.book.value ?: return@selector
                    book.config.imageStyle = imageStyle
                    activity.lifecycleScope.launch(IO) {
                        appDb.bookDao.update(book)
                        if (imageStyle == Book.imgStyleSingle) {
                            ReadBookEvents.postConfig(ReadConfigChange.PAGE_ANIM)
                        }
                        screenModel.viewModel.loadChapter(screenModel.viewModel.durChapterIndex.value)
                    }
                }
            }

            // 更新目录: 清解析缓存后回源重拉目录 (对照原版 menu_update_toc)
            ReadMenuAction.UPDATE_TOC -> screenModel.viewModel.updateToc()

            // 云进度: 手动同步, 上传成功/已同步 toast (对照原版 menu_sync_progress)
            ReadMenuAction.SYNC_PROGRESS -> screenModel.viewModel.syncProgressManual(
                uploadSuccessAction = { activity.toastOnUi(R.string.upload_book_success) },
                syncSuccessAction = { activity.toastOnUi(R.string.sync_book_progress_success) },
            )

            // 段评: 章节级评论对话框 (对照原版 menu_review → viewModel.openCommentDialog)
            ReadMenuAction.REVIEW -> {
                val book = screenModel.viewModel.book.value ?: return
                val chapter = screenModel.currentChapter
                if (chapter != null) {
                    activity.showDialogFragment(ReviewListDialog(book, chapter, 0))
                }
            }

            // 帮助 (对照原版 menu_help → showHelp("readMenuHelp"))
            ReadMenuAction.HELP -> activity.showHelp("readMenuHelp")

            // epub 去除 ruby/h 标签: 全章清缓存重载 (对照原版 menu_del_ruby_tag / menu_del_h_tag)
            ReadMenuAction.DEL_RUBY_TAG -> toggleDelTag(Book.rubyTag)
            ReadMenuAction.DEL_H_TAG -> toggleDelTag(Book.hTag)

            else -> Unit
        }
    }

    /** 翻转去除标签配置并全章重载 (对照原版 DEL_RUBY_TAG/DEL_H_TAG 分支) */
    private fun toggleDelTag(tag: Long) {
        val book = screenModel.viewModel.book.value ?: return
        if (book.config.delTag and tag == tag) {
            book.config.delTag = book.config.delTag and tag.inv()
        } else {
            book.config.delTag = book.config.delTag or tag
        }
        upTopMenu()
        activity.lifecycleScope.launch(IO) {
            appDb.bookDao.update(book)
            screenModel.viewModel.refreshContentAll()
        }
    }

    /** 模拟阅读配置弹窗 (对照原版 BaseReadBookActivity.showSimulatedReading) */
    private fun showSimulatedReading() {
        val book = screenModel.viewModel.book.value ?: return
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val enabledState = mutableStateOf(book.config.readSimulating)
        val startState = mutableStateOf(book.getStartChapter().toString())
        val numState = mutableStateOf(book.config.dailyChapters.toString())
        val dateState = mutableStateOf(book.getStartDate()?.format(dateFormatter).orEmpty())
        activity.alert(R.string.simulated_reading) {
            customView {
                val colors = AppTheme.colors
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            activity.getString(R.string.switch_on),
                            color = colors.primaryText,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f),
                        )
                        AppSwitch(
                            checked = enabledState.value,
                            onCheckedChange = { enabledState.value = it },
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            activity.getString(R.string.start_from),
                            color = colors.primaryText,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = dateState.value.ifEmpty { "Select date" },
                            color = if (dateState.value.isEmpty()) colors.secondaryText
                            else colors.primaryText,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    val localStartDate = runCatching {
                                        LocalDate.parse(dateState.value)
                                    }.getOrDefault(LocalDate.now())
                                    DatePickerDialog(
                                        activity,
                                        { _, yy, mm, dayOfMonth ->
                                            dateState.value = LocalDate.of(yy, mm + 1, dayOfMonth)
                                                .format(dateFormatter)
                                        },
                                        localStartDate.year,
                                        localStartDate.monthValue - 1,
                                        localStartDate.dayOfMonth,
                                    ).show()
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            activity.getString(R.string.start_chapter),
                            color = colors.primaryText,
                            fontSize = 16.sp,
                        )
                        AppNumberField(
                            value = startState.value,
                            onValueChange = { startState.value = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                        )
                        Text(
                            activity.getString(R.string.daily_chapters),
                            color = colors.primaryText,
                            fontSize = 16.sp,
                        )
                        AppNumberField(
                            value = numState.value,
                            onValueChange = { numState.value = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp),
                        )
                    }
                }
            }
            okButton {
                val date = dateState.value.let {
                    if (it.isEmpty()) LocalDate.now()
                    else LocalDate.parse(it, dateFormatter)
                }
                book.config.startDate = date
                book.config.dailyChapters = numState.value.intOr(book.totalChapterNum)
                book.config.startChapter = startState.value.intOr(0)
                book.config.readSimulating = enabledState.value
                // 对照原版 book.save() + viewModel.initData: 落库并重装使模拟章节总数生效
                book.save()
                screenModel.initBook(book, book.durChapterIndex)
            }
            cancelButton()
        }
    }

    private fun String.intOr(default: Int): Int = toIntOrNull() ?: default

    override fun onSeekDragStart() = Unit
    override fun onSeekStop(progress: Int) {
        screenModel.viewModel.loadChapter(progress)
    }

    override fun clickSearch() {
        // 缓存的结果只有 query 与当前一致才回填 (对照 ReadBookActivity.openSearchActivity)
        val initialResults = screenModel.searchResultList
            ?.takeIf { results -> results.firstOrNull()?.query == screenModel.searchContentQuery }
        navigator.push(
            AppRoute.SearchContent(
                index = screenModel.searchResultIndex,
                word = screenModel.searchContentQuery.takeIf { it.isNotEmpty() },
                initialResults = initialResults,
            ),
            resultKey = RouteResults.SEARCH_CONTENT,
        )
    }

    // 自动翻页: 切换状态 + 停止朗读 (对照 app 端 ReadBookActivity.autoPage)
    override fun clickAutoPage() {
        autoPage = !autoPage
        if (autoPage) ReadAloud.stop(activity)
    }

    override fun clickReplaceRule() {
        // 对照原版 openReplaceRule → EffectiveReplacesDialog (runMenuOut 先收菜单)
        hide()
        screenModel.postDialogEvent(ReaderDialogEvent.EffectiveReplaces)
    }

    // 夜间主题切换 (对照 app 端 ReadMenu.clickNightTheme)
    override fun clickNightTheme() {
        AppConfig.isNightTheme = !AppConfig.isNightTheme
        isNightTheme = AppConfig.isNightTheme
        ThemeConfig.applyDayNight(activity)
    }

    override fun clickPre() {
        screenModel.viewModel.moveToPrevChapter()
    }

    override fun clickNext() {
        screenModel.viewModel.moveToNextChapter()
    }

    override fun clickCatalog() {
        screenModel.currentBook?.let {
            navigator.push(AppRoute.Toc(it.toRouteRef()), RouteResults.TOC)
        }
    }

    // 朗读: 未运行→开始, 暂停→恢复, 运行→暂停 (对照 app 端 ReadBookActivity.onClickReadAloud)
    override fun clickReadAloud() {
        when {
            !BaseReadAloudService.isRun -> {
                ReadAloud.upReadAloudClass()
                ReadAloud.play(activity)
            }

            BaseReadAloudService.pause -> ReadAloud.resume(activity)
            else -> ReadAloud.pause(activity)
        }
    }

    // 长按朗读: 弹共享朗读控制面板 (对照原版 ReadMenu 长按 → showReadAloudDialog)
    override fun longClickReadAloud() {
        screenModel.postDialogEvent(ReaderDialogEvent.ReadAloud)
    }

    override fun clickFont() {
        // 对照原版 showReadStyle → ReadStyleDialog (底部弹窗, runMenuOut 先收菜单)
        hide()
        screenModel.postDialogEvent(ReaderDialogEvent.ReadStyle)
    }

    override fun clickSetting() {
        // 对照原版 showMoreSetting → MoreConfigDialog (底部弹窗, runMenuOut 先收菜单)
        hide()
        screenModel.postDialogEvent(ReaderDialogEvent.MoreConfig)
    }

    // 刷新当前章节 (顶栏刷新图标短按)
    override fun onRefresh() {
        screenModel.viewModel.refreshCurrentChapter()
    }
}
