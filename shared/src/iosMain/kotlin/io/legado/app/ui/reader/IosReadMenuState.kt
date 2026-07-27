package io.legado.app.ui.reader

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.openURL
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.ReadMenuAction
import io.legado.app.ui.book.read.ReadMenuState
import io.legado.app.ui.book.read.SourceAction
import io.legado.app.ui.book.read.TopMenuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * iOS 端 [ReadMenuState] 实现：桥接 shared/sharedUiMain 的 [io.legado.app.ui.book.read.ReadMenuOverlay]
 * 与 [ReadBookViewModelShared]。
 *
 * # 职责
 *
 * 对照 app 端 `ReadMenu` 类 + 桌面端 `DesktopReadMenuState`, iOS 端实现 [ReadMenuState] 接口,
 * 供 shared/sharedUiMain 的 [io.legado.app.ui.book.read.ReadMenuOverlay] 解耦访问。
 *
 * # 实现策略 (与桌面端对齐, 简化 iOS 专属逻辑)
 *
 * - **显隐**: 用 [MutableTransitionState] 直接控制 (无 bgClickEnabled / 出场动画收尾等细节,
 *   iOS 端无系统状态栏沉浸式联动需求)
 * - **色彩**: 固定非沉浸式 (immersive=false), bgColor/textColor 给默认值;
 *   `ReadMenuOverlay` 内部按 immersive=false 走 `AppTheme.colors` 分支 (与桌面端一致)
 * - **状态同步**: 由 [IosReaderScreen] 的 `LaunchedEffect(curTextPage, durChapterIndex, chapterList)`
 *   调 [upBookView] 同步顶栏标题 + 底栏进度 + 上一/下一章可用性
 * - **回调桥接**:
 *   - [supportFinishAfterTransition] → [onBack] 回书架
 *   - [clickCatalog] → [openCatalog] 打开 ModalNavigationDrawer 目录侧栏
 *   - [clickPre] / [clickNext] → viewModel 切章
 *   - [clickReadAloud] / [longClickReadAloud] → [showReadAloudDialog] 弹朗读控制 Dialog
 *   - [clickAutoPage] → 用 [autoPageScope] 启动/取消定时翻页 job (间隔取 [readBookConfig] 的 autoReadSpeed, 与桌面端对齐)
 *   - [clickNightTheme] → [onToggleNightTheme] 切换 iOS 端 ThemeStore 深浅色
 *   - [onSeekStop] → viewModel.loadChapter 章节级跳转
 *
 * # 简化项 (iOS 端 KP5 阶段暂未接入, 留 TODO)
 *
 * - 顶栏 PAGE_ANIM: 复用 clickFont 入口 (showReadConfigPanel, 含翻页模式选择)
 * - 顶栏 EDIT_CONTENT: 弹 ContentEditDialog (sharedUiMain 已下沉)
 * - 顶栏 CHAPTER_CHANGE_SOURCE: 弹 IosChangeChapterSourceScreen 覆盖层
 * - 顶栏 CHANGE_SOURCE / BOOK_CHANGE_SOURCE (整书换源): 弹 IosChangeBookSourceScreen 覆盖层
 * - 书签 (ADD_BOOKMARK): 弹 BookmarkDialog (sharedUiMain 已下沉)
 * - 沉浸式色彩 (immersive): iOS 端暂不接入 ThemeConfig 沉浸式菜单色
 *
 * @param viewModel 阅读 ViewModel, 提供 curTextPage / durChapterIndex / chapterList 等
 * @param book 当前阅读的书籍 (供 title / isLocal 判定)
 * @param onBack 返回书架回调 (桥接 `supportFinishAfterTransition`)
 * @param onOpenBookInfo 打开书籍详情回调 (桥接 `openBookInfoActivity`)
 * @param openCatalog 打开目录侧栏回调 (桥接 `clickCatalog`)
 * @param showReadAloudDialog 显示朗读控制 Dialog 回调 (桥接 `longClickReadAloud`)
 * @param onToggleNightTheme 切换夜间主题回调 (桥接 `clickNightTheme`)
 * @param onOpenSearchContent 打开书内全文搜索回调 (桥接 `clickSearch`)
 * @param onOpenReplaceRule 打开有效替换规则回调 (桥接 `clickReplaceRule`)
 * @param onOpenMoreConfig 打开更多阅读配置回调 (桥接 `clickSetting`)
 * @param onOpenReadStyle 显示阅读样式配置回调 (桥接 `clickFont` / `PAGE_ANIM`)
 * @param onShowContentEdit 显示章节正文编辑 Dialog 回调 (桥接 `EDIT_CONTENT`)
 * @param showChangeChapterSource 显示章节换源覆盖层回调 (桥接 `CHAPTER_CHANGE_SOURCE`)
 * @param showAddBookmark 显示添加书签 Dialog 回调 (桥接 `ADD_BOOKMARK`)
 * @param showChangeBookSource 显示整书换源覆盖层回调 (桥接 `CHANGE_SOURCE` / `BOOK_CHANGE_SOURCE`)
 * @param showSourceLogin 书源登录回调 (桥接 `SourceAction.LOGIN`, 弹 SourceLoginDialog)
 * @param onChapterPay 章节购买回调 (桥接 `SourceAction.CHAPTER_PAY`, 打开浏览器)
 * @param showVariableDialog 变量编辑回调 (桥接 `SourceAction.SET_SOURCE_VARIABLE` / `SET_BOOK_VARIABLE`, 弹 VariableDialog)
 * @param openSourceEdit 编辑书源回调 (桥接 `SourceAction.EDIT_SOURCE`, 跳转 BookSourceEdit)
 * @param autoPageScope 自动翻页协程作用域 (桥接 `clickAutoPage` 启动定时翻页 job)
 * @param showAutoReadDialog 显示自动翻页配置 Dialog 回调 (桥接 `toggleMenu` 在 autoPage=true 时)
 * @param readBookConfig 阅读配置 (供 `clickAutoPage` 读取 autoReadSpeed 决定翻页间隔)
 */
class IosReadMenuState(
    private val viewModel: ReadBookViewModelShared,
    private val book: Book,
    private val onBack: () -> Unit,
    private val onOpenBookInfo: () -> Unit,
    private val openCatalog: () -> Unit,
    private val showReadAloudDialog: () -> Unit,
    private val onToggleNightTheme: () -> Unit,
    private val onOpenSearchContent: () -> Unit,
    private val onOpenReplaceRule: () -> Unit,
    private val onOpenMoreConfig: () -> Unit,
    private val onOpenReadStyle: () -> Unit,
    private val onShowContentEdit: () -> Unit,
    private val showChangeChapterSource: () -> Unit,
    private val showAddBookmark: () -> Unit,
    private val showChangeBookSource: () -> Unit,
    private val showSourceLogin: () -> Unit,
    private val onChapterPay: () -> Unit,
    private val showVariableDialog: () -> Unit,
    private val openSourceEdit: () -> Unit,
    private val autoPageScope: CoroutineScope,
    private val showAutoReadDialog: () -> Unit,
    private val readBookConfig: ReadBookConfigShared,
) : ReadMenuState {

    // 自动翻页定时 job (clickAutoPage 启动/取消, 间隔取 readBookConfig.autoReadSpeed)
    private var autoPageJob: Job? = null

    // ---- 显隐与动画 ----
    override val visibleState = MutableTransitionState(false)
    override val animate: Boolean = true
    override val isVisible: Boolean get() = visibleState.currentState || visibleState.targetState
    override var canShowMenu: Boolean = false
        private set

    // ---- 沉浸式菜单色彩 (iOS 端固定非沉浸式, 走 ReadMenuOverlay 的 AppTheme.colors 分支) ----
    // bgColor/textColor 仅在 immersive=true 时被 ReadMenuOverlay 使用, 这里给默认值避免 0 黑色
    override val immersive: Boolean = false
    override var bgColor by mutableIntStateOf(0xFFFFFFFF.toInt())
        private set
    override var textColor by mutableIntStateOf(0xFF000000.toInt())
        private set
    override val hasBgImage: Boolean = false

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
    override val titleBarAdditionVisible: Boolean = true
    override val topMenu: TopMenuState = TopMenuState().apply {
        // iOS 端默认按"在线书源"展示顶栏换源/刷新/离线缓存按钮
        // (对照 app 端 ReadMenu.upTopMenu: onLine = !book.isLocal)
        onLine = !book.isLocal
    }

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

    // ---- 内部辅助方法 ----

    /**
     * 由 [IosReaderScreen] 调用同步状态 (curTextPage / 章节索引 / 章节总数变化时触发)。
     * 对照 app 端 `ReadMenu.upBookView()`: 同步顶栏标题 + 底栏进度 + 上一/下一章可用性。
     */
    fun upBookView(chapterTitle: String?, durIndex: Int, chapterSize: Int) {
        title = book.name
        chapterName = chapterTitle
        chapterNameVisible = chapterTitle != null
        chapterUrlVisible = false
        seekMax = (chapterSize - 1).coerceAtLeast(0)
        seekValue = durIndex
        prevEnabled = durIndex > 0
        nextEnabled = durIndex < chapterSize - 1
    }

    /**
     * 切菜单显隐 (中心点击触发, 与 app 端 showActionMenu 行为对齐)。
     *
     * 对照 app 端 [io.legado.app.ui.book.read.ReadBookActivity.showActionMenu]:
     * - 自动翻页运行中 (isAutoPage=true) → 弹 AutoReadDialog 配置速度
     * - 正在显示 → 收起; 隐藏 → 显示
     */
    fun toggleMenu() {
        if (autoPage) {
            // 自动翻页运行中, 中心点击弹 AutoReadDialog 配置速度 (对齐 app 端 showActionMenu)
            showAutoReadDialog()
        } else if (isVisible) {
            runMenuOut()
        } else {
            runMenuIn()
        }
    }

    /** 显示菜单 (与 app 端 ReadMenu.runMenuIn 对应, 简化出场动画收尾) */
    fun runMenuIn() {
        canShowMenu = true
        visibleState.targetState = true
    }

    /** 隐藏菜单 (与 app 端 ReadMenu.runMenuOut 对应, 简化出场动画收尾) */
    fun runMenuOut() {
        visibleState.targetState = false
    }

    // ---- 动画生命周期回调 ----

    override fun onTransitionIdle(shown: Boolean) {
        if (!shown) {
            canShowMenu = false
        }
    }

    override fun onBgClick() {
        // 菜单显示时点击空白区域收起 (与 app 端 onBgClick 一致)
        if (isVisible) runMenuOut()
    }

    // ---- 顶栏动作回调 ----

    override fun onChapterViewClick() {
        // 用系统浏览器打开章节 URL (对照 app 端用浏览器打开章节)
        chapterUrl?.takeIf { it.isNotEmpty() }?.let { openURL(it) }
    }

    override fun onChapterViewLongClick() {
        // app 端弹"用浏览器打开"开关对话框, iOS 端暂 no-op
    }

    override fun onOverflowOpened() {
        // app 端在展开溢出菜单时刷新 sameTitleRemoved / review 可见性,
        // iOS 端这两个菜单项的可见性由 TopMenuState 默认值控制 (reviewVisible=false)
    }

    override fun sourceLoginVisible(): Boolean = false

    override fun sourcePayVisible(): Boolean = false

    override fun onSourceAction(action: SourceAction) {
        // 对照 app 端 ReadMenu.onSourceAction → ReadBookActivity 各回调
        when (action) {
            SourceAction.DISABLE_SOURCE -> {
                // 禁用当前书源 + Toast 确认 + 退出阅读 (对照 app 端 ReadBookActivity.disableSource)
                viewModel.disableSource()
                Toasters.get().toast("已禁用书源")
                onBack()
            }
            // 书源登录: 弹 SourceLoginDialog (shared/sharedUiMain 已下沉)
            SourceAction.LOGIN -> showSourceLogin()
            // 章节购买: 打开浏览器 (iOS 用 platform 级 openURL, 对照 app 端 payAction → WebViewActivity)
            SourceAction.CHAPTER_PAY -> onChapterPay()
            // 变量编辑: 弹 VariableDialog (shared/sharedUiMain 已下沉, 源变量 + 书籍变量双 Tab)
            SourceAction.SET_SOURCE_VARIABLE,
            SourceAction.SET_BOOK_VARIABLE -> showVariableDialog()
            // 编辑书源: 跳转 BookSourceEdit 路由 (对照 app 端 openSourceEditActivity)
            SourceAction.EDIT_SOURCE -> openSourceEdit()
        }
    }

    // ---- 宿主桥接 (原 state.activity.xxx) ----

    override fun openBookInfoActivity() {
        // 切到 BOOK_INFO 路由 (携带当前 book), 由 IosReaderScreen 注入的 onOpenBookInfo 回调触发
        onOpenBookInfo()
    }

    override fun supportFinishAfterTransition() {
        onBack()
    }

    override fun onTopMenuAction(action: ReadMenuAction) {
        // iOS 端顶栏菜单 action 分发 (对照 app 端 ReadBookActivity.onMenuAction)
        when (action) {
            ReadMenuAction.REFRESH,
            ReadMenuAction.REFRESH_DUR -> {
                // 重载当前章节 (与 app 端 refreshContentDur 对应, iOS 端可直接复用 viewModel)
                viewModel.loadChapter(viewModel.durChapterIndex.value)
            }
            ReadMenuAction.PAGE_ANIM -> {
                // 翻页动画配置: 复用 clickFont 入口 (showReadConfigPanel, 含翻页模式选择)
                // 对照 desktop 端 PAGE_ANIM → showReadStyleDialog, iOS 端用简化版 ReadConfigPanel
                onOpenReadStyle()
            }
            ReadMenuAction.EDIT_CONTENT -> {
                // 弹 ContentEditDialog 编辑当前章节正文 (sharedUiMain 已下沉)
                // 对照 app 端 showDialogFragment(ContentEditDialog)
                onShowContentEdit()
            }
            ReadMenuAction.CHAPTER_CHANGE_SOURCE -> {
                // 章节换源: 弹 IosChangeChapterSourceScreen 覆盖层
                // 对照 app 端 onMenuAction(CHAPTER_CHANGE_SOURCE) → ChangeChapterSourceDialog
                showChangeChapterSource()
            }
            ReadMenuAction.CHANGE_SOURCE,
            ReadMenuAction.BOOK_CHANGE_SOURCE -> {
                // 整书换源: 弹 IosChangeBookSourceScreen 覆盖层
                // 对照 app 端 onMenuAction(CHANGE_SOURCE/BOOK_CHANGE_SOURCE) → ChangeBookSourceDialog
                showChangeBookSource()
            }
            ReadMenuAction.ADD_BOOKMARK -> {
                // 添加书签: 弹 BookmarkDialog (sharedUiMain 已下沉)
                // 对照 app 端 addBookmark() → showDialogFragment(BookmarkDialog(bookmark))
                showAddBookmark()
            }
            else -> {
                // TODO: 其余顶栏 action (DOWNLOAD/TOC_REGEX/SET_CHARSET/SYNC_PROGRESS 等)
                //  需对应 Dialog/Screen 下沉后接入
            }
        }
    }

    // ---- 底栏动作回调 ----

    override fun onSeekDragStart() {
        // 进度条拖动开始 (app 端用 bgClickEnabled=false 拦截 bg 点击, iOS 端简化)
    }

    override fun onSeekStop(progress: Int) {
        // 章节级跳转 (与 app 端 skipToChapter 对应, iOS 端 viewModel.loadChapter 即可)
        viewModel.loadChapter(progress)
    }

    override fun clickSearch() {
        // 弹书内全文搜索 Screen (包装 shared/sharedUiMain 的 SearchContentScreen)
        onOpenSearchContent()
    }

    override fun clickAutoPage() {
        // 启动/取消自动翻页定时 job
        // (app 端用 readView.autoPager.start, iOS 端简化为协程定时调 viewModel.nextPage,
        //  间隔取 readBookConfig.autoReadSpeed (秒, 默认 10, 范围 1-120 由 IosAutoReadPanel 滑条调节))
        autoPage = !autoPage
        if (autoPage) {
            autoPageJob?.cancel()
            autoPageJob = autoPageScope.launch {
                while (isActive) {
                    // 每次循环重读 autoReadSpeed, 让滑条调速即时生效 (无需重启 job)
                    delay(readBookConfig.autoReadSpeed.coerceAtLeast(1) * 1000L)
                    viewModel.nextPage()
                }
            }
        } else {
            autoPageJob?.cancel()
            autoPageJob = null
        }
    }

    override fun clickReplaceRule() {
        // 弹有效替换规则 Dialog (包装 shared/sharedUiMain 的 EffectiveReplacesDialog)
        onOpenReplaceRule()
    }

    override fun clickNightTheme() {
        // 切换夜间主题: 调用方注入 onToggleNightTheme, 内部写 NSUserDefaults themeMode +
        // emitRecreate 触发 AppTheme 重组 (与桌面端 DesktopThemeStoreProvider.toggleDark 对应)
        onToggleNightTheme()
    }

    override fun clickPre() {
        // 上一章 (与 app 端 ReadBook.moveToPrevChapter 对应)
        viewModel.moveToPrevChapter()
    }

    override fun clickNext() {
        // 下一章 (与 app 端 ReadBook.moveToNextChapter 对应)
        viewModel.moveToNextChapter()
    }

    override fun clickCatalog() {
        // 打开目录侧栏 (与 app 端 openChapterList 对应, iOS 端用 ModalNavigationDrawer 实现)
        openCatalog()
    }

    override fun clickReadAloud() {
        // 点击朗读按钮: 弹朗读控制 Dialog (与 app 端 onClickReadAloud 一致)
        // app 端点击朗读按钮会启动/暂停朗读, iOS 端简化为弹 Dialog 让用户控制
        showReadAloudDialog()
    }

    override fun longClickReadAloud() {
        // 长按朗读按钮: 弹朗读控制 Dialog (对照 app 端 ReadBookActivity.longClickReadAloud → showReadAloudDialog)
        showReadAloudDialog()
    }

    override fun clickFont() {
        // 弹阅读样式配置面板 (shared/sharedUiMain 的 ReadConfigPanel, 简化版字号/行距/段距/背景/翻页)
        onOpenReadStyle()
    }

    override fun clickSetting() {
        // 弹更多阅读配置 Screen (包装 shared/sharedUiMain 的 MoreConfigScreen)
        onOpenMoreConfig()
    }
}
