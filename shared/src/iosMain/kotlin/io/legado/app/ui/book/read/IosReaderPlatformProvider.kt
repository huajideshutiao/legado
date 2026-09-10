@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.legado.app.ui.book.read

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppWebDavShared
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
import io.legado.app.help.toast.Toasters
import io.legado.app.help.tts.IosReadAloudHost
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.page.AutoPagerCompose
import io.legado.app.ui.compose.platform.SharedThemeStoreProvider
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.reader.ReaderImageActionMenu
import io.legado.app.ui.reader.ReaderImageActions
import io.legado.app.ui.reader.ReaderTextActionMenu
import io.legado.app.ui.reader.ReaderTextActions
import io.legado.app.ui.reader.ReaderTextSelectionRequest
import io.legado.app.ui.reader.readerMenuAnchor
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.dialog.encodePhotoOverlayPayload
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.create
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

    /** 图片长按动作协程 scope (Main: UIKit 操作/toast 需主线程, 网络下载在 loadBytes 内部切 IO)。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 页内文字选择请求 (null = 不显示自绘浮动菜单), 由 [TextSelectionHost] 渲染。 */
    private var textSelection by mutableStateOf<ReaderTextSelectionRequest?>(null)

    /** 当次选择的动作集: 动作要 screenModel, 故在 onTextSelected 装配好存下。 */
    private var textActions by mutableStateOf<ReaderTextActions?>(null)

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = IosReadMenuController(navigator, screenModel)

    // 自动翻页面板停止按钮 (对照 app/desktop 端 autoPageStop → stopAutoPage: 停控制器 + 复位开关)
    override fun autoPageStop(screenModel: ReaderScreenModel) {
        (screenModel.menuController.state as? IosReadMenuState)?.stopAutoPage()
    }

    // 自动翻页滑条抬手 → 重新应用当前 TTS 语速 (对照 app 端 upTtsSpeechRate: 重读配置 +
    // pause/resume 让新语速立刻作用到当前段; 本方法不写配置, 只按现配置重放)
    override fun upTtsSpeechRate(screenModel: ReaderScreenModel) = IosReadAloudHost.upSpeechRate()

    /**
     * 图片长按: 弹共享自绘浮动菜单 (查看/刷新/保存; iOS 无 SAF"选择目录", 保存直落系统相册)。
     * 菜单项收尾对照原版 popupAction 点击 → onDismiss → postSelectionCancel。
     */
    override fun onImageLongPress(screenModel: ReaderScreenModel, src: String, x: Float, y: Float) {
        if (src.isBlank()) return
        ReaderImageActionMenu.show(
            anchor = readerMenuAnchor(x, y),
            actions = ReaderImageActions(
                view = { viewImage(screenModel, src) },
                refresh = {
                    refreshReaderImage(screenModel.currentBook, screenModel.currentChapter, src)
                },
                save = { saveImageToAlbum(screenModel, src) },
            ),
        )
    }

    /**
     * 页内文字选择完成: 弹共享自绘浮动文本操作菜单
     * (见 [ReaderTextActionMenu], 宿主 [TextSelectionHost] 挂在 MainViewController 根组合)。
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
            onReadAloud = screenModel.readAloudTextCallback(),
            onSearchContent = screenModel.searchContentTextCallback(),
        )
        textSelection = ReaderTextSelectionRequest(text, readerMenuAnchor(anchorX, anchorY))
    }

    /**
     * 页内选区已消失（点按取消选择/翻页/重排等任意路径）：收起浮动菜单
     * （对照原版 onCancelSelect → textActionMenu.dismiss）。幂等：菜单未显示时无操作。
     */
    override fun onTextSelectionDismissed(screenModel: ReaderScreenModel) = dismissActionMenus()

    /**
     * 同步立即关闭浮动菜单（点按取消选择等手势分支同帧同步直调，对照原版 ACTION_DOWN →
     * textActionMenu.dismiss() 同步语义，避免事件链异步延迟的菜单"闪一下再消失"）。
     * 幂等，事件链兜底重复调用安全。
     */
    override fun dismissTextActionMenu(screenModel: ReaderScreenModel) = dismissActionMenus()

    /** 对照 master ReadBookActivity.cancelSelect: 文本/图片菜单互斥, 同时 dismiss。 */
    private fun dismissActionMenus() {
        textSelection = null
        ReaderImageActionMenu.dismiss()
    }

    /** 阅读页销毁: 停止自动翻页并释放控制器/协程; 收起浮动菜单 + 停朗读, 避免残留 (对照原版 onDestroy → textActionMenu.dismiss)。
     *  iOS 无前台 Service/后台控制面, 离开阅读页即无朗读控制入口, 显式停止。 */
    override fun onExit(screenModel: ReaderScreenModel) {
        autoPageStop(screenModel)
        (screenModel.menuController.state as? IosReadMenuState)?.dispose()
        readerAutoPageActive = false
        dismissActionMenus()
        textActions = null
        IosReadAloudHost.stop()
    }

    /**
     * 阅读页文本操作菜单宿主 (挂在 MainViewController 根组合, 对照 desktop
     * DesktopReaderPlatformProvider.TextSelectionHost)。
     */
    @Composable
    fun TextSelectionHost() {
        val actions = textActions ?: return
        ReaderTextActionMenu(
            request = textSelection,
            actions = actions,
            // 对照原版 onMenuActionFinally: 关菜单 + 取消页内选择
            onFinally = {
                textSelection = null
                ReadBookEvents.postSelectionCancel()
            },
        )
    }

    /** 查看大图: 弹共享全屏大图 Overlay (key="photo", 口径同 desktop/ohos/android)。 */
    private fun viewImage(screenModel: ReaderScreenModel, src: String) {
        val book = screenModel.currentBook
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog(
                key = "photo",
                payload = encodePhotoOverlayPayload(
                    src, screenModel.viewModel.durChapterIndex.value
                ),
                sourceOrigin = book?.origin?.takeIf { !book.isLocal && it.isNotBlank() },
            )
        )
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
        if (bytes.isEmpty()) return null
        return runCatching {
            val nsData = bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
            UIImage.imageWithData(nsData)
        }.getOrNull()
    }

    // UIDevice 电池监控: 返回 0~100, 未启用或未知回落 100 (用户拍板 2026-08: 电量恒显示)
    override fun getBatteryLevel(): Int {
        val device = UIDevice.currentDevice
        if (!device.batteryMonitoringEnabled) {
            device.batteryMonitoringEnabled = true
        }
        val level = device.batteryLevel
        return if (level < 0f) 100 else (level * 100).toInt()
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
    navigator: AppNavigator,
    screenModel: ReaderScreenModel,
) : BaseReadMenuState(navigator, screenModel) {

    /** 自动翻页控制器协程作用域 (Main)。 */
    private val autoPageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 自动翻页控制器 (对照 Desktop/Android, shared AutoPagerCompose 承载)。 */
    private var autoPager: AutoPagerCompose? = null

    // 菜单栏配色 (对照原版 ReadMenu.upColorConfig, 逻辑见 shared createReadMenuColors)
    private val menuTheme: ReadMenuColors
        get() = createReadMenuColors(
            ReadBookConfigProviders.get().config,
            SharedThemeStoreProvider().bottomBackground.toArgb(),
        )
    override val immersive: Boolean get() = menuTheme.immersive
    override val bgColor: Int get() = menuTheme.bgColor
    override val textColor: Int get() = menuTheme.textColor

    // 窗口背景图时顶栏透明让背景图透出; 与 LegadoApp 壁纸层同一数据源
    override val hasBgImage: Boolean
        get() = hasBgImageByPath(SharedThemeStoreProvider().bgImagePath)

    override fun clickAutoPage() {
        if (autoPage) {
            stopAutoPage()
        } else {
            startAutoPage()
            showAutoPagePanel()
        }
    }

    /**
     * 启动自动翻页: 三模式语义与 app/desktop 端一致 (E-Ink 定时整页翻 / 非 E-Ink 揭示动画覆盖层 /
     * 滚动模式连续滚动), 由 shared [AutoPagerCompose] 驱动 [ReadBookViewModelShared].
     * 翻到全书末尾自动停 (pager.onEnd)。
     */
    private fun startAutoPage() {
        stopAutoPage()
        autoPage = true
        readerAutoPageActive = true
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

    /** 停止自动翻页: 复位控制器 + 复位开关 (对照 app/desktop 端 stopAutoPage)。 */
    fun stopAutoPage() {
        autoPager?.stop()
        autoPager = null
        autoPage = false
        readerAutoPageActive = false
    }

    override fun clickPre() {
        stopAutoPage()
        super.clickPre()
    }

    override fun clickNext() {
        stopAutoPage()
        super.clickNext()
    }

    // 朗读短按: 停自动翻页后切换播放/暂停
    override fun clickReadAloud() {
        stopAutoPage()
        screenModel.viewModel.toggleReadAloud()
    }

    /** 释放自动翻页协程作用域 (阅读页退出时由 Provider 调用) */
    fun dispose() {
        stopAutoPage()
        autoPageScope.cancel()
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
