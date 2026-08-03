package io.legado.app.ui.book.read

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.SourceLoginContext
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable

/**
 * 鸿蒙端 [ReaderPlatformProvider]: 电池电量经 napi Battery 桥查询 @ohos.batteryInfo,
 * 菜单状态/导航回调对齐 iOS [IosReadMenuState] (visibleState 可切, click 经 navigator/screenModel)。
 *
 * ArkTS 侧 TODO: legado_napi.cpp 实现 registerBatteryCallback + BatteryBridgeHandler.ets
 * 桥未就绪时 getBatteryLevel 返回 -1 (不显示, 同未启用电池监控的 iOS 设备)。
 */
object OhosReaderPlatformProvider : ReaderPlatformProvider {

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = OhosReadMenuController(navigator, screenModel)

    // 经 napi Battery 桥查询 @ohos.batteryInfo.batterySOC; 桥未就绪/超时返回 -1
    override fun getBatteryLevel(): Int {
        if (!OhosNativeBridge.isBatteryBridgeReady()) return -1
        val result = OhosNativeBridge.invokeBatterySync("getLevel") ?: return -1
        val resp = runCatching {
            KS_JSON.decodeFromString(BatteryResponse.serializer(), result)
        }.getOrNull()
        return resp?.level ?: -1
    }
}

@Serializable
private data class BatteryResponse(val level: Int? = null)

private class OhosReadMenuController(
    navigator: AppNavigator,
    screenModel: ReaderScreenModel,
) : ReadMenuController {
    override val state: ReadMenuState = OhosReadMenuState(navigator, screenModel)
    override fun showMenu() = (state as OhosReadMenuState).show()
    override fun hideMenu() = (state as OhosReadMenuState).hide()
}

/**
 * 鸿蒙阅读菜单状态: visibleState 可切, 字段从 screenModel.viewModel 取实时值。
 * 菜单显隐时刷新动态项 (书源按钮/顶栏勾选/夜间态), 对齐 iOS [IosReadMenuState].show()。
 */
private class OhosReadMenuState(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
) : ReadMenuState {

    override val visibleState = MutableTransitionState(false)
    override var animate: Boolean = true
        private set
    override val isVisible: Boolean get() = visibleState.currentState || visibleState.targetState
    override val canShowMenu: Boolean get() = true

    // 无沉浸式阅读背景, 用 AppTheme 默认色 (同 iOS/desktop)
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

            // 模拟阅读 (对照原版 menu_simulated_reading → SimulatedReadingDialog)
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

            // 段评: iOS/鸿蒙无 ReviewListDialog (Android 专属 Fragment), 且 reviewVisible 恒 false
            // 不渲染该项; 保留分支仅为穷尽枚举
            ReadMenuAction.REVIEW -> Unit

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
            ),
            resultKey = RouteResults.SEARCH_CONTENT,
        )
    }

    // 自动翻页: 切换状态 (对照 iOS IosReadMenuState.clickAutoPage, 无 ReadAloud.stop)
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

    // TODO: 待 ReadAloudControllerShared 接入阅读页后启动朗读 (同 iOS IosReadMenuState.clickReadAloud)
    override fun clickReadAloud() = Unit

    override fun longClickReadAloud() {
        // 对照原版 朗读按钮长按 → ReadAloudDialog
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
