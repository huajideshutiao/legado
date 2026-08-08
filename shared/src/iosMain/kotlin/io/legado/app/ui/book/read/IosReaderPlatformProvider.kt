@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui.book.read

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.showSourceLogin
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isNotShelf
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.image.ReaderImageCache
import io.legado.app.help.toast.Toasters
import io.legado.app.help.tts.IosReadAloudHost
import io.legado.app.help.tts.TtsEngineProvider
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.encodeURI
import io.legado.app.utils.isAbsUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.UIKit.UIDevice
import platform.UIKit.UIImage
import platform.UIKit.UIImageWriteToSavedPhotosAlbum

/**
 * iOS 阅读页平台能力: 菜单可见/可切, 导航经 [AppNavigator] 桥接, 电量用 [UIDevice]。
 *
 * 结构对照 desktop `DesktopReaderPlatformProvider` (顶/底栏 UI 由 shared ReadMenuOverlay
 * 渲染, 此处只持有状态 + 导航回调); 差异仅 getBatteryLevel 用 UIDevice 真实电量。
 *
 * 朗读已接入: 短按经 [IosReadAloudHost] (ReadAloudControllerShared) 启动/暂停/恢复,
 * 长按弹共享朗读控制面板, 退出阅读页停朗读 (iOS 无后台控制面)。
 *
 * # 不实现
 * - 沉浸式色彩: 纯色阅读背景时菜单栏跟随阅读背景色+文字色 (shared createReadMenuColors,
 *   同 desktop); 图片阅读背景/无窗口背景图时用 AppTheme 默认色
 */
object IosReaderPlatformProvider : ReaderPlatformProvider {

    /** 查词请求 (选中词 → 暂存, 由 MainViewController 宿主渲染 DictDialogHost; 对照原版 menu_dict → DictDialog)。 */
    internal var dictWord by mutableStateOf<String?>(null)

    /** 图片长按动作协程 scope (Main: UIKit 操作/toast 需主线程, 网络下载在 loadBytes 内部切 IO)。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = IosReadMenuController(navigator, screenModel)

    // 自动翻页面板停止按钮: 本端 autoPage 仅开关状态 (无 AutoPager), 复位开关即可
    override fun autoPageStop(screenModel: ReaderScreenModel) {
        (screenModel.menuController.state as? IosReadMenuState)?.autoPage = false
    }

    /** 空白长按回落: 原版 ContentTextView.longPress 未命中任何列时无动作，此处 no-op。 */
    override fun onLongPress(screenModel: ReaderScreenModel) = Unit

    /**
     * 图片长按：弹平台原生浮动菜单（查看/刷新/保存到相册；iOS 无"选择目录"概念，
     * 用"保存到相册"代替，动作分发见 [onImageAction]——对标原版 onImageLongPress）。
     */
    override fun onImageLongPress(screenModel: ReaderScreenModel, src: String, x: Float, y: Float) {
        if (src.isBlank()) return
        IosImageActionMenu.show(
            anchorX = x,
            anchorY = y,
            onAction = { action -> onImageAction(screenModel, src, action) },
        )
    }

    /**
     * 页内文字选择完成：弹 UIMenuController 浮动菜单并跟随选区（平台原生实现，
     * 对标 Android 原版 TextActionMenu；动作见 [onTextAction]）。
     */
    override fun onTextSelected(
        screenModel: ReaderScreenModel,
        text: String,
        anchorX: Float,
        anchorY: Float,
    ) {
        if (text.isBlank()) return
        IosTextActionMenu.show(
            anchorX = anchorX,
            anchorY = anchorY,
            onAction = { action -> onTextAction(screenModel, text, action) },
            // 菜单关闭 (动作完成/点外部) → 取消页内选择 (对标原版 onMenuActionFinally)
            onMenuFinally = { ReadBookEvents.postSelectionCancel() },
        )
    }

    /**
     * 页内选区已消失（点按取消选择/翻页/重排等任意路径）：收起 UIMenuController 浮动菜单
     * （对照原版 onCancelSelect → textActionMenu.dismiss）。幂等：菜单未显示时无操作。
     */
    override fun onTextSelectionDismissed(screenModel: ReaderScreenModel) {
        IosTextActionMenu.dismiss()
    }

    /** 阅读页退出: 收起浮动菜单 + 停朗读, 避免残留 (对照原版 onDestroy → textActionMenu.dismiss)。
     *  iOS 无前台 Service/后台控制面, 离开阅读页即无朗读控制入口, 显式停止。 */
    override fun onExit(screenModel: ReaderScreenModel) {
        IosTextActionMenu.dismiss()
        IosReadAloudHost.stop()
    }

    /**
     * 文本菜单动作分发 (对标原版 ReadBookActivity.onMenuItemSelected/onMenuItemClick):
     * 替换/书签/全文搜索/分享走 screenModel 回调; 复制走剪贴板; 查词暂存 dictWord;
     * 浏览器 URL 直开否则系统搜索; 朗读走系统 TTS 引擎 (见 [TtsEngineProvider])。
     */
    private fun onTextAction(screenModel: ReaderScreenModel, text: String, action: String) {
        when (action) {
            "replace" -> {
                val book = screenModel.viewModel.book.value
                AppNavigatorProviders.get().push(
                    AppRoute.ReplaceEdit(
                        pattern = text.lineSequence().joinToString("\n") { it.trim() },
                        scope = listOfNotNull(book?.name, book?.origin).joinToString(";"),
                    )
                )
            }

            "copy" -> PlatformCapabilityProviders.get().copyToClipboard(text)
            "bookmark" -> onBookmark(screenModel, text)
            "aloud" -> {
                // 朗读选中文本: 系统 TTS 引擎 (AVSpeechSynthesizer, 宿主启动经
                // registerIosSystemTtsEngine 注册到 TtsEngineProvider; 未注册时提示)
                val engine = TtsEngineProvider.get()
                if (engine == null) {
                    Toasters.get().toast("朗读引擎未就绪")
                } else {
                    engine.speak(text, "textActionAloud")
                }
            }

            "dict" -> dictWord = text
            "search_content" -> {
                screenModel.searchContentQuery = text
                screenModel.menuState.clickSearch()
            }

            "browser" -> {
                val url = if (text.isAbsUrl()) {
                    text
                } else {
                    "https://www.bing.com/search?q=" + text.encodeURI()
                }
                PlatformCapabilityProviders.get().openExternalUrl(url)
            }

            "share" -> PlatformCapabilityProviders.get().shareText(text)
        }
    }

    /**
     * 图片菜单动作分发 (对标原版 ReadBookActivity.onImageLongPress 的
     * show/refresh/save 三分支; selectFolder 由 iOS"保存到相册"取代):
     * 查看 → 下载解码 + 模态预览; 刷新 → 清内存缓存 + 重排; 保存 → 写系统相册。
     */
    private fun onImageAction(screenModel: ReaderScreenModel, src: String, action: String) {
        when (action) {
            "view" -> previewImage(screenModel, src)
            "refresh" -> {
                // 清共享内存缓存 + 重排 (对照原版 viewModel.refreshImage 的删缓存文件+清内存缓存+loadContent;
                // iOS 阅读页图片走 shared ReaderImageResolver → ReaderImageCache, 磁盘缓存由 Coil3 自管)
                ReaderImageCache.clear()
                ReadBookEvents.postConfig(ReadConfigChange.LOAD_CONTENT)
            }

            "save" -> saveImageToAlbum(screenModel, src)
        }
    }

    /** 查看图片: 下载解码 → 模态预览 (失败 toast; 对照原版 show → PhotoDialog)。 */
    private fun previewImage(screenModel: ReaderScreenModel, src: String) {
        val book = screenModel.viewModel.book.value
        val bookSource = screenModel.viewModel.bookSource.value
        scope.launch {
            val image = loadImage(src, book, bookSource)
            if (image == null) {
                Toasters.get().toast("图片加载失败")
                return@launch
            }
            showIosImagePreview(image)
        }
    }

    /** 保存到相册: 下载解码 → UIImageWriteToSavedPhotosAlbum (无完成回调, 保存后提示)。 */
    private fun saveImageToAlbum(screenModel: ReaderScreenModel, src: String) {
        val book = screenModel.viewModel.book.value
        val bookSource = screenModel.viewModel.bookSource.value
        Toasters.get().toast("正在保存")
        scope.launch {
            val image = loadImage(src, book, bookSource)
            if (image == null) {
                Toasters.get().toast("图片保存失败")
                return@launch
            }
            UIImageWriteToSavedPhotosAlbum(image, null, null, null)
            Toasters.get().toast("已保存到相册")
        }
    }

    /** 下载并解码图片 (ImageBitmapLoader 内部网络/磁盘切 IO, 本 scope 在主线程, 返回即可直接操作 UIKit)。 */
    private suspend fun loadImage(src: String, book: Book?, bookSource: BookSource?): UIImage? {
        val bytes = runCatching { ImageBitmapLoader().loadBytes(src, book, bookSource) }.getOrNull()
            ?: return null
        return runCatching { bytes.toUIImage() }.getOrNull()
    }

    /** 书签 (对照原版 menu_bookmark): 用选中文本建书签, 弹 BookmarkDialog (ReaderRoute 处理 AddBookmark)。 */
    private fun onBookmark(screenModel: ReaderScreenModel, text: String) {
        val book = screenModel.viewModel.book.value ?: return
        val bookmark = Bookmark(bookName = book.name, bookAuthor = book.author).apply {
            chapterIndex = screenModel.viewModel.durChapterIndex.value
            chapterPos = screenModel.viewModel.durChapterPos.value
            chapterName = screenModel.currentChapter?.title ?: ""
            bookText = text.trim()
        }
        screenModel.postDialogEvent(ReaderDialogEvent.AddBookmark(bookmark))
    }

    // UIDevice 电池监控: 返回 0~100, 未启用或未知返回 -1
    override fun getBatteryLevel(): Int {
        val device = UIDevice.currentDevice
        if (!device.batteryMonitoringEnabled) {
            device.batteryMonitoringEnabled = true
        }
        val level = device.batteryLevel
        return if (level < 0f) -1 else (level * 100).toInt()
    }

    /** 朗读控制桥: 长按面板动作落到 [IosReadAloudHost] + 偏好项 (对照 desktop DesktopReadAloudControls)。 */
    override fun readAloudControls(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadAloudControls = IosReadAloudControls(navigator, screenModel)
}

private class IosReadMenuController(
    navigator: AppNavigator,
    screenModel: ReaderScreenModel,
) : ReadMenuController {
    override val state: ReadMenuState = IosReadMenuState(navigator, screenModel)
    override fun showMenu() = (state as IosReadMenuState).show()
    override fun hideMenu() = (state as IosReadMenuState).hide()
}

/**
 * iOS 阅读菜单状态: visibleState 可切, 字段从 screenModel.viewModel 取实时值。
 * 菜单显隐时刷新动态项 (书源按钮/顶栏勾选/夜间态), 对齐 app 端 AndroidReaderMenuState.show()。
 */
private class IosReadMenuState(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
) : ReadMenuState {

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
    // (app 端 ThemeConfig.curBgImagePath), iOS 无此概念恒 false。
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
        get() = !chapterUrl.isNullOrEmpty() && screenModel.currentBook?.isLocal == false
    override var sourceActionText by mutableStateOf("")
        private set
    override var sourceActionVisible by mutableStateOf(false)
        private set
    override val titleBarAdditionVisible: Boolean get() = false
    override val topMenu = TopMenuState()

    // 底栏: 进度条 + 上/下章 + 自动翻页 + 夜间主题
    override val seekMax: Int
        get() = (screenModel.viewModel.simulatedChapterSize - 1).coerceAtLeast(0)
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

    private fun upSourceAction() {
        val book = screenModel.viewModel.book.value
        val source = screenModel.viewModel.bookSource.value
        sourceActionText = source?.bookSourceName ?: "书源"
        sourceActionVisible = book?.let { !it.isLocal } ?: false
    }

    private fun upTopMenu() {
        val book = screenModel.viewModel.book.value ?: return
        topMenu.onLine = !book.isLocal
        topMenu.isLocalTxt = book.isLocalTxt
        topMenu.isEpub = book.isEpub
        topMenu.enableReplaceChecked = book.getUseReplaceRule()
        topMenu.reSegmentChecked = book.config.reSegment
        topMenu.delRubyChecked = book.config.delTag and Book.rubyTag == Book.rubyTag
        topMenu.delHChecked = book.config.delTag and Book.hTag == Book.hTag
        // 去重勾选态 (对照原版 onMenuOpened → menu_same_title_removed.isChecked)
        topMenu.sameTitleRemovedChecked =
            screenModel.viewModel.curTextChapter.value?.sameTitleRemoved == true
        // 云进度同步可见性 (对照原版 onMenuOpened: ReadBook.inBookshelf && AppWebDav.isOk)
        topMenu.syncProgressVisible = !book.isNotShelf && AppWebDavShared.isOk
    }

    override fun onTransitionIdle(shown: Boolean) = Unit
    override fun onBgClick() = hide()

    override fun onChapterViewClick() {
        val book = screenModel.viewModel.book.value ?: return
        if (book.isLocal) return
        val url = chapterUrl.orEmpty()
        // 传原始 chapterUrl (可能含 `,{...}` 请求头) + 书源信息, 由 WebViewRoute 解析
        navigator.push(
            AppRoute.WebView(
                url = url,
                sourceKey = book.origin,
                sourceName = book.originName,
            )
        )
    }

    override fun onChapterViewLongClick() = Unit

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
                // 统一登录入口: URL 登录桌面端直开登录窗口 (2026-08-07); 表单登录弹 Overlay,
                // 带上当前书与当前章 (对照原版 showLogin 预置 IntentData)
                showSourceLogin(
                    source.getKey(),
                    source,
                    screenModel.currentBook,
                    screenModel.currentChapter,
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
            ReadMenuAction.LOG -> screenModel.postDialogEvent(ReaderDialogEvent.Log)

            // ===== 溢出菜单动作 (对照 app 端 AndroidReaderMenuState.onTopMenuAction 各分支,
            // 全部接到 shared 能力: viewModel / 事件广播 / 导航 / 弹窗事件) =====

            // 刷新后续章节 (对照原版 menu_refresh_after → viewModel.refreshContentAfter)
            ReadMenuAction.REFRESH_AFTER -> {
                val book = screenModel.viewModel.book.value ?: return
                screenModel.viewModel.refreshContentAfter(book)
            }

            // 刷新全部 (对照原版 menu_refresh_all → viewModel.refreshContentAll)
            ReadMenuAction.REFRESH_ALL -> screenModel.viewModel.refreshContentAll()

            // 离线缓存 (对照原版 menu_download → DownloadDialog 弹窗 → CacheBookShared.start)
            ReadMenuAction.DOWNLOAD ->
                screenModel.postDialogEvent(ReaderDialogEvent.Download)

            // 本地 TXT 目录正则 (对照原版 menu_toc_regex → TxtTocRule 路由)
            ReadMenuAction.TOC_REGEX -> navigator.push(AppRoute.TxtTocRule)

            // 设置编码 (对照原版 menu_set_charset → CharsetDialog 弹窗 → viewModel.setCharset)
            ReadMenuAction.SET_CHARSET ->
                screenModel.postDialogEvent(ReaderDialogEvent.SetCharset)

            // 翻页动画: 选择器回调忽略索引, 实际动画值在界面设置弹窗配置,
            // 此处直接发配置事件触发重载 (与 app 端 showPageAnimConfigSelector 等价)
            ReadMenuAction.PAGE_ANIM ->
                ReadBookEvents.postConfig(ReadConfigChange.PAGE_ANIM, ReadConfigChange.LOAD_CONTENT)

            // 模拟追读 (对照原版 menu_simulated_reading → SimulatedReadingDialog)
            ReadMenuAction.SIMULATED_READING ->
                screenModel.postDialogEvent(ReaderDialogEvent.SimulatedReading)

            // 启用替换: 翻转 useReplaceRule + 刷新替换规则缓存 + 同章重载
            ReadMenuAction.ENABLE_REPLACE -> screenModel.viewModel.toggleUseReplaceRule()

            // 去重: 翻转当前章去重标记并重载 (shared viewModel 已含未找到重复标题的提示语义)
            ReadMenuAction.SAME_TITLE_REMOVED -> screenModel.viewModel.reverseRemoveSameTitle()

            // 重新分段: 翻转 reSegment + 落库 + 同章重载
            ReadMenuAction.RE_SEGMENT -> screenModel.viewModel.toggleReSegment()

            // 图片样式 (对照原版 menu_image_style → ImageStyleDialog)
            ReadMenuAction.IMAGE_STYLE ->
                screenModel.postDialogEvent(ReaderDialogEvent.ImageStyle)

            // 更新目录: 清解析缓存后回源重拉目录
            ReadMenuAction.UPDATE_TOC -> screenModel.viewModel.updateToc()

            // 云进度手动同步 (上传/同步成功 toast; 项仅在 syncProgressVisible 时渲染)
            ReadMenuAction.SYNC_PROGRESS -> screenModel.viewModel.syncProgressManual(
                uploadSuccessAction = { Toasters.get().toast("上传成功") },
                syncSuccessAction = { Toasters.get().toast("同步成功") },
            )

            // 段评: 弹 shared 底部弹窗 (对照原版 menu_review → ReviewListDialog(book, chapter, 0);
            // 能力已四端接通, 见 ReviewListDialogHost.kt)
            ReadMenuAction.REVIEW -> screenModel.currentBook?.let { book ->
                val chapter = screenModel.currentChapter
                if (!PlatformCapabilityProviders.get().showReviewListDialog(book, chapter, 0)) {
                    Toasters.get().toast("暂不支持段评")
                }
            }

            // 帮助: 原版 showHelp 打开本地 web 帮助页 (依赖 Android 资源), iOS/鸿蒙未移植
            ReadMenuAction.HELP -> Unit

            // epub 去除 ruby/h 标签: 翻转 delTag + 落库 + 全章清缓存重载
            ReadMenuAction.DEL_RUBY_TAG -> screenModel.viewModel.toggleDelTag(Book.rubyTag)
            ReadMenuAction.DEL_H_TAG -> screenModel.viewModel.toggleDelTag(Book.hTag)
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

    override fun clickAutoPage() {
        autoPage = !autoPage
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

    // 朗读短按: 停自动翻页后切换播放/暂停 (对照 app 端 AndroidReaderPlatformProvider.
    // clickReadAloud 的 autoPageStop + 播放/暂停/恢复三态; 引擎经 TtsEngineProvider 注册:
    // IosSystemTtsEngine / HttpTtsPlayer.ios.kt)
    override fun clickReadAloud() {
        // 对照原版 onClickReadAloud 第一行 autoPageStop
        if (autoPage) clickAutoPage()
        // viewModel.toggleReadAloud 内部按 isReadAloudRun/isReadAloudPause 分流:
        // 未运行 → 从当前进度/可视区起点朗读, 暂停 → 恢复, 运行中 → 暂停
        // (经 ReadBookPlatforms → IosReadBookPlatform → IosReadAloudHost)
        screenModel.viewModel.toggleReadAloud()
    }

    // 朗读长按: 弹共享朗读控制面板 (面板动作经 IosReadAloudControls 落到 IosReadAloudHost)
    override fun longClickReadAloud() {
        // 对照原版 ReadAloudDialog 展示需要真实朗读态, 面板按钮全量可用
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

    override fun onRefresh() {
        screenModel.viewModel.refreshCurrentChapter()
    }
}

/**
 * iOS 端朗读控制桥: 面板动作落到 [IosReadAloudHost] + 偏好项。
 *
 * 语速/跟随系统/定时默认值直接读写 PreferKey (与原版 AppConfig 同 key),
 * 对照 desktop `DesktopReadAloudControls`。
 */
private class IosReadAloudControls(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
) : ReadAloudControls {

    private val prefs get() = PreferenceProviders.get()

    override val isPlaying: Boolean get() = !IosReadAloudHost.isPause

    override val timerMinute: Int
        get() = IosReadAloudHost.timeMinute
            .takeIf { it > 0 }
            ?: prefs.getInt(PreferKey.ttsTimer, 0)

    override val speechRate: Int get() = prefs.getInt(PreferKey.ttsSpeechRate, 5)

    override val followSys: Boolean get() = prefs.getBoolean(PreferKey.ttsFollowSys, true)

    override fun playPause() = IosReadAloudHost.toggle()

    override fun stop() = IosReadAloudHost.stop()

    override fun prevChapter() {
        screenModel.viewModel.moveToPrevChapter()
    }

    override fun nextChapter() {
        screenModel.viewModel.moveToNextChapter()
    }

    override fun prevParagraph() = IosReadAloudHost.prevParagraph()

    override fun nextParagraph() = IosReadAloudHost.nextParagraph()

    override fun setTimer(minute: Int) {
        IosReadAloudHost.setTimer(minute)
    }

    override fun setSpeechRate(rate: Int) {
        prefs.putInt(PreferKey.ttsSpeechRate, rate.coerceIn(0, 45))
        IosReadAloudHost.setSpeechRate(rate)
    }

    override fun setFollowSys(follow: Boolean) {
        prefs.putBoolean(PreferKey.ttsFollowSys, follow)
        // 跟随系统时回落默认语速 (对照原版 AppConfig.speechRatePlay)
        IosReadAloudHost.setSpeechRate(if (follow) 5 else speechRate)
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
