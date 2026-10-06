package io.legado.app.ui.book.read

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.legado.app.App
import io.legado.app.BuildConfig
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.storage.Backup
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.AutoPagerCompose
import io.legado.app.ui.compose.component.AppAutoCompleteField
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.reader.ReaderTextActionMenu
import io.legado.app.ui.reader.ReaderTextActions
import io.legado.app.ui.reader.ReaderTextSelectionRequest
import io.legado.app.ui.reader.readerMenuAnchor
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.utils.openUrl
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
            onBookmark = screenModel.bookmarkTextCallback(),
            onUnderline = screenModel.underlineTextCallback(),
            onReadAloud = screenModel.readAloudTextCallback(),
            onSearchContent = screenModel.searchContentTextCallback(),
        )
        textSelection = ReaderTextSelectionRequest(
            text = text,
            anchor = readerMenuAnchor(anchorX, anchorY),
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

    /**
     * 页内选区已消失（点按取消选择/翻页/重排等任意路径）：收起浮动文本操作菜单
     * （对照原版 onCancelSelect → textActionMenu.dismiss）。幂等：菜单未显示时无操作。
     */
    override fun onTextSelectionDismissed(screenModel: ReaderScreenModel) = dismissActionMenus()

    /**
     * 同步立即关闭浮动文本操作菜单（点按取消选择等手势分支在选区清除的同帧同步直调）。
     * 幂等，事件链兜底重复调用安全。
     */
    override fun dismissTextActionMenu(screenModel: ReaderScreenModel) = dismissActionMenus()

    /** 对照 master ReadBookActivity.cancelSelect: 文本/图片菜单互斥, 同时 dismiss。 */
    private fun dismissActionMenus() {
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
        if (src.isBlank()) return
        activity.showImageActionMenu(src, x, y)
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
        // 注册生命周期观察者: 退后台停自动翻页 + 自动备份 (对照原版 onPause 的
        // autoPageStop / Backup.autoBack)。计时/落库/取消预下载改由 shared
        // 本页 Lifecycle 统一驱动 (OnRouteLifecycle), 此处不再转发
        val observer = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                activeMenuState?.second?.stopAutoPage()
                if (!BuildConfig.DEBUG) {
                    Backup.autoBack(activity)
                }
            }
        }
        activity.lifecycle.addObserver(observer)
        lifecycleObserver = observer
    }

    override fun onExit(screenModel: ReaderScreenModel) {
        // 退出阅读页: 先停自动翻页 (对照原版返回键 → autoPageStop) + 停页面变化订阅,
        // 再调 exitReaderWindow 清除常亮与系统栏状态, 避免 stopAutoPage 的常亮回落重加 FLAG_KEEP_SCREEN_ON
        if (activeMenuState?.first === screenModel) {
            activeMenuState?.second?.stopAutoPage()
            activeMenuState?.second?.stopPageChangedWatch()
            activeMenuState = null
        }
        readerAutoPageActive = false
        activity.exitReaderWindow()
        // 与 onEnter 的幂等注销一致包 runCatching: receiver 已被别的路径注销时 unregister 会抛异常
        batteryReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
        batteryReceiver = null
        lifecycleObserver?.let { activity.lifecycle.removeObserver(it) }
        lifecycleObserver = null
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

    // 沉浸式菜单色彩: 实时计算, 与桌面/iOS/鸿蒙同一机制 (DesktopReaderPlatformProvider.kt:503-510
    // / IosReaderPlatformProvider.kt:257-264 / OhosReaderPlatformProvider.kt:255-262)。
    // 原先是 show() 时写一次的快照, Android 是四端唯一异类: 从未展开过阅读菜单就走点击行为 11
    // /文字浮动菜单进搜索态时 immersive 仍为初始 false、改阅读背景后未再展开菜单则取到旧色。
    private val menuTheme: ReadMenuColors
        get() = createReadMenuColors(
            ReadBookConfigProviders.get().config,
            activity.bottomBackground,
        )
    override val immersive: Boolean get() = menuTheme.immersive
    override val bgColor: Int get() = menuTheme.bgColor
    override val textColor: Int get() = menuTheme.textColor
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

    override fun show() {
        // 自动翻页运行时点屏幕的菜单重定向已上移 shared (ReaderScreenModel.showMenu:
        // autoPage → ReaderDialogEvent.AutoRead → ReaderRoute 渲染 AutoReadPanelDialogHost)
        showNormalMenu()
    }

    /** 常规菜单展开 (对照原版 readMenu.runMenuIn) */
    private fun showNormalMenu() {
        animate = !AppConfig.isEInkMode
        upColorConfig()
        refresh()
        visibleState.targetState = true
        // 菜单显示时状态栏/导航栏恢复显示 (对照原版 runMenuIn → upSystemUiVisibility)
        activity.upReaderSystemBars(menuVisible = true)
    }

    override fun hide() {
        visibleState.targetState = false
        // 菜单收起后按 hideStatusBar/hideNavigationBar 配置恢复
        activity.upReaderSystemBars(menuVisible = false)
    }

    // 窗口背景图判定 (色彩已改 [menuTheme] 实时 getter, 不再需要 show 时快照;
    // 方法名保留 upColorConfig 以对应原版 ReadMenu.upColorConfig 的调用位置)
    private fun upColorConfig() {
        // 窗口背景图 (原 ThemeConfig.curBgImagePath 非空) 时顶栏透明, 让窗口背景图透出
        // (T6: 判定收敛 shared hasBgImageByPath, 与 LocalThemeStoreProvider.current.bgImagePath 同一数据源)
        hasBgImage = hasBgImageByPath(ThemeConfig.curBgImagePath)
    }

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

    // onTopMenuAction/upSourceAction/upTopMenu/onSourceAction/onSeekStop 等与父类同实现或
    // 已由 shared 对话框承接, 全部统一走父类 (含 KEYWORD_HIGHLIGHT: 收菜单后进关键词高亮管理页)

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
        readerAutoPageActive = true
        autoPager = AutoPagerCompose(
            viewModel = screenModel.viewModel,
            scope = activity.lifecycleScope,
            // 每拍现读速度配置 (对照原版每次 postDelayed 现取 ReadBookConfig.autoReadSpeed)
            autoReadSpeed = { ReadBookConfig.autoReadSpeed.coerceAtLeast(1) },
        ).also { pager ->
            pager.onEnd = { stopAutoPage() }
            pager.start()
        }
        // 自动翻页期间强制常亮 (对照原版 autoPage(): screenTimeOut = -1L + screenOffTimerStart)
        activity.setReaderAutoPageKeepScreenOn(true)
        // 收菜单 + 弹控制面板 (基类实现; 顶层 autoPage 已置 true, 不会被路由层 clear 吃掉)
        showAutoPagePanel()
    }

    /** 停止自动翻页: 复位控制器 + 收起控制面板 */
    fun stopAutoPage() {
        val wasRunning = autoPager != null
        autoPager?.stop()
        autoPager = null
        autoPage = false
        readerAutoPageActive = false
        // 恢复 keepLight 配置的常亮计时 (对照原版 autoPageStop(): upScreenTimeOut)
        if (wasRunning) activity.setReaderAutoPageKeepScreenOn(false)
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

    // 翻页动画选择器已删除: 原版该选择器回调忽略索引 (selector { _, _ -> success() }),
    // 选完不写任何动画值, 仅发 PAGE_ANIM + LOAD_CONTENT —— 属于无效的死 UI。
    // 翻页动画统一在界面设置弹窗 (ReadStyleScreen 的 5 个单选项) 配置。

    // 自动翻页控制面板 (对照原版 AutoReadDialog: 速度滑条 + 目录/主菜单/停止/设置)
    // 已上移 shared: 面板由 ReaderRoute 渲染 AutoReadPanelDialogHost,
    // 本端只需提供 autoPageStop / upTtsSpeechRate 平台动作 (见 Provider 覆写)

    // 对照原版 ReadAloudDialog.upTtsSpeechRate: 新语速要 pause+resume 才作用到当前段
    fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(activity)
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(activity)
            ReadAloud.resume(activity)
        }
    }


}

