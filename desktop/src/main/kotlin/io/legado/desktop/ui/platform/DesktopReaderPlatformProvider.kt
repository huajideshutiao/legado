package io.legado.desktop.ui.platform

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.book.read.ReadMenuAction
import io.legado.app.ui.book.read.ReadMenuController
import io.legado.app.ui.book.read.ReadMenuState
import io.legado.app.ui.book.read.ReaderDialogEvent
import io.legado.app.ui.book.read.ReaderPlatformProvider
import io.legado.app.ui.book.read.ReaderScreenModel
import io.legado.app.ui.book.read.SourceAction
import io.legado.app.ui.book.read.TopMenuState
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef

/**
 * desktop 端 [ReaderPlatformProvider] 真实实现: 菜单可见/可切, 导航经 [AppNavigator] 桥接。
 *
 * 对照 app 端 [io.legado.app.ui.book.read.AndroidReaderPlatformProvider]:
 * - createMenuController: 返回真实 [DesktopReadMenuController] (visibleState 可切, 非恒 false)
 * - getBatteryLevel: 返回 -1 (JVM 无统一电池 API, 与 iOS 端 UIDevice 不一致, 桌面端不显示电量)
 * - 顶/底栏菜单 UI 由 shared [io.legado.app.ui.book.read.ReadMenuOverlay] 渲染, 此处只持有状态
 * - 导航回调 (clickCatalog/clickSearch/clickFont/clickSetting 等) 经 [AppNavigator] 跳 shared Route
 * - 章节导航 (clickPre/clickNext/onSeekStop) 委托 [ReaderScreenModel.viewModel]
 *
 * # 不实现 (与 app 端差异)
 * - ReadAloud (朗读): app 端走 ReadAloud.stop/play/pause(activity) + BaseReadAloudService,
 *   desktop 无 Service; 长按朗读跳 [AppRoute.ReadAloudConfig], 短按暂不启动 (待 TTS 引擎接入)
 * - ThemeConfig.applyDayNight: app 端切夜间主题后调 Activity 重启 UI, desktop 经
 *   [AppConfigProviders] 切换 isNightTheme 标记即可 (AppTheme 已订阅 themeMode 变化)
 * - 沉浸式色彩 (immersive/bgColor/textColor): desktop 无阅读背景图/纯色定制, 用 AppTheme 默认色
 */
class DesktopReaderPlatformProvider : ReaderPlatformProvider {

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = DesktopReadMenuController(navigator, screenModel)

    override fun getBatteryLevel(): Int = -1
}

private class DesktopReadMenuController(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
) : ReadMenuController {
    override val state: ReadMenuState = DesktopReadMenuState(navigator, screenModel)
    override fun showMenu() = (state as DesktopReadMenuState).show()
    override fun hideMenu() = (state as DesktopReadMenuState).hide()
}

/**
 * desktop 阅读菜单状态: visibleState 可切 (非恒 false), 字段从 screenModel.viewModel 取实时值。
 *
 * 颜色用 AppTheme 默认 (非沉浸式); 菜单显隐时刷新动态项 (顶栏可见性/书源按钮/章节信息),
 * 行为对齐 app 端 AndroidReaderMenuState.show()。
 */
private class DesktopReadMenuState(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
) : ReadMenuState {

    override val visibleState = MutableTransitionState(false)
    override var animate: Boolean = true
        private set
    override val isVisible: Boolean get() = visibleState.currentState || visibleState.targetState
    override val canShowMenu: Boolean get() = true

    // 桌面端无沉浸式阅读背景, 用 AppTheme 默认色
    override val immersive: Boolean = false
    override val bgColor: Int = 0
    override val textColor: Int = 0
    override val hasBgImage: Boolean = false

    // 顶栏: 书名/章节名/章节 URL/书源按钮
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
    override val titleBarAdditionVisible: Boolean get() = false
    override val topMenu = TopMenuState()

    // 底栏: 进度条 + 上/下章 + 自动翻页 + 夜间主题
    override val seekMax: Int
        get() = (screenModel.viewModel.simulatedChapterSize - 1).coerceAtLeast(
            0
        )
    override val seekValue: Int get() = screenModel.viewModel.durChapterIndex.value
    override val prevEnabled: Boolean get() = screenModel.viewModel.canMoveToPrevChapter()
    override val nextEnabled: Boolean get() = screenModel.viewModel.canMoveToNextChapter()
    override var autoPage by mutableStateOf(false)
    override var isNightTheme by mutableStateOf(AppConfigProviders.get().isNightTheme)
        private set

    fun show() {
        animate = !AppConfigProviders.get().isEInkMode
        upSourceAction()
        upTopMenu()
        isNightTheme = AppConfigProviders.get().isNightTheme
        visibleState.targetState = true
    }

    fun hide() {
        visibleState.targetState = false
    }

    // 书源操作按钮 (对照 app 端 AndroidReaderMenuState.upSourceAction)
    private fun upSourceAction() {
        val book = screenModel.viewModel.book.value
        val source = screenModel.viewModel.bookSource.value
        sourceActionText = source?.bookSourceName ?: "书源"
        sourceActionVisible = book?.let { !it.isLocal } ?: false
    }

    // 顶栏菜单可见/勾选状态 (对照 app 端 AndroidReaderMenuState.upTopMenu)
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

    override fun onChapterViewClick() {
        val book = screenModel.viewModel.book.value ?: return
        if (book.isLocal) return
        val url = chapterUrl.orEmpty()
        navigator.push(AppRoute.WebView(url.substringBefore(",{")))
    }

    override fun onChapterViewLongClick() = Unit

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

    // 自动翻页: 切换状态 (对照 app 端 AndroidReaderMenuState.clickAutoPage, 无 ReadAloud.stop)
    override fun clickAutoPage() {
        autoPage = !autoPage
    }

    override fun clickReplaceRule() {
        navigator.push(AppRoute.EffectiveReplaces)
    }

    // 夜间主题切换 (对照 app 端 clickNightTheme, 无 ThemeConfig.applyDayNight)
    override fun clickNightTheme() {
        val config = AppConfigProviders.get()
        val newNight = !config.isNightTheme
        // isNightTheme 由 themeMode 计算, 写 themeMode ("2"=夜间 / "1"=日间)
        PreferenceProviders.get().putString(PreferKey.themeMode, if (newNight) "2" else "1")
        isNightTheme = newNight
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

    // 朗读: desktop 无 Service, 短按暂不启动 (待 TTS 引擎接入后补齐)
    override fun clickReadAloud() = Unit

    // 长按朗读: 跳朗读配置页 (对照 app 端 showReadAloudDialog)
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
