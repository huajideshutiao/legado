package io.legado.desktop.ui.platform

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.SourceLoginContext
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.sourceLoginOverlayPayload
import io.legado.app.ui.book.read.ReadAloudControls
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.ReadMenuAction
import io.legado.app.ui.book.read.ReadMenuColors
import io.legado.app.ui.book.read.ReadMenuController
import io.legado.app.ui.book.read.ReadMenuState
import io.legado.app.ui.book.read.ReaderDialogEvent
import io.legado.app.ui.book.read.ReaderPlatformProvider
import io.legado.app.ui.book.read.ReaderScreenModel
import io.legado.app.ui.book.read.SourceAction
import io.legado.app.ui.book.read.TopMenuState
import io.legado.app.ui.book.read.createReadMenuColors
import io.legado.app.ui.book.read.page.AutoPagerCompose
import io.legado.app.ui.dict.DictDialogHost
import io.legado.app.ui.reader.TextSelectionDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef
import io.legado.desktop.help.DesktopBattery
import io.legado.desktop.help.tts.DesktopReadAloudHost
import io.legado.desktop.ui.DesktopDialogRequest
import io.legado.desktop.ui.DesktopDialogs
import io.legado.desktop.ui.DesktopPlatformCapabilities
import io.legado.desktop.ui.readerWindowTint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * desktop 端 [ReaderPlatformProvider] 真实实现: 菜单可见/可切, 导航经 [AppNavigator] 桥接。
 *
 * 对照 app 端 [io.legado.app.ui.book.read.AndroidReaderPlatformProvider]:
 * - createMenuController: 返回真实 [DesktopReadMenuController] (visibleState 可切, 非恒 false)
 * - getBatteryLevel: Windows 经 kernel32 (JNA) / macOS 经 `pmset -g batt` /
 *   Linux 经 sysfs BAT/
capacity 读真实电量, 失败 -1 (信息条不显示电量, 正常降级)
 * - 顶/底栏菜单 UI 由 shared [io.legado.app.ui.book.read.ReadMenuOverlay] 渲染, 此处只持有状态
 * - 导航回调 (clickCatalog/clickSearch/clickFont/clickSetting 等) 经 [AppNavigator] 跳 shared Route
 * - 章节导航 (clickPre/clickNext/onSeekStop) 委托 [ReaderScreenModel.viewModel]
 *
 * # 不实现 (与 app 端差异)
 * - ReadAloud (朗读): app 端走 ReadAloud + BaseReadAloudService, desktop 无 Service,
 *   改由 [DesktopReadAloudHost] 驱动 ReadAloudControllerShared; 长按弹共享朗读控制面板
 * - ThemeConfig.applyDayNight: app 端切夜间主题后调 Activity 重启 UI, desktop 经
 *   [io.legado.app.help.config.ThemeConfigProviders] (FileThemeConfigProvider.applyDayNight)
 *   写 ThemeStore 色 + themeMode 并 emit RECREATE, AppTheme 重组重读新色
 * - 沉浸式色彩 (immersive/bgColor/textColor): 对照原版 ReadMenu.upColorConfig, 纯色阅读背景时
 *   菜单栏跟随阅读背景色+文字色 (shared createReadMenuColors); 图片阅读背景时走 AppTheme 默认色
 */
class DesktopReaderPlatformProvider : ReaderPlatformProvider {

    /** 长按文字选择请求 (对照 app 端 MainActivity.readerSelection, 由 [TextSelectionHost] 渲染)。 */
    internal var readerSelection by mutableStateOf<ReaderTextSelection?>(null)
        private set

    /** 窗口标题栏着色协程 (阅读页激活期间订阅背景色变化, 见 [readerWindowTint])。 */
    private var titleBarTintJob: Job? = null

    /** 标题栏着色协程作用域 (Main)。 */
    private val titleBarScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 触发标题栏着色刷新的配置变更集合。 */
    private val titleBarTintChanges = setOf(
        ReadConfigChange.BG,
        ReadConfigChange.BG_ALPHA,
        ReadConfigChange.STYLE,
    )

    /** 查词请求 (TextSelectionDialog 查词按钮 → onDict 回调暂存, 由 [TextSelectionHost] 渲染词典对话框)。 */
    internal var dictWord by mutableStateOf<String?>(null)
        private set

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = DesktopReadMenuController(navigator, screenModel)

    // 自动翻页面板停止按钮 (对照 app 端 autoPageStop → stopAutoPage: 停控制器 + 复位开关)
    override fun autoPageStop(screenModel: ReaderScreenModel) {
        (screenModel.menuController.state as? DesktopReadMenuState)?.stopAutoPage()
    }

    override fun getBatteryLevel(): Int = DesktopBattery.getBatteryLevel()

    /**
     * 阅读页进入: 把窗口系统标题栏着色为阅读背景色 (视为状态栏着色,
     * 对照 app 端状态栏随阅读背景变化; 用户要求 2026-08-06)。
     * 订阅配置变更实时刷新; 阅读页退出 ([onExit]) 时清除回落 AppTheme 主题色。
     */
    override fun onEnter(screenModel: ReaderScreenModel) {
        titleBarTintJob?.cancel()
        titleBarTintJob = titleBarScope.launch {
            fun updateTint() {
                val color = runCatching {
                    ReadBookConfigProviders.get().config.curBgColor()
                }.getOrNull() ?: return
                // 标题栏不着半透明, 取不透明阅读背景色
                readerWindowTint.value = Color(color).copy(alpha = 1f)
            }
            updateTint()
            ReadBookEvents.configChange.collect { changes ->
                if (changes.any { it in titleBarTintChanges }) updateTint()
            }
        }
    }

    /** 阅读页退出: 清除标题栏着色, 回落 AppTheme 主题色。 */
    override fun onExit(screenModel: ReaderScreenModel) {
        titleBarTintJob?.cancel()
        titleBarTintJob = null
        readerWindowTint.value = null
    }

    override fun onLongPress(screenModel: ReaderScreenModel) {
        // 对照 app 端 AndroidReaderPlatformProvider.onLongPress: 携带章节名 + 整章正文,
        // 由共享 TextSelectionDialog (SelectionContainer 包 Text) 承载拖选/复制/查词。
        // 文字长按已由页内选择接管 (ReadViewComposable → onTextSelected); 此处为图片/
        // 空白长按回落路径。
        readerSelection = ReaderTextSelection(
            chapterName = screenModel.currentChapter?.title.orEmpty(),
            content = screenModel.currentChapterText,
            onReplace = onReplace(screenModel),
            onBookmark = onBookmark(screenModel),
            onReadAloud = onReadAloud(screenModel),
            onSearchContent = onSearchContent(screenModel),
            onShare = onShare(screenModel),
        )
    }

    /** 页内文字选择完成: 复用共享 [TextSelectionDialog], 注入选中文本 (锚点坐标桌面端对话框形态不使用)。
     *  对话框独立承载选中文本, 页内高亮不再需要, 立即清除选择 (移动端浮动菜单是
     *  onMenuActionFinally 才清的原版语义, 桌面端对话框形态直接清, 2026-08-06 用户要求)。 */
    override fun onTextSelected(
        screenModel: ReaderScreenModel,
        text: String,
        anchorX: Float,
        anchorY: Float,
    ) {
        if (text.isBlank()) return
        // 弹对话框即清除页内选择 (读选区已完成, 高亮不再有意义且干扰后续点按)
        ReadBookEvents.postSelectionCancel()
        readerSelection = ReaderTextSelection(
            chapterName = screenModel.currentChapter?.title.orEmpty(),
            content = screenModel.currentChapterText,
            selectedText = text,
            onReplace = onReplace(screenModel),
            onBookmark = onBookmark(screenModel),
            onReadAloud = onReadAloud(screenModel),
            onSearchContent = onSearchContent(screenModel),
            onShare = onShare(screenModel),
        )
    }

    /** 图片长按: 桌面端无图片菜单, 维持整章选择对话框回落 (与空白长按一致) */
    override fun onImageLongPress(
        screenModel: ReaderScreenModel,
        src: String,
        x: Float,
        y: Float,
    ) {
        onLongPress(screenModel)
    }

    /** 替换 (对照 app 端 menu_replace): 打开替换规则编辑页, pattern=选中文本(去行首尾空白), scope=书名;书源URL */
    private fun onReplace(screenModel: ReaderScreenModel): (String) -> Unit = { text ->
        val book = screenModel.viewModel.book.value
        AppNavigatorProviders.get().push(
            AppRoute.ReplaceEdit(
                pattern = text.lineSequence().joinToString("\n") { it.trim() },
                scope = listOfNotNull(book?.name, book?.origin).joinToString(";"),
            )
        )
    }

    /** 书签 (对照原版 menu_bookmark): 用选中文本建书签, 弹 BookmarkDialog (ReaderRoute 处理 AddBookmark) */
    private fun onBookmark(screenModel: ReaderScreenModel): (String) -> Unit = onBookmark@{ text ->
        val book = screenModel.viewModel.book.value ?: return@onBookmark
        val bookmark = Bookmark(bookName = book.name, bookAuthor = book.author).apply {
            chapterIndex = screenModel.viewModel.durChapterIndex.value
            chapterPos = screenModel.viewModel.durChapterPos.value
            chapterName = screenModel.currentChapter?.title ?: ""
            bookText = text.trim()
        }
        screenModel.postDialogEvent(ReaderDialogEvent.AddBookmark(bookmark))
    }

    /** 朗读选中文字 (对照 app 端 menu_aloud 的 contentSelectSpeakMod==1 分支语义: 桌面无单句 TTS, 从当前进度朗读章节) */
    private fun onReadAloud(screenModel: ReaderScreenModel): (String) -> Unit = { _ ->
        DesktopReadAloudHost.play()
    }

    /** 全文搜索 (对照原版 menu_search_content): 设置搜索词后走既有搜索路由 */
    private fun onSearchContent(screenModel: ReaderScreenModel): (String) -> Unit = { text ->
        screenModel.searchContentQuery = text
        screenModel.menuState.clickSearch()
    }

    /** 分享 (对照原版 menu_share_str; 桌面端无系统分享 UI, 写剪贴板) */
    private fun onShare(screenModel: ReaderScreenModel): (String) -> Unit = { text ->
        DesktopPlatformCapabilities.shareText(text)
    }

    /**
     * 长按文字选择对话框宿主 (挂在桌面 Compose 根, 对照 app 端 MainActivity 的
     * `readerSelection?.let { TextSelectionDialog(...) }` 分支)。剪贴板读写 / 打开外链
     * 走 [DesktopPlatformCapabilities], 复用共享 [TextSelectionDialog] 不另写一套。
     */
    @Composable
    fun TextSelectionHost() {
        val selection = readerSelection
        if (selection != null) {
            TextSelectionDialog(
                chapterName = selection.chapterName,
                content = selection.content,
                onDismiss = { readerSelection = null },
                clipTextProvider = { DesktopPlatformCapabilities.getClipboardText() },
                clipTextSink = { DesktopPlatformCapabilities.copyToClipboard(it) },
                openUrl = { DesktopPlatformCapabilities.openExternalUrl(it) },
                // 查词: 打开共享词典查询对话框 (对照原版 TextActionMenu menu_dict → DictDialog)
                onDict = { dictWord = it },
                selectedText = selection.selectedText,
                onReplace = selection.onReplace,
                onBookmark = selection.onBookmark,
                onReadAloud = selection.onReadAloud,
                onSearchContent = selection.onSearchContent,
                onShare = selection.onShare,
            )
        }
        // 查词对话框 (选中词/剪贴板词 → 词典查询, 本地/在线词典规则)
        val word = dictWord
        if (word != null) {
            DictDialogHost(
                word = word,
                onDismiss = { dictWord = null },
            )
        }
    }

    override fun readAloudControls(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadAloudControls = DesktopReadAloudControls(navigator, screenModel)
}

/** 长按文字选择请求载荷: 章节名 + 整章正文 (+ 可选页内选中文本, null=整章拖选形态) +
 *  动作回调 (对照 app 端 MainActivity.ReaderTextSelection, 由选中对话框按钮触发)。 */
internal data class ReaderTextSelection(
    val chapterName: String,
    val content: String,
    val selectedText: String? = null,
    val onReplace: (String) -> Unit = {},
    val onBookmark: (String) -> Unit = {},
    val onReadAloud: (String) -> Unit = {},
    val onSearchContent: (String) -> Unit = {},
    val onShare: (String) -> Unit = {},
)

/**
 * 桌面端朗读控制桥: 面板动作落到 [DesktopReadAloudHost] + 偏好项。
 *
 * 语速/跟随系统/定时默认值直接读写 PreferKey (与原版 AppConfig 同 key), 桌面端没有
 * AppConfig setter, 与 [DesktopReadMenuState] 写 themeMode 的做法一致。
 */
private class DesktopReadAloudControls(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
) : ReadAloudControls {

    private val prefs get() = PreferenceProviders.get()

    override val isPlaying: Boolean get() = !DesktopReadAloudHost.isPause

    override val timerMinute: Int
        get() = DesktopReadAloudHost.timeMinute
            .takeIf { it > 0 }
            ?: prefs.getInt(PreferKey.ttsTimer, 0)

    override val speechRate: Int get() = prefs.getInt(PreferKey.ttsSpeechRate, 5)

    override val followSys: Boolean get() = prefs.getBoolean(PreferKey.ttsFollowSys, true)

    override fun playPause() = DesktopReadAloudHost.toggle()

    override fun stop() = DesktopReadAloudHost.stop()

    override fun prevChapter() {
        screenModel.viewModel.moveToPrevChapter()
    }

    override fun nextChapter() {
        screenModel.viewModel.moveToNextChapter()
    }

    override fun prevParagraph() = DesktopReadAloudHost.prevParagraph()

    override fun nextParagraph() = DesktopReadAloudHost.nextParagraph()

    override fun setTimer(minute: Int) {
        DesktopReadAloudHost.setTimer(minute)
    }

    override fun setSpeechRate(rate: Int) {
        prefs.putInt(PreferKey.ttsSpeechRate, rate.coerceIn(0, 45))
        DesktopReadAloudHost.setSpeechRate(rate)
    }

    override fun setFollowSys(follow: Boolean) {
        prefs.putBoolean(PreferKey.ttsFollowSys, follow)
        // 跟随系统时回落默认语速 (对照原版 AppConfig.speechRatePlay)
        DesktopReadAloudHost.setSpeechRate(if (follow) 5 else speechRate)
    }

    override fun openChapterList() {
        // 对照原版 朗读面板目录按钮 → TocDialog 底部弹窗
        screenModel.postDialogEvent(ReaderDialogEvent.Toc)
    }

    override fun openSettings() {
        // 对照原版 ReadAloudDialog 设置按钮 → ReadAloudConfigDialog
        screenModel.postDialogEvent(ReaderDialogEvent.ReadAloudConfig)
    }

    override fun toBackstage() {
        navigator.pop()
    }
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

    /** 自动翻页控制器协程作用域 (对照 app 端 AndroidReaderMenuState.autoPageScope, Main)。 */
    private val autoPageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val visibleState = MutableTransitionState(false)
    override var animate: Boolean = true
        private set
    override val isVisible: Boolean get() = visibleState.currentState || visibleState.targetState
    override val canShowMenu: Boolean get() = true

    // 菜单栏配色 (对照原版 ReadMenu.upColorConfig, 逻辑见 shared createReadMenuColors):
    // 纯色阅读背景时 immersive=true, 顶/底栏用阅读背景色(含 bgAlpha 透明度)+阅读文字色,
    // 文字对比度由阅读配色自身保证; 图片阅读背景时沉浸式为 false, ReadMenuOverlay 走
    // AppTheme 默认色 (与原版非沉浸式行为一致)。fallbackBgColor 传 0: 非沉浸式时
    // Composable 不消费 bgColor, 仅沉浸式解析失败时兜底。hasBgImage 语义为「窗口背景图」
    // (app 端 ThemeConfig.curBgImagePath), 桌面端无此概念恒 false。
    private val menuTheme: ReadMenuColors
        get() = createReadMenuColors(ReadBookConfigProviders.get().config, fallbackBgColor = 0)
    override val immersive: Boolean get() = menuTheme.immersive
    override val bgColor: Int get() = menuTheme.bgColor
    override val textColor: Int get() = menuTheme.textColor

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

    // 顶栏下方的章节名/章节链接行 (对照 app 端 AndroidReaderMenuState:
    // titleBarAdditionVisible = AppConfig.showReadTitleBarAddition, 默认 true;
    // 桌面端无对应设置页入口, 读同一 pref, 缺失回落 true 恢复显示)
    override val titleBarAdditionVisible: Boolean
        get() = runCatching {
            PreferenceProviders.get().getBoolean(PreferKey.showReadTitleAddition, true)
        }.getOrDefault(true)
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
        // 长按可切换浏览器/应用内打开方式 (对照 app 端 ReadMenu.onChapterViewClick)
        if (PreferenceProviders.get().getBoolean(PreferKey.readUrlOpenInBrowser, false)) {
            DesktopPlatformCapabilities.openExternalUrl(url.substringBefore(",{"))
            return
        }
        // 传原始 chapterUrl (可能含 `,{...}` 请求头) + 书源信息, 由 WebViewRoute 解析
        navigator.push(
            AppRoute.WebView(
                url = url,
                sourceKey = book.origin,
                sourceName = book.originName,
            )
        )
    }

    // 章节链接长按: 弹选择框切换浏览器/应用内打开 (对照 app 端 ReadMenu.onChapterViewLongClick
    // 的 activity.alert, 写同一 PreferKey.readUrlOpenInBrowser)
    override fun onChapterViewLongClick() {
        val book = screenModel.viewModel.book.value ?: return
        if (book.isLocal) return
        DesktopDialogs.show(
            DesktopDialogRequest.Confirm(
                title = "打开方式",
                message = "是否使用外部浏览器打开？",
                okText = "是",
                noText = "否",
                onOk = {
                    PreferenceProviders.get().putBoolean(PreferKey.readUrlOpenInBrowser, true)
                },
                onNo = {
                    PreferenceProviders.get().putBoolean(PreferKey.readUrlOpenInBrowser, false)
                },
            )
        )
    }

    override fun onOverflowOpened() {
        screenModel.updateSourceMenu()
    }

    override fun sourceLoginVisible(): Boolean = screenModel.sourceLoginVisible()

    // 购买按钮显示条件已下沉 ReaderScreenModel.sourcePayVisible (对照原版 ReadMenu)
    override fun sourcePayVisible(): Boolean = screenModel.sourcePayVisible()

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
                // 纯 Overlay 弹登录对话框, 不推新路由; 表单/URL 两条分支由
                // SourceLoginOverlayContent 统一分发 (对照原版 showLoginDialog)
                navigator.showOverlay(
                    AppOverlay.Dialog(
                        key = "sourceLogin",
                        payload = sourceLoginOverlayPayload(source.getKey(), dataKey),
                    )
                )
            }

            SourceAction.EDIT_SOURCE -> {
                val origin = screenModel.viewModel.book.value?.origin ?: return
                navigator.push(AppRoute.BookSourceEdit(origin), RouteResults.BOOK_SOURCE_EDIT)
            }

            SourceAction.DISABLE_SOURCE -> screenModel.viewModel.disableSource()

            // 购买当前章: 确认弹窗由 ReaderRoute ChapterPay 渲染, 确认后执行书源 payAction JS
            // (对照原版 ReadMenu menu_chapter_pay -> payAction)
            SourceAction.CHAPTER_PAY ->
                screenModel.postDialogEvent(ReaderDialogEvent.ChapterPay)

            // 源/书变量编辑 (对照原版 ReadMenu showSourceVariableDialog/showBookVariableDialog)
            SourceAction.SET_SOURCE_VARIABLE -> screenModel.showSourceVariableDialog()
            SourceAction.SET_BOOK_VARIABLE -> screenModel.showBookVariableDialog()
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
            ReadMenuAction.BOOK_CHANGE_SOURCE -> {
                // 对照原版 换源 → ChangeBookSourceDialog 底部弹窗
                screenModel.postDialogEvent(ReaderDialogEvent.ChangeSource)
            }

            ReadMenuAction.CHAPTER_CHANGE_SOURCE -> {
                // 对照原版 章节换源 → ChangeChapterSourceDialog 底部弹窗
                screenModel.postDialogEvent(ReaderDialogEvent.ChangeChapterSource)
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
            ReadMenuAction.REVIEW -> screenModel.currentBook?.let { book ->
                val chapter = screenModel.currentChapter
                if (!PlatformCapabilityProviders.get().showReviewListDialog(book, chapter, 0)) {
                    navigator.push(AppRoute.ReviewList(book.toRouteRef()))
                }
            }

            ReadMenuAction.LOG -> screenModel.postDialogEvent(ReaderDialogEvent.Log)
            else -> Unit
        }
    }

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
                book = screenModel.viewModel.book.value?.toRouteRef(),
            ),
            resultKey = RouteResults.SEARCH_CONTENT,
        )
    }

    // 自动翻页: 切换状态 + 启停控制器 (对照 app 端 AndroidReaderMenuState.clickAutoPage;
    // 桌面端无 ReadAloud 单例, 朗读与自动翻页共用翻页动作, 无需先停朗读)
    override fun clickAutoPage() {
        if (autoPage) {
            stopAutoPage()
        } else {
            startAutoPage()
        }
    }

    /** 自动翻页控制器 (对照 app 端 AndroidReaderMenuState.autoPager, shared AutoPagerCompose 承载)。 */
    private var autoPager: AutoPagerCompose? = null

    /**
     * 启动自动翻页: 三模式语义与 app 端一致 (E-Ink 定时整页翻 / 非 E-Ink 揭示动画覆盖层 /
     * 滚动模式连续滚动), 由 shared [AutoPagerCompose] 驱动 [ReadBookViewModelShared].
     * 翻到全书末尾自动停 (pager.onEnd)。
     */
    private fun startAutoPage() {
        stopAutoPage()
        autoPage = true
        autoPager = AutoPagerCompose(
            viewModel = screenModel.viewModel,
            scope = autoPageScope,
            // 每拍现读速度配置 (对照原版每次 postDelayed 现取 ReadBookConfig.autoReadSpeed)
            autoReadSpeed = {
                ReadBookConfigProviders.get().autoReadSpeed.coerceAtLeast(1)
            },
        ).also { pager ->
            pager.onEnd = { stopAutoPage() }
            pager.start()
        }
    }

    /** 停止自动翻页: 复位控制器 + 复位开关 (对照 app 端 stopAutoPage)。 */
    fun stopAutoPage() {
        autoPager?.stop()
        autoPager = null
        autoPage = false
    }

    override fun clickReplaceRule() {
        // 对照原版 openReplaceRule → EffectiveReplacesDialog (runMenuOut 先收菜单)
        hide()
        screenModel.postDialogEvent(ReaderDialogEvent.EffectiveReplaces)
    }

    // 夜间主题切换 (对照 app 端 clickNightTheme, 经 ThemeConfigProviders 应用主题色 + 触发重组)
    override fun clickNightTheme() {
        val newNight = !isNightTheme
        ThemeConfigProviders.get().applyDayNight(newNight)
        isNightTheme = newNight
    }

    override fun clickPre() {
        screenModel.viewModel.moveToPrevChapter()
    }

    override fun clickNext() {
        screenModel.viewModel.moveToNextChapter()
    }

    override fun clickCatalog() {
        // 对照原版 目录按钮 → TocDialog 底部弹窗 (runMenuOut 先收菜单)
        hide()
        screenModel.postDialogEvent(ReaderDialogEvent.Toc)
    }

    // 桌面端短按直接打开面板并开始/继续朗读。
    override fun clickReadAloud() {
        if (!DesktopReadAloudHost.isRun || DesktopReadAloudHost.isPause) {
            DesktopReadAloudHost.toggle()
        }
        screenModel.postDialogEvent(ReaderDialogEvent.ReadAloud)
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
