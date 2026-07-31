package io.legado.app.ui.book.read

import android.content.Context
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.getAbsoluteURL
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.widget.dialog.showBookVariableDialog
import io.legado.app.ui.widget.dialog.showSourceVariableDialog
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.openUrl
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读界面菜单：Compose 状态持有者（app 端薄壳）。
 *
 * UI Composable 已下沉到 shared/sharedUiMain 的 [ReadMenuOverlay]（12 个 @Composable），
 * 本类仅保留状态属性 + Android 专属逻辑（lifecycleScope / alert / Intent /
 * AppConfig / ReadBookConfig / ThemeConfig / ReadBook / AppWebDav 等深度依赖，
 * 属 L3 不可下沉），实现 [ReadMenuState] 供 shared Composable 解耦访问。
 *
 * 对外 API(runMenuIn/runMenuOut/upBookView/upSeekBar/setSeekPage/setAutoPage/reset)
 * 与原 View 版一致，UI 由 shared [ReadMenuOverlay] 渲染。
 *
 * 调用方契约不变：`val readMenu: ReadMenu by lazy { ReadMenu(this) }`。
 */
class ReadMenu(internal val activity: ReadBookActivity) : ReadMenuState {

    private val callBack: CallBack get() = activity
    private val context: Context get() = activity

    // ---- 显隐与动画(语义对齐原 menuTopIn/menuTopOut 监听器) ----
    override val visibleState = MutableTransitionState(false)
    override val isVisible: Boolean get() = visibleState.currentState || visibleState.targetState
    override var canShowMenu: Boolean = false
    var isMenuOutAnimating = false
        private set
    override var animate by mutableStateOf(true)
        private set

    /** 原 vw_menu_bg click listener 有无(出场动画中/拖动进度中禁点) */
    private var bgClickEnabled = true
    private var pendingInEnd = false
    private var pendingOutEnd = false
    private var onMenuOutEnd: (() -> Unit)? = null

    // ---- 沉浸式菜单色彩 ----
    override var immersive by mutableStateOf(false)
        private set
    override var bgColor by mutableIntStateOf(0)
        private set
    override var textColor by mutableIntStateOf(0)
        private set
    override var hasBgImage by mutableStateOf(false)
        private set

    // ---- 顶栏 ----
    override var title by mutableStateOf<String?>(null)
        private set
    override var chapterName by mutableStateOf<String?>(null)
        private set
    override var chapterUrl by mutableStateOf<String?>(null)
        private set
    override var chapterNameVisible by mutableStateOf(false)
        private set
    override var chapterUrlVisible by mutableStateOf(false)
        private set
    override var sourceActionText by mutableStateOf("")
        private set
    override var sourceActionVisible by mutableStateOf(false)
        private set
    override var titleBarAdditionVisible by mutableStateOf(AppConfig.showReadTitleBarAddition)
        private set
    override val topMenu = TopMenuState()

    // ---- 底栏 ----
    override var seekMax by mutableIntStateOf(0)
        private set
    override var seekValue by mutableIntStateOf(0)
        private set
    override var prevEnabled by mutableStateOf(false)
        private set
    override var nextEnabled by mutableStateOf(false)
        private set
    override var autoPage by mutableStateOf(false)
    override var isNightTheme by mutableStateOf(AppConfig.isNightTheme)
        private set

    private var confirmSkipToChapter = false

    init {
        upColorConfig()
    }

    private fun upColorConfig() {
        immersive = ReadBookConfig.durConfig.curBgType() == 0
        bgColor = if (immersive) {
            runCatching {
                ReadBookConfig.durConfig.curBgStr().toColorInt()
            }.getOrDefault(context.bottomBackground)
        } else {
            context.bottomBackground
        }
        textColor = if (immersive) {
            ReadBookConfig.durConfig.curTextColor()
        } else {
            context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
        }
        hasBgImage = !ThemeConfig.curBgImagePath.isNullOrBlank()
    }

    fun reset() {
        upColorConfig()
        isNightTheme = AppConfig.isNightTheme
        titleBarAdditionVisible = AppConfig.showReadTitleBarAddition
    }

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        callBack.onMenuShow()
        animate = anim
        // 原 menuInListener.onAnimationStart
        sourceActionText =
            ReadBook.bookSource?.bookSourceName ?: context.getString(R.string.book_source)
        sourceActionVisible = !ReadBook.isLocalBook
        callBack.upSystemUiVisibility()
        // 打断出场：原监听器不会再回调，丢弃出场收尾(与 View 替换动画语义一致)
        pendingOutEnd = false
        isMenuOutAnimating = false
        onMenuOutEnd = null
        if (visibleState.targetState && visibleState.isIdle) {
            menuInEnd()
            return
        }
        pendingInEnd = true
        visibleState.targetState = true
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode, onMenuOutEnd: (() -> Unit)? = null) {
        if (isMenuOutAnimating) {
            return
        }
        callBack.onMenuHide()
        this.onMenuOutEnd = onMenuOutEnd
        if (isVisible) {
            animate = anim
            // 原 menuOutListener.onAnimationStart
            isMenuOutAnimating = true
            bgClickEnabled = false
            pendingInEnd = false
            pendingOutEnd = true
            visibleState.targetState = false
        }
    }

    /** 原 menuInListener.onAnimationEnd */
    private fun menuInEnd() {
        bgClickEnabled = true
        callBack.upSystemUiVisibility()
        if (!LocalConfig.readMenuHelpVersionIsLast) {
            callBack.showHelp()
        }
    }

    /** 原 menuOutListener.onAnimationEnd */
    private fun menuOutEnd() {
        canShowMenu = false
        isMenuOutAnimating = false
        onMenuOutEnd?.invoke()
        onMenuOutEnd = null
        callBack.upSystemUiVisibility()
    }

    override fun onTransitionIdle(shown: Boolean) {
        if (shown && pendingInEnd) {
            pendingInEnd = false
            menuInEnd()
        } else if (!shown && pendingOutEnd) {
            pendingOutEnd = false
            menuOutEnd()
        }
    }

    override fun onBgClick() {
        if (bgClickEnabled) runMenuOut()
    }

    fun upBookView() {
        title = ReadBook.book?.name
        val textChapter = ReadBook.curTextChapter
        if (textChapter != null) {
            chapterName = textChapter.title
            chapterNameVisible = true
            if (!ReadBook.isLocalBook) {
                chapterUrl = textChapter.chapter.getAbsoluteURL(ReadBook.book!!)
                chapterUrlVisible = true
            } else {
                chapterUrlVisible = false
            }
            upSeekBar()
            prevEnabled = ReadBook.durChapterIndex != 0
            nextEnabled = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
        } else {
            chapterNameVisible = false
            chapterUrlVisible = false
        }
    }

    fun upSeekBar() {
        when (AppConfig.progressBarBehavior) {
            "page" -> ReadBook.curTextChapter?.let {
                seekMax = it.pageSize.minus(1)
                seekValue = ReadBook.durPageIndex
            }

            "chapter" -> {
                seekMax = ReadBook.simulatedChapterSize - 1
                seekValue = ReadBook.durChapterIndex
            }
        }
    }

    fun setSeekPage(seek: Int) {
        seekValue = seek
    }

    /** 原 menuHandler.upMenu()：刷新顶栏菜单状态 */
    fun upTopMenu() {
        val book = ReadBook.book ?: return
        val onLine = !book.isLocal
        topMenu.onLine = onLine
        topMenu.isLocalTxt = book.isLocalTxt
        topMenu.isEpub = book.isEpub
        topMenu.enableReplaceChecked = book.getUseReplaceRule()
        topMenu.reSegmentChecked = book.config.reSegment
        topMenu.delRubyChecked = book.config.delTag and Book.rubyTag == Book.rubyTag
        topMenu.delHChecked = book.config.delTag and Book.hTag == Book.hTag
        activity.lifecycleScope.launch {
            val show = ReadBook.inBookshelf && withContext(IO) {
                AppWebDav.isOk
            }
            topMenu.syncProgressVisible = show
        }
    }

    /** 原 onMenuOpened：展开溢出菜单时刷新动态项 */
    override fun onOverflowOpened() {
        topMenu.sameTitleRemovedChecked = ReadBook.curTextChapter?.sameTitleRemoved == true
        topMenu.reviewVisible =
            ReadBook.bookSource?.reviewRule?.reviewUrl.isNullOrBlank() == false
    }

    // ---- 事件(原 bindEvent) ----

    override fun onChapterViewClick() {
        if (ReadBook.isLocalBook) {
            return
        }
        if (AppConfig.readUrlInBrowser) {
            context.openUrl(chapterUrl.orEmpty().substringBefore(",{"))
        } else {
            Coroutine.async {
                // 内置浏览器跳转走 AppNavigator
                AppNavigatorProviders.get().push(AppRoute.WebView(chapterUrl.orEmpty()))
            }
        }
    }

    override fun onChapterViewLongClick() {
        if (ReadBook.isLocalBook) {
            return
        }
        context.alert(R.string.open_fun) {
            setMessage(R.string.use_browser_open)
            okButton {
                AppConfig.readUrlInBrowser = true
            }
            noButton {
                AppConfig.readUrlInBrowser = false
            }
        }
    }

    override fun onSeekDragStart() {
        bgClickEnabled = false
    }

    override fun onSeekStop(progress: Int) {
        bgClickEnabled = true
        when (AppConfig.progressBarBehavior) {
            "page" -> ReadBook.skipToPage(progress)
            "chapter" -> {
                if (confirmSkipToChapter) {
                    callBack.skipToChapter(progress)
                } else {
                    context.alert("章节跳转确认", "确定要跳转章节吗？") {
                        yesButton {
                            confirmSkipToChapter = true
                            callBack.skipToChapter(progress)
                        }
                        noButton {
                            upSeekBar()
                        }
                        onCancelled {
                            upSeekBar()
                        }
                    }
                }
            }
        }
    }

    override fun clickSearch() = runMenuOut { callBack.openSearchActivity(null) }

    override fun clickAutoPage() = runMenuOut { callBack.autoPage() }

    override fun clickReplaceRule() = callBack.openReplaceRule()

    override fun clickNightTheme() {
        AppConfig.isNightTheme = !AppConfig.isNightTheme
        isNightTheme = AppConfig.isNightTheme
        ThemeConfig.applyDayNight(context)
    }

    override fun clickPre() { ReadBook.moveToPrevChapter(upContent = true, toLast = false) }

    override fun clickNext() { ReadBook.moveToNextChapter(true) }

    override fun clickCatalog() = runMenuOut { callBack.openChapterList() }

    override fun clickReadAloud() = runMenuOut { callBack.onClickReadAloud() }

    override fun longClickReadAloud() = runMenuOut { callBack.showReadAloudDialog() }

    override fun clickFont() = runMenuOut { callBack.showReadStyle() }

    override fun clickSetting() = runMenuOut { callBack.showMoreSetting() }

    // 刷新当前章节: 复用 onTopMenuAction(REFRESH) 路径 (app 端 menuHandler 统一处理)
    override fun onRefresh() = activity.onTopMenuAction(ReadMenuAction.REFRESH)

    // ---- 宿主桥接（原 Composable 直接访问 state.activity.xxx）----

    override fun openBookInfoActivity() = activity.openBookInfoActivity()

    override fun supportFinishAfterTransition() = activity.supportFinishAfterTransition()

    override fun onTopMenuAction(action: ReadMenuAction) = activity.onTopMenuAction(action)

    // ---- 书源操作(原 sourceMenu PopupMenu) ----

    override fun sourceLoginVisible() = ReadBook.bookSource?.hasLogin() == true

    override fun sourcePayVisible() = ReadBook.bookSource?.hasLogin() == true
            && ReadBook.curTextChapter?.isVip == true
            && ReadBook.curTextChapter?.isPay != true

    override fun onSourceAction(action: SourceAction) {
        when (action) {
            SourceAction.LOGIN -> callBack.showLogin()
            SourceAction.CHAPTER_PAY -> callBack.payAction()
            SourceAction.SET_SOURCE_VARIABLE ->
                ReadBook.bookSource?.showSourceVariableDialog(activity)

            SourceAction.SET_BOOK_VARIABLE ->
                ReadBook.book?.showBookVariableDialog(activity, ReadBook.bookSource)

            SourceAction.EDIT_SOURCE -> callBack.openSourceEditActivity()
            SourceAction.DISABLE_SOURCE -> callBack.disableSource()
        }
    }

    interface CallBack {
        fun autoPage()
        fun openReplaceRule()
        fun openChapterList()
        fun openSearchActivity(searchWord: String?)
        fun openSourceEditActivity()
        fun openBookInfoActivity()
        fun showReadStyle()
        fun showMoreSetting()
        fun showReadAloudDialog()
        fun upSystemUiVisibility()
        fun onClickReadAloud()
        fun showHelp()
        fun showLogin()
        fun payAction()
        fun disableSource()
        fun skipToChapter(index: Int)
        fun onMenuShow()
        fun onMenuHide()
    }
}
