package io.legado.app.ui.book.read

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.showSourceLogin
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.file.saveImageToAlbum
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.toast.Toasters
import io.legado.app.help.tts.OhosReadAloudHost
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.page.AutoPagerCompose
import io.legado.app.ui.book.read.ReaderDialogEvent
import io.legado.app.ui.compose.platform.SharedThemeStoreProvider
import io.legado.app.ui.reader.ReaderImageActionMenu
import io.legado.app.ui.reader.ReaderImageActions
import io.legado.app.ui.reader.ReaderTextActionMenu
import io.legado.app.ui.reader.ReaderTextActions
import io.legado.app.ui.reader.ReaderTextSelectionRequest
import io.legado.app.ui.reader.readerMenuAnchor
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.imageExtension
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.dialog.encodePhotoOverlayPayload
import io.legado.app.utils.KS_JSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 鸿蒙端 [ReaderPlatformProvider]: 电池电量经 napi Battery 桥查询 @ohos.batteryInfo,
 * 菜单状态/导航回调对齐 iOS [IosReadMenuState] (visibleState 可切, click 经 navigator/screenModel)。
 *
 * ArkTS 侧 TODO: legado_napi.cpp 实现 registerBatteryCallback + BatteryBridgeHandler.ets
 * 桥未就绪时 getBatteryLevel 回落 100 (电量恒显示, 用户拍板 2026-08, 与 desktop 一致)。
 */
object OhosReaderPlatformProvider : ReaderPlatformProvider {

    /** 图片长按动作协程 scope: 下载与相册写入都阻塞等 ArkTS 桥回调, 必须离开主线程。 */
    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    /** 页内文字选择请求 (null = 不显示自绘浮动菜单), 由 [TextSelectionHost] 渲染。 */
    private var textSelection by mutableStateOf<ReaderTextSelectionRequest?>(null)

    /** 当次选择的动作集: 动作要 screenModel, 故在 onTextSelected 装配好存下。 */
    private var textActions by mutableStateOf<ReaderTextActions?>(null)

    /**
     * 页内文字选择完成: 弹共享自绘浮动文本操作菜单
     * (见 [ReaderTextActionMenu], 宿主 [TextSelectionHost] 挂在 MainOhos 根组合)。
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

    /** 阅读页退出: 停止朗读宿主; 停止自动翻页并释放控制器/协程; 收起浮动菜单避免残留 (对照原版 onDestroy → textActionMenu.dismiss)。 */
    override fun onExit(screenModel: ReaderScreenModel) {
        OhosReadAloudHost.stop()
        autoPageStop(screenModel)
        (screenModel.menuController.state as? OhosReadMenuState)?.dispose()
        readerAutoPageActive = false
        dismissActionMenus()
        textActions = null
    }

    /**
     * 阅读页文本操作菜单宿主 (挂在 MainOhos 根组合, 对照 desktop
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

    /**
     * 图片长按: 弹共享自绘浮动菜单 (查看/刷新/保存; 鸿蒙无 SAF"选择目录", 保存直落系统相册)。
     * 菜单项收尾对照原版 popupAction 点击 → onDismiss → postSelectionCancel。
     */
    override fun onImageLongPress(
        screenModel: ReaderScreenModel,
        src: String,
        x: Float,
        y: Float,
    ) {
        if (src.isBlank()) return
        ReaderImageActionMenu.show(
            anchor = readerMenuAnchor(x, y),
            actions = ReaderImageActions(
                view = { viewImage(screenModel, src) },
                refresh = {
                    refreshReaderImage(screenModel.currentBook, screenModel.currentChapter, src)
                },
                save = { saveImage(screenModel, src) },
            ),
        )
    }

    /** 查看大图: 共享全屏大图 Overlay (key="photo", 口径同 desktop); payload 带章节索引让
     *  对话框优先读阅读时已落盘的章节图片缓存, sourceOrigin 供防盗链 header/封面解密。 */
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

    /** 保存到相册: 下载解码 (带书源防盗链 header) → photoAccessHelper 写入 (仅 ArkTS 可调, 经文件桥)。 */
    private fun saveImage(screenModel: ReaderScreenModel, src: String) {
        val book = screenModel.currentBook
        val bookSource = screenModel.viewModel.bookSource.value
        Toasters.get().toast("正在保存")
        scope.launch {
            val bytes = ImageBitmapLoader().loadBytes(src, book, bookSource)
            if (bytes == null) {
                Toasters.get().toast("图片保存失败")
                return@launch
            }
            val saved = saveImageToAlbum(imageExtension(bytes, src).removePrefix("."), bytes)
            Toasters.get().toast(if (saved) "已保存到相册" else "图片保存失败")
        }
    }

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = OhosReadMenuController(navigator, screenModel)

    // 自动翻页面板停止按钮 (对照 app/desktop 端 autoPageStop → stopAutoPage: 停控制器 + 复位开关)
    override fun autoPageStop(screenModel: ReaderScreenModel) {
        (screenModel.menuController.state as? OhosReadMenuState)?.stopAutoPage()
    }

    // 自动翻页滑条抬手 → 重新应用当前 TTS 语速 (对照 app 端 upTtsSpeechRate: 重读配置 +
    // pause/resume 让新语速立刻作用到当前段; 本方法不写配置, 只按现配置重放)
    override fun upTtsSpeechRate(screenModel: ReaderScreenModel) = OhosReadAloudHost.upSpeechRate()

    // 经 napi Battery 桥查询 @ohos.batteryInfo.batterySOC; 桥未就绪/超时回落 100 (用户拍板 2026-08: 电量恒显示)
    override fun getBatteryLevel(): Int {
        if (!OhosNativeBridge.isBatteryBridgeReady()) return 100
        val result = OhosNativeBridge.invokeBatterySync("getLevel") ?: return 100
        val resp = runCatching {
            KS_JSON.decodeFromString(BatteryResponse.serializer(), result)
        }.getOrNull()
        return resp?.level ?: 100
    }

    /** 朗读控制桥: 长按面板动作落到 [OhosReadAloudHost] + 偏好项 (对照 iOS [IosReadAloudControls])。 */
    override fun readAloudControls(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadAloudControls = OhosReadAloudControls(navigator, screenModel)
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

    override fun clickReadAloud() {
        stopAutoPage()
        OhosReadAloudHost.toggle()
    }

    /** 释放自动翻页协程作用域 (阅读页退出时由 Provider 调用) */
    fun dispose() {
        stopAutoPage()
        autoPageScope.cancel()
    }
}

/**
 * 鸿蒙端朗读控制桥: 面板动作落到 [OhosReadAloudHost] + 偏好项。
 *
 * 语速/跟随系统/定时默认值直接读写 PreferKey (与原版 AppConfig 同 key),
 * 对照 desktop `DesktopReadAloudControls` / iOS `IosReadAloudControls`。
 */
private class OhosReadAloudControls(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
) : ReadAloudControls {

    private val prefs get() = PreferenceProviders.get()

    override val isPlaying: Boolean get() = !OhosReadAloudHost.isPause

    override val timerMinute: Int
        get() = OhosReadAloudHost.timeMinute
            .takeIf { it > 0 }
            ?: prefs.getInt(PreferKey.ttsTimer, 0)

    override val speechRate: Int get() = prefs.getInt(PreferKey.ttsSpeechRate, 5)

    override val followSys: Boolean get() = prefs.getBoolean(PreferKey.ttsFollowSys, true)

    override fun playPause() = OhosReadAloudHost.toggle()

    override fun stop() = OhosReadAloudHost.stop()

    override fun prevChapter() {
        screenModel.viewModel.moveToPrevChapter()
    }

    override fun nextChapter() {
        screenModel.viewModel.moveToNextChapter()
    }

    override fun prevParagraph() = OhosReadAloudHost.prevParagraph()

    override fun nextParagraph() = OhosReadAloudHost.nextParagraph()

    override fun setTimer(minute: Int) {
        OhosReadAloudHost.setTimer(minute)
    }

    override fun setSpeechRate(rate: Int) {
        prefs.putInt(PreferKey.ttsSpeechRate, rate.coerceIn(0, 45))
        OhosReadAloudHost.setSpeechRate(rate)
    }

    override fun setFollowSys(follow: Boolean) {
        prefs.putBoolean(PreferKey.ttsFollowSys, follow)
        // 跟随系统时回落默认语速 (对照原版 AppConfig.speechRatePlay)
        OhosReadAloudHost.setSpeechRate(if (follow) 5 else speechRate)
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
