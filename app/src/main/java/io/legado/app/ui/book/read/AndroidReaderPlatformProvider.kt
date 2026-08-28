package io.legado.app.ui.book.read

import android.app.DatePickerDialog
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.legado.app.App
import io.legado.app.BuildConfig
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.AppWebDav
import io.legado.app.help.TTS
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.showSourceLogin
import io.legado.app.help.storage.Backup
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.AutoPagerCompose
import io.legado.app.ui.compose.component.AppAutoCompleteField
import io.legado.app.ui.compose.component.AppNumberField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.reader.ReaderTextActionMenu
import io.legado.app.ui.reader.ReaderTextActions
import io.legado.app.ui.reader.ReaderTextSelectionRequest
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.route.encodeReviewListDialogPayload
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.openUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AndroidReaderPlatformProvider(
    private val activity: MainActivity,
) : ReaderPlatformProvider {

    // 当前阅读页菜单状态 (路由内唯一), 供 onPause/onExit 停止自动翻页;
    // 记 screenModel 用于校验归属, 避免旧路由 onExit 误停新路由的自动翻页
    private var activeMenuState: Pair<ReaderScreenModel, AndroidReaderMenuState>? = null

    /** 页内文字选择请求 (null = 不显示自绘浮动菜单), 由 [TextSelectionHost] 渲染。 */
    private var textSelection by mutableStateOf<ReaderTextSelectionRequest?>(null)

    /** 当次选择的动作集: 动作要 screenModel, 故在 onTextSelected 装配好存下。 */
    private var textActions by mutableStateOf<ReaderTextActions?>(null)

    // Activity 生命周期观察者：桥接到 ReaderScreenModel.onPause/onResume (对照 app 端 ReadBookActivity.onPause/onResume)
    // onEnter 注册、onExit 解注册；ON_PAUSE 落库+取消预下载+停自动翻页，ON_RESUME 留扩展点
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    // 时间/电池广播接收器: 阅读页打开期间注册, 桥接 ACTION_TIME_TICK / ACTION_BATTERY_CHANGED → ReadBookEvents
    private var batteryReceiver: BroadcastReceiver? = null

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = AndroidReaderMenuController(navigator, screenModel, activity).also {
        activeMenuState =
            (it.state as? AndroidReaderMenuState)?.let { state -> screenModel to state }
    }

    // ===== 自动翻页面板平台动作 (对照原版 AutoReadDialog 的 CallBack + 平台副作用) =====

    override fun autoPageStop(screenModel: ReaderScreenModel) {
        activeMenuState?.takeIf { it.first === screenModel }?.second?.stopAutoPage()
    }

    override fun showPageAnimConfig(screenModel: ReaderScreenModel) {
        activeMenuState?.takeIf { it.first === screenModel }?.second?.showPageAnimConfigSelector()
    }

    override fun upTtsSpeechRate(screenModel: ReaderScreenModel) {
        activeMenuState?.takeIf { it.first === screenModel }?.second?.upTtsSpeechRate()
    }

    override fun getBatteryLevel(): Int {
        // 读取失败/无电池统一回落 100 (用户拍板 2026-08: 电量恒显示, 与 desktop 一致)
        val manager = App.instance.getSystemService(BatteryManager::class.java) ?: return 100
        return runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(100)
    }

    /**
     * 空白长按回落：原版 ContentTextView.longPress 未命中任何列时无动作，此处 no-op。
     * （文字长按由页内选择接管走 [onTextSelected]，图片长按走 [onImageLongPress]。）
     */
    override fun onLongPress(screenModel: ReaderScreenModel) = Unit

    /**
     * 页内文字选择完成（长按选中文字后抬起）：弹自绘浮动文本操作菜单
     * （见共享 [ReaderTextActionMenu]，宿主 [TextSelectionHost] 挂在 MainActivity 根组合）。
     *
     * 原本桥接 MainActivity 的 TextActionMenu（ActionMode.TYPE_FLOATING，系统样式），
     * 已换成与详情页/输入框同一套自绘弹层，app 端那份连同 CallBack 桥接一并删除。
     *
     * 锚点：ReadViewComposable 上报的是窗口坐标（页内坐标已折算滚动 + 页眉 + 状态栏，
     * 与同树内的 SelectionHandleOverlay 同源）；阅读页铺满窗口、宿主也在同一 Compose 根，
     * 故 Popup 的 anchorBounds 即窗口原点，直接用不会重复叠加系统栏。
     * 取锚点周围 40px 方块当选区矩形（对照原版 showReaderTextActionMenu 的 ±20px）。
     */
    override fun onTextSelected(
        screenModel: ReaderScreenModel,
        text: String,
        anchorX: Float,
        anchorY: Float,
    ) {
        if (text.isBlank()) return
        textActions = ReaderTextActions(
            onReplace = screenModel.replaceTextCallback(),
            onCopy = { activity.sendToClip(it) },
            onBookmark = screenModel.bookmarkTextCallback(),
            onReadAloud = onReadAloud(screenModel),
            onDict = { activity.showDictWord(it) },
            onSearchContent = screenModel.searchContentTextCallback(),
            onBrowser = ::openInBrowser,
            onShare = onShare(screenModel),
        )
        textSelection = ReaderTextSelectionRequest(
            text = text,
            anchor = Rect(anchorX - 20f, anchorY - 20f, anchorX + 20f, anchorY + 20f),
        )
    }

    /**
     * 阅读页文本操作菜单宿主：挂在 MainActivity 根组合（对照桌面
     * DesktopReaderPlatformProvider.TextSelectionHost）。
     */
    @Composable
    fun TextSelectionHost() {
        val actions = textActions ?: return
        ReaderTextActionMenu(
            request = textSelection,
            actions = actions,
            // 对照原版 onMenuActionFinally：关菜单 + 取消页内选择
            onFinally = {
                textSelection = null
                ReadBookEvents.postSelectionCancel()
            },
        )
    }

    /** 浏览器 (对照原版 TextActionMenu.menu_browser)：URL 直接打开，非 URL 走系统搜索 */
    private fun openInBrowser(text: String) {
        runCatching {
            val intent = if (text.isAbsUrl()) {
                Intent(Intent.ACTION_VIEW).apply { data = text.toUri() }
            } else {
                Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra(SearchManager.QUERY, text) }
            }
            activity.startActivity(intent)
        }.onFailure {
            it.printOnDebug()
            activity.toastOnUi(it.localizedMessage ?: "ERROR")
        }
    }

    /**
     * 页内选区已消失（点按取消选择/翻页/重排等任意路径）：收起浮动文本操作菜单
     * （对照原版 onCancelSelect → textActionMenu.dismiss）。幂等：菜单未显示时无操作。
     */
    override fun onTextSelectionDismissed(screenModel: ReaderScreenModel) {
        // 对照 master ReadBookActivity.cancelSelect: 文本/图片菜单互斥, 同时 dismiss
        textSelection = null
        activity.dismissImageActionMenu()
    }

    /**
     * 同步立即关闭浮动文本操作菜单（点按取消选择等手势分支在选区清除的同帧同步直调）。
     * 幂等，事件链兜底重复调用安全。
     */
    override fun dismissTextActionMenu(screenModel: ReaderScreenModel) {
        // 对照 master ReadBookActivity.cancelSelect: 文本/图片菜单互斥, 同时 dismiss
        textSelection = null
        activity.dismissImageActionMenu()
    }

    /**
     * 图片长按（命中图片列）：弹图片操作菜单（对照原版 ReadBookActivity.onImageLongPress：
     * 查看/刷新/保存/选择目录，现走 ReaderImageActionMenu 自绘浮动菜单与文本菜单同款）。
     */
    override fun onImageLongPress(
        screenModel: ReaderScreenModel,
        src: String,
        x: Float,
        y: Float,
    ) {
        activity.showImageActionMenu(src, x, y)
    }

    /** 朗读选中文字 (对照原版 menu_aloud): 默认模式 TTS 朗读选中文本;
     *  contentSelectSpeakMod=1 的 aloudStartSelect(从选中处朗读章节) 依赖 View 层选区位置,
     *  Compose 阅读页无等价能力, 回落为从当前进度开始章节朗读 */
    private fun onReadAloud(screenModel: ReaderScreenModel): (String) -> Unit = { text ->
        when (AppConfig.contentSelectSpeakMod) {
            1 -> {
                ReadAloud.upReadAloudClass()
                ReadAloud.play(activity)
            }

            else -> TTS().speak(text)
        }
    }

    /** 分享 (对照原版 menu_share_str) */
    private fun onShare(screenModel: ReaderScreenModel): (String) -> Unit = { text ->
        activity.share(text)
    }

    /** 屏幕超时设置变更 → 重算常亮计时 (对照原版 keepLightChange → upScreenTimeOut) */
    override fun onKeepLightChange(screenModel: ReaderScreenModel) {
        activity.upScreenTimeOut()
    }

    override fun onEnter(screenModel: ReaderScreenModel) {
        // 幂等: 重复进入时先注销旧注册 (上一轮异常路径可能未走 onExit),
        // 否则旧 receiver 永不注销, ACTION_TIME_TICK/ACTION_BATTERY_CHANGED 按泄漏份数重复触发
        batteryReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
        batteryReceiver = null
        lifecycleObserver?.let { activity.lifecycle.removeObserver(it) }
        lifecycleObserver = null
        // 同样幂等重建页面变化订阅: 上一轮 onExit 已取消, 复用同一菜单状态时须重起
        activeMenuState?.takeIf { it.first === screenModel }?.second?.startPageChangedWatch()
        activity.enterReaderWindow()
        // 注册时间/电池广播: 桥接系统广播到 ReadBookEvents (对照原版 TimeBatteryReceiver),
        // 时间/电池槽位由 shared ReaderScreenModel 订阅对应事件刷新
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_TIME_TICK -> ReadBookEvents.postTimeChanged()
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        ReadBookEvents.postBatteryChanged(level)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            },
            // 系统受保护广播只能由系统发出, 无需导出给其他应用 (RECEIVER_EXPORTED 会放大攻击面)
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        batteryReceiver = receiver
        // 注册生命周期观察者: 桥接 Activity onPause/onResume 到 shared ScreenModel
        val observer = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                // 对照原版 onPause → autoPageStop: 退后台停自动翻页
                activeMenuState?.second?.stopAutoPage()
                screenModel.onPause()
                // 对照原版 ReadBookActivity.onPause → Backup.autoBack: 退后台触发自动备份
                if (!BuildConfig.DEBUG) {
                    Backup.autoBack(activity)
                }
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
        // 与 onEnter 的幂等注销一致包 runCatching: receiver 已被别的路径注销时 unregister 会抛异常
        batteryReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
        batteryReceiver = null
        lifecycleObserver?.let { activity.lifecycle.removeObserver(it) }
        lifecycleObserver = null
        // 退出阅读页: 停自动翻页 (对照原版返回键 → autoPageStop) + 停页面变化订阅
        if (activeMenuState?.first === screenModel) {
            activeMenuState?.second?.stopAutoPage()
            activeMenuState?.second?.stopPageChangedWatch()
            activeMenuState = null
        }
        // 退出阅读页: 自动备份 (对照原版 ReadBookActivity.onDestroy → Backup.autoBack;
        // MainActivity.onDestroy 兜底保留)
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(activity)
        }
        // 退出阅读页: 收起文本/图片操作浮动菜单 (对照原版 onDestroy → textActionMenu.dismiss
        // + popupAction.dismiss)。动作集一并清空: 它的闭包持有 screenModel
        textSelection = null
        textActions = null
        activity.dismissImageActionMenu()
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
    navigator: AppNavigator,
    screenModel: ReaderScreenModel,
    private val controller: AndroidReaderMenuController,
    private val activity: MainActivity,
) : BaseReadMenuState(navigator, screenModel) {

    // 沉浸式菜单色彩 (对照 app 端 ReadMenu.upColorConfig)
    override var immersive by mutableStateOf(false)
    override var bgColor by mutableIntStateOf(0)
    override var textColor by mutableIntStateOf(0)
    override var hasBgImage by mutableStateOf(false)

    override var titleBarAdditionVisible by mutableStateOf(AppConfig.showReadTitleBarAddition)

    // 自动翻页控制器 (对照 app 端 ReadView.autoPager 的 AutoPager; 由 shared AutoPagerCompose 承载)
    private var autoPager: AutoPagerCompose? = null

    // 滚动模式朗读重定位: 暂停期间页面是否变化 (对照原版 ReadBookActivity.pageChanged)
    private var aloudPageChanged = false

    // 页面变化订阅句柄: 屏幕级生命周期, 由 onExit 取消、onEnter/init 重建。
    // 只挂 activity.lifecycleScope 时同一 Activity 内反复进出阅读页会累积常驻订阅者
    private var pageChangedJob: Job? = null

    init {
        startPageChangedWatch()
    }

    /**
     * 起/重起页面变化订阅 → aloudPageChanged (对照原版 ReadBook.CallBack.pageChanged;
     * 经 ReadBookEvents.seekBarChange 桥接: onPageChanged/onChapterChanged 均触发)。
     * 幂等: 先取消旧 job; 仍挂 lifecycleScope 作 Activity 销毁兜底。
     */
    fun startPageChangedWatch() {
        pageChangedJob?.cancel()
        pageChangedJob = activity.lifecycleScope.launch {
            ReadBookEvents.seekBarChange.collect { aloudPageChanged = true }
        }
    }

    /** 停页面变化订阅 (阅读页退出) */
    fun stopPageChangedWatch() {
        pageChangedJob?.cancel()
        pageChangedJob = null
    }

    fun show() {
        // 自动翻页运行时点屏幕的菜单重定向已上移 shared (ReaderScreenModel.showMenu:
        // autoPage → ReaderDialogEvent.AutoRead → ReaderRoute 渲染 AutoReadPanelDialogHost)
        showNormalMenu()
    }

    /** 常规菜单展开 (对照原版 readMenu.runMenuIn) */
    private fun showNormalMenu() {
        animate = !AppConfig.isEInkMode
        upColorConfig()
        refresh()
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

    // 沉浸式色彩配置 (对照 app 端 ReadMenu.upColorConfig, 逻辑下沉 shared createReadMenuColors;
    // 用 shareLayout 感知的 config, 与阅读页正文 ReaderDrawStyle 同源)
    private fun upColorConfig() {
        val theme = createReadMenuColors(
            config = ReadBookConfigProviders.get().config,
            fallbackBgColor = activity.bottomBackground,
        )
        immersive = theme.immersive
        bgColor = theme.bgColor
        textColor = theme.textColor
        // 窗口背景图 (原 ThemeConfig.curBgImagePath 非空) 时顶栏透明, 让窗口背景图透出
        // (T6: 判定收敛 shared hasBgImageByPath, 与 LocalThemeStoreProvider.current.bgImagePath 同一数据源)
        hasBgImage = hasBgImageByPath(ThemeConfig.curBgImagePath)
    }

    // 书源操作按钮 (对照 app 端 ReadMenu.runMenuIn sourceAction 赋值)
    private fun upSourceAction() {
        val book = screenModel.viewModel.book.value
        val source = screenModel.viewModel.bookSource.value
        sourceActionText = source?.bookSourceName ?: androidAppString("book_source")
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
        // 去重勾选态 (对照原版 onMenuOpened → menu_same_title_removed.isChecked)
        topMenu.sameTitleRemovedChecked =
            screenModel.viewModel.curTextChapter.value?.sameTitleRemoved == true
        // 云进度同步可见性 (对照原版 onMenuOpened: ReadBook.inBookshelf && AppWebDav.isOk)
        topMenu.syncProgressVisible = !book.isNotShelf && AppWebDav.isOk
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
        activity.alert(androidAppString("open_fun")) {
            setMessage(androidAppString("use_browser_open"))
            okButton { AppConfig.readUrlInBrowser = true }
            noButton { AppConfig.readUrlInBrowser = false }
        }
    }

    // 溢出菜单展开时刷新动态项 (对照 app 端 ReadMenu.onOverflowOpened)
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
                // 统一登录入口 (shared): Android 端 URL 登录仍弹 Overlay 对话框 (与原行为一致),
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

    override fun onTopMenuAction(action: ReadMenuAction) {
        when (action) {
            ReadMenuAction.CHANGE_SOURCE,
            ReadMenuAction.BOOK_CHANGE_SOURCE -> {
                hide()
                screenModel.postDialogEvent(ReaderDialogEvent.ChangeSource)
            }

            ReadMenuAction.CHAPTER_CHANGE_SOURCE -> {
                hide()
                screenModel.postDialogEvent(ReaderDialogEvent.ChangeChapterSource)
            }

            ReadMenuAction.DOWNLOAD -> showDownloadDialog()
            ReadMenuAction.SET_CHARSET -> showCharsetConfig()

            ReadMenuAction.ADD_BOOKMARK -> {
                val book = screenModel.viewModel.book.value ?: return
                val page = screenModel.viewModel.curTextPage.value
                val bookmark = Bookmark(bookName = book.name, bookAuthor = book.author).apply {
                    chapterIndex = screenModel.viewModel.durChapterIndex.value
                    chapterPos = screenModel.viewModel.durChapterPos.value
                    chapterName = page?.title ?: screenModel.currentChapter?.title ?: ""
                    bookText = page?.text?.trim() ?: ""
                }
                hide()
                screenModel.postDialogEvent(ReaderDialogEvent.AddBookmark(bookmark))
            }

            ReadMenuAction.EDIT_CONTENT -> {
                hide()
                screenModel.postDialogEvent(ReaderDialogEvent.EditContent)
            }

            ReadMenuAction.LOG -> {
                hide()
                screenModel.postDialogEvent(ReaderDialogEvent.Log)
            }

            // ===== 溢出菜单动作 (对照原版 ReadBookActivity.menuHandler.onMenuAction) =====

            // 翻页动画: 6 项选择器, 选中后刷新 (对照原版 showPageAnimConfig:
            // 选择器回调忽略索引, 实际动画值在界面设置弹窗配置, 此处只触发 upPageAnim + 重载)
            ReadMenuAction.PAGE_ANIM -> showPageAnimConfigSelector()

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
                        // 只 PATCH 阅读配置列; 整行 update 会冲掉后台 updateToc 写入的目录/元数据
                        appDb.bookDao.updateReadConfig(book.bookUrl, book.config)
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
                val removeSameTitle = !textChapter.sameTitleRemoved
                activity.lifecycleScope.launch(IO) {
                    // 原版取 curTextChapter.chapter, 这里 TextChapterShared 不带 BookChapter,
                    // 内存目录取不到就查库兜底 (放 IO, 主线程 runBlocking 会卡 UI)
                    val chapter = vm.chapterList.value.getOrNull(textChapter.chapterIndex)
                        ?: appDb.bookChapterDao.getChapter(book.bookUrl, textChapter.chapterIndex)
                        ?: return@launch
                    // toast 有条件, 翻转无条件 (对照原版 toast 在 book?.let 块内, 翻转在块外)
                    if (removeSameTitle
                        && !ContentProcessor.get(book).removeSameTitleCache
                            .contains(chapter.getFileName("nr"))
                    ) {
                        activity.toastOnUi("未找到可移除的重复标题")
                    }
                    BookHelp.setRemoveSameTitle(book, chapter, removeSameTitle)
                    vm.loadChapter(textChapter.chapterIndex)
                }
            }

            // 重新分段: 翻转 reSegment (对照原版 menu_re_segment)
            ReadMenuAction.RE_SEGMENT -> {
                val book = screenModel.viewModel.book.value ?: return
                book.config.reSegment = !book.config.reSegment
                upTopMenu()
                activity.lifecycleScope.launch(IO) {
                    // 只 PATCH 阅读配置列; 整行 update 会冲掉后台 updateToc 写入的目录/元数据
                    appDb.bookDao.updateReadConfig(book.bookUrl, book.config)
                    screenModel.viewModel.loadChapter(screenModel.viewModel.durChapterIndex.value)
                }
            }

            // 图片样式: 4 项选择器, 单选样式后重载 (对照原版 menu_image_style;
            // SINGLE 样式需要重建翻页委托)
            ReadMenuAction.IMAGE_STYLE -> {
                val imgStyles = arrayListOf(
                    Book.imgStyleDefault, Book.imgStyleFull, Book.imgStyleText, Book.imgStyleSingle
                )
                activity.selector(androidAppString("image_style"), imgStyles) { _, index ->
                    val imageStyle = imgStyles[index]
                    val book = screenModel.viewModel.book.value ?: return@selector
                    book.config.imageStyle = imageStyle
                    activity.lifecycleScope.launch(IO) {
                        // 只 PATCH 阅读配置列; 整行 update 会冲掉后台 updateToc 写入的目录/元数据
                        appDb.bookDao.updateReadConfig(book.bookUrl, book.config)
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
                uploadSuccessAction = { activity.toastOnUi(androidAppString("upload_book_success")) },
                syncSuccessAction = { activity.toastOnUi(androidAppString("sync_book_progress_success")) },
            )

            // 段评: 章节级评论对话框 (对照原版 menu_review → viewModel.openCommentDialog);
            // ReviewListDialog 已下沉 shared: 经 review_list Overlay 弹底部弹窗
            ReadMenuAction.REVIEW -> {
                val book = screenModel.viewModel.book.value ?: return
                val chapter = screenModel.currentChapter
                if (chapter != null) {
                    hide()
                    AppNavigatorProviders.getOrNull()?.showOverlay(
                        AppOverlay.Dialog(
                            key = "review_list",
                            payload = encodeReviewListDialogPayload(book, chapter, 0),
                        )
                    )
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
            // 只 PATCH 阅读配置列; 整行 update 会冲掉后台 updateToc 写入的目录/元数据
            appDb.bookDao.updateReadConfig(book.bookUrl, book.config)
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
        activity.alert(androidAppString("simulated_reading")) {
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
                            androidAppString("switch_on"),
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
                            androidAppString("start_from"),
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
                            androidAppString("start_chapter"),
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
                            androidAppString("daily_chapters"),
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
                activity.lifecycleScope.launch {
                    // 只 PATCH 阅读配置列; 整行 update 会冲掉后台 updateToc 写入的目录/元数据
                    withContext(IO) {
                        appDb.bookDao.updateReadConfig(book.bookUrl, book.config)
                    }
                    screenModel.initBook(book, book.durChapterIndex)
                }
            }
            cancelButton()
        }
    }

    private fun String.intOr(default: Int): Int = toIntOrNull() ?: default

    override fun onSeekDragStart() = Unit

    override fun onSeekStop(progress: Int) {
        // 推导逻辑下沉 shared (page 跳页 / chapter 首次弹确认后跳章 + confirmSkipToChapter 标志),
        // 平台槽只负责弹确认框, 与桌面端同源
        screenModel.onSeekStop(progress) { onConfirm ->
            activity.alert("章节跳转确认", "确定要跳转章节吗？") {
                yesButton { onConfirm() }
                noButton { }
                onCancelled { }
            }
        }
    }

    // 自动翻页: 切换状态 + 停止朗读 (对照 app 端 ReadBookActivity.autoPage)
    override fun clickAutoPage() {
        if (autoPage) {
            stopAutoPage()
        } else {
            // 开启前先停朗读 (对照原版 autoPage() 第一行 ReadAloud.stop)
            ReadAloud.stop(activity)
            startAutoPage()
        }
    }

    /**
     * 启动自动翻页: 对照原版 AutoPager 三模式——
     * - E-Ink: 定时整页翻
     * - 非 E-Ink 翻页模式: clip 揭示动画 + accent 色 1px 进度线 (ReadViewComposable 覆盖层绘制)
     * - 滚动模式: 连续滚动 (每拍小步长推进, 经 delegate.onAutoScrollBy 行级折算驱动)
     * 翻到全书末尾自动停; 手势翻页期间由 delegate 钩子暂停/恢复/复位 (对照原版 onScrollAnimStart/Stop)
     */
    private fun startAutoPage() {
        stopAutoPage()
        autoPage = true
        autoPager = AutoPagerCompose(
            viewModel = screenModel.viewModel,
            scope = activity.lifecycleScope,
            // 每拍现读速度配置 (对照原版每次 postDelayed 现取 ReadBookConfig.autoReadSpeed)
            autoReadSpeed = { ReadBookConfig.autoReadSpeed.coerceAtLeast(1) },
        ).also { pager ->
            pager.onEnd = { stopAutoPage() }
            pager.start()
        }
    }

    /** 停止自动翻页: 复位控制器 + 收起控制面板 */
    fun stopAutoPage() {
        autoPager?.stop()
        autoPager = null
        autoPage = false
    }

    override fun clickPre() {
        // 手动切章时停自动翻页
        stopAutoPage()
        super.clickPre()
    }

    override fun clickNext() {
        // 手动切章时停自动翻页
        stopAutoPage()
        super.clickNext()
    }

    // 朗读: 未运行→开始, 暂停→恢复, 运行→暂停 (对照 app 端 ReadBookActivity.onClickReadAloud)
    override fun clickReadAloud() {
        // 对照原版 onClickReadAloud 第一行 autoPageStop()
        stopAutoPage()
        when {
            !BaseReadAloudService.isRun -> {
                ReadAloud.upReadAloudClass()
                if (screenModel.viewModel.isScrollPageAnim) {
                    readAloudFromVisibleStart()
                } else {
                    ReadAloud.play(activity)
                }
            }

            BaseReadAloudService.pause -> {
                // 滚动模式且暂停期间翻过页: 重定位到新可视段起点 (对照原版 pageChanged 分支)
                if (screenModel.viewModel.isScrollPageAnim && aloudPageChanged) {
                    aloudPageChanged = false
                    readAloudFromVisibleStart()
                } else {
                    ReadAloud.resume(activity)
                }
            }

            else -> ReadAloud.pause(activity)
        }
    }

    /**
     * 滚动模式朗读起点: 从可视区首行开始朗读 (委托 shared 阅读层, 基于 scrollOffset +
     * TextPage.lines 的 isVisible 定位, 对照原版 onClickReadAloud 的 getReadAloudPos +
     * durChapterPos/openChapter + readAloud(startPos) 分支; 跨章/装载守卫在 shared 内处理)。
     */
    private fun readAloudFromVisibleStart() {
        screenModel.viewModel.readAloudFromVisibleStart()
    }

    /** 顶栏/底栏展示数据 (对照原版 upBookView: 书名/章节名/章节链接/上下章可用性) */
    override fun upMenuView() {
        super.upMenuView()
        titleBarAdditionVisible = AppConfig.showReadTitleBarAddition
    }

    // 进度条刷新 (对照原版 seekBarChange → readMenu.upSeekBar)
    override fun upSeekBar() {
        seekMax = if (AppConfig.progressBarBehavior == "page") {
            (screenModel.viewModel.curTextChapter.value?.pageSize?.minus(1) ?: -1)
                .coerceAtLeast(0)
        } else {
            (screenModel.viewModel.simulatedChapterSize - 1).coerceAtLeast(0)
        }
        seekValue = if (AppConfig.progressBarBehavior == "page") {
            screenModel.viewModel.durPageIndex.value
        } else {
            screenModel.viewModel.durChapterIndex.value
        }
    }

    // 翻页动画选择器 (原 PAGE_ANIM 分支提取, AutoReadPanel 设置按钮复用)
    fun showPageAnimConfigSelector() {
        val items = arrayListOf(
            androidAppString("btn_default_s"),
            androidAppString("page_anim_cover"),
            androidAppString("page_anim_slide"),
            androidAppString("page_anim_simulation"),
            androidAppString("page_anim_scroll"),
            androidAppString("page_anim_none"),
        )
        activity.selector(androidAppString("page_anim"), items) { _, _ ->
            ReadBookEvents.postConfig(
                ReadConfigChange.PAGE_ANIM, ReadConfigChange.LOAD_CONTENT
            )
        }
    }

    // 自动翻页控制面板 (对照原版 AutoReadDialog: 速度滑条 + 目录/主菜单/停止/设置)
    // 已上移 shared: 面板由 ReaderRoute 渲染 AutoReadPanelDialogHost,
    // 本端只需提供 autoPageStop / showPageAnimConfig / upTtsSpeechRate 平台动作 (见 Provider 覆写)

    // 对照原版 ReadAloudDialog.upTtsSpeechRate: 新语速要 pause+resume 才作用到当前段
    fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(activity)
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(activity)
            ReadAloud.resume(activity)
        }
    }

    // 离线缓存弹窗 (对照原版 BaseReadBookActivity.showDownloadDialog → CacheBook.start)
    private fun showDownloadDialog() {
        val book = screenModel.viewModel.book.value ?: return
        val startState = mutableStateOf((book.durChapterIndex + 1).toString())
        val endState = mutableStateOf(book.totalChapterNum.toString())
        activity.alert(androidAppString("offline_cache")) {
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
                            androidAppString("start"),
                            color = colors.primaryText,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        AppNumberField(
                            value = startState.value,
                            onValueChange = { startState.value = it },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            androidAppString("end"),
                            color = colors.primaryText,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        AppNumberField(
                            value = endState.value,
                            onValueChange = { endState.value = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            okButton {
                val start = startState.value.intOr(0)
                val end = endState.value.intOr(book.totalChapterNum)
                // 与原版一致: 输入为章节号, CacheBook 下标从 0 起 (start-1/end-1)
                CacheBook.start(activity, book, start - 1, end - 1)
            }
            cancelButton()
        }
    }

    // 设置编码弹窗 (对照原版 BaseReadBookActivity.showCharsetConfig → ReadBook.setCharset)
    private fun showCharsetConfig() {
        val book = screenModel.viewModel.book.value ?: return
        val charsetState = mutableStateOf(book.charset.orEmpty())
        activity.alert(androidAppString("set_charset")) {
            customView {
                AppAutoCompleteField(
                    value = charsetState.value,
                    onValueChange = { charsetState.value = it },
                    label = "charset",
                    values = AppConst.charsets,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
            okButton {
                charsetState.value.takeIf { it.isNotBlank() }?.let {
                    // 走 shared 阅读器实例 setCharset (app 端 ReadBook 单例与阅读器非同一实例)
                    screenModel.viewModel.setCharset(it)
                }
            }
            cancelButton()
        }
    }
}
