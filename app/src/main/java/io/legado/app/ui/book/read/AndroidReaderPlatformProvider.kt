package io.legado.app.ui.book.read

import android.os.BatteryManager
import androidx.compose.animation.core.MutableTransitionState
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.toRouteRef
import splitties.init.appCtx

class AndroidReaderPlatformProvider(
    private val activity: MainActivity,
) : ReaderPlatformProvider {

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = AndroidReaderMenuController(navigator, screenModel)

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
    }

    override fun onExit(screenModel: ReaderScreenModel) {
        activity.exitReaderWindow()
    }
}

private class AndroidReaderMenuController(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
) : ReadMenuController {
    override val state: ReadMenuState = AndroidReaderMenuState(navigator, screenModel, this)
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
) : ReadMenuState {
    override val visibleState = MutableTransitionState(false)
    override val animate: Boolean = true
    override val isVisible: Boolean get() = visibleState.currentState || visibleState.targetState
    override val canShowMenu: Boolean get() = true
    override val immersive: Boolean = false
    override val bgColor: Int = 0
    override val textColor: Int = 0
    override val hasBgImage: Boolean = false
    override val title: String? get() = screenModel.currentBook?.name
    override val chapterName: String? get() = screenModel.currentChapter?.title
    override val chapterUrl: String? get() = screenModel.currentChapter?.url
    override val chapterNameVisible: Boolean get() = !chapterName.isNullOrEmpty()
    override val chapterUrlVisible: Boolean get() = !chapterUrl.isNullOrEmpty()
    override val sourceActionText: String = ""
    override val sourceActionVisible: Boolean = false
    override val titleBarAdditionVisible: Boolean = true
    override val topMenu = TopMenuState()
    override val seekMax: Int get() = (screenModel.viewModel.chapterSize - 1).coerceAtLeast(0)
    override val seekValue: Int get() = screenModel.viewModel.durChapterIndex.value
    override val prevEnabled: Boolean get() = screenModel.viewModel.canMoveToPrevChapter()
    override val nextEnabled: Boolean get() = screenModel.viewModel.canMoveToNextChapter()
    override val autoPage: Boolean = false
    override val isNightTheme: Boolean = false

    fun show() {
        visibleState.targetState = true
    }

    fun hide() {
        visibleState.targetState = false
    }

    override fun onTransitionIdle(shown: Boolean) = Unit
    override fun onBgClick() = hide()
    override fun onChapterViewClick() = Unit
    override fun onChapterViewLongClick() = Unit
    override fun onOverflowOpened() = Unit
    override fun sourceLoginVisible(): Boolean = false
    override fun sourcePayVisible(): Boolean = false
    override fun onSourceAction(action: SourceAction) = Unit
    override fun openBookInfoActivity() {
        screenModel.currentBook?.let { navigator.push(AppRoute.BookInfo(it.toRouteRef())) }
    }

    override fun supportFinishAfterTransition() {
        navigator.pop()
    }

    override fun onTopMenuAction(action: ReadMenuAction) {
        when (action) {
            ReadMenuAction.CHANGE_SOURCE,
            ReadMenuAction.BOOK_CHANGE_SOURCE -> screenModel.currentBook?.let {
                navigator.push(AppRoute.ChangeSource(it.toRouteRef()))
            }

            ReadMenuAction.CHAPTER_CHANGE_SOURCE -> screenModel.currentBook?.let {
                navigator.push(AppRoute.ChangeChapterSource(it.toRouteRef()))
            }

            ReadMenuAction.LOG -> Unit
            else -> Unit
        }
    }

    override fun onSeekDragStart() = Unit
    override fun onSeekStop(progress: Int) {
        screenModel.viewModel.loadChapter(progress)
    }

    override fun clickSearch() {
        navigator.push(AppRoute.SearchContent())
    }

    override fun clickAutoPage() = Unit
    override fun clickReplaceRule() {
        navigator.push(AppRoute.EffectiveReplaces)
    }

    override fun clickNightTheme() = Unit
    override fun clickPre() {
        screenModel.viewModel.moveToPrevChapter()
    }

    override fun clickNext() {
        screenModel.viewModel.moveToNextChapter()
    }

    override fun clickCatalog() {
        screenModel.currentBook?.let { navigator.push(AppRoute.Toc(it.toRouteRef())) }
    }

    override fun clickReadAloud() = Unit
    override fun longClickReadAloud() = Unit
    override fun clickFont() {
        navigator.push(AppRoute.ReadStyle)
    }

    override fun clickSetting() {
        navigator.push(AppRoute.MoreConfig)
    }
}
