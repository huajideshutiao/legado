@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui.book.read

import androidx.compose.animation.core.MutableTransitionState
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
import io.legado.app.help.image.ReaderImageCache
import io.legado.app.help.toast.Toasters
import io.legado.app.help.tts.IosReadAloudHost
import io.legado.app.help.tts.TtsEngineProvider
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.compose.platform.IosThemeStoreProvider
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef
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

    // 设置按钮 → 翻页动画配置 (对照 app 端 showPageAnimConfigSelector: 选择器回调忽略索引,
    // 实际动画值在界面设置弹窗配置, 只触发 upPageAnim + 重载; 与菜单 PAGE_ANIM 分支同语义)
    override fun showPageAnimConfig(screenModel: ReaderScreenModel) {
        AppNavigatorProviders.getOrNull()?.showOverlay(AppOverlay.Dialog("page_anim_config"))
    }

    // 自动翻页滑条抬手 → 重新应用当前 TTS 语速 (对照 app 端 upTtsSpeechRate: 重读配置 +
    // pause/resume 让新语速立刻作用到当前段; 本方法不写配置, 只按现配置重放)
    override fun upTtsSpeechRate(screenModel: ReaderScreenModel) {
        val prefs = runCatching { PreferenceProviders.get() }.getOrNull() ?: return
        val rate = if (prefs.getBoolean(PreferKey.ttsFollowSys, true)) {
            5
        } else {
            prefs.getInt(PreferKey.ttsSpeechRate, 5)
        }
        IosReadAloudHost.setSpeechRate(rate)
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

    /**
     * 同步立即关闭浮动菜单（点按取消选择等手势分支同帧同步直调，对照原版 ACTION_DOWN →
     * textActionMenu.dismiss() 同步语义，避免事件链异步延迟的菜单"闪一下再消失"）。
     * dismiss 幂等（未显示时无操作），事件链兜底重复调用安全。
     */
    override fun dismissTextActionMenu(screenModel: ReaderScreenModel) {
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
            // T1: 替换/书签/全文搜索复用共享回调 (见 ReaderScreenModel.replaceTextCallback 等)
            "replace" -> screenModel.replaceTextCallback().invoke(text)

            "copy" -> PlatformCapabilityProviders.get().copyToClipboard(text)
            "bookmark" -> screenModel.bookmarkTextCallback().invoke(text)
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
            "search_content" -> screenModel.searchContentTextCallback().invoke(text)

            "browser" -> openTextInBrowser(text)
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

    // 菜单栏配色 (对照原版 ReadMenu.upColorConfig, 逻辑见 shared createReadMenuColors)
    private val menuTheme: ReadMenuColors
        get() = createReadMenuColors(
            ReadBookConfigProviders.get().config,
            IosThemeStoreProvider().bottomBackground.toArgb(),
        )
    override val immersive: Boolean get() = menuTheme.immersive
    override val bgColor: Int get() = menuTheme.bgColor
    override val textColor: Int get() = menuTheme.textColor

    // 窗口背景图时顶栏透明让背景图透出; 与 LegadoApp 壁纸层同一数据源
    override val hasBgImage: Boolean
        get() = hasBgImageByPath(IosThemeStoreProvider().bgImagePath)

    // 朗读短按: 停自动翻页后切换播放/暂停
    override fun clickReadAloud() {
        if (autoPage) autoPage = false
        screenModel.viewModel.toggleReadAloud()
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
