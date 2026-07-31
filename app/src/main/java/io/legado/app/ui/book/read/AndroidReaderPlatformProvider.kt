package io.legado.app.ui.book.read

import android.os.BatteryManager
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.toColorInt
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.openUrl
import splitties.init.appCtx

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
    }

    fun hide() {
        visibleState.targetState = false
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
            navigator.push(AppRoute.WebView(url))
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
                val origin = screenModel.viewModel.book.value?.origin ?: return
                navigator.push(AppRoute.Login(origin))
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

            else -> Unit
        }
    }

    override fun onSeekDragStart() = Unit
    override fun onSeekStop(progress: Int) {
        screenModel.viewModel.loadChapter(progress)
    }

    override fun clickSearch() {
        navigator.push(
            AppRoute.SearchContent(),
            resultKey = RouteResults.SEARCH_CONTENT,
        )
    }

    // 自动翻页: 切换状态 + 停止朗读 (对照 app 端 ReadBookActivity.autoPage)
    override fun clickAutoPage() {
        autoPage = !autoPage
        if (autoPage) ReadAloud.stop(activity)
    }

    override fun clickReplaceRule() {
        navigator.push(AppRoute.EffectiveReplaces)
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

    // 长按朗读: 跳转朗读配置页 (对照 app 端 showReadAloudDialog)
    override fun longClickReadAloud() {
        navigator.push(AppRoute.ReadAloudConfig)
    }

    override fun clickFont() {
        navigator.push(AppRoute.ReadStyle)
    }

    override fun clickSetting() {
        navigator.push(AppRoute.MoreConfig)
    }

    // 刷新当前章节 (顶栏刷新图标短按)
    override fun onRefresh() {
        screenModel.viewModel.refreshCurrentChapter()
    }
}
