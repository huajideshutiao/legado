package io.legado.app.ui.book.read

import androidx.compose.animation.core.MutableTransitionState
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
import io.legado.app.help.image.ReaderImageCache
import io.legado.app.help.toast.Toasters
import io.legado.app.help.tts.OhosReadAloudHost
import io.legado.app.help.tts.TtsEngineProvider
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.ReaderDialogEvent
import io.legado.app.ui.compose.platform.SharedThemeStoreProvider
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable

/**
 * 鸿蒙端 [ReaderPlatformProvider]: 电池电量经 napi Battery 桥查询 @ohos.batteryInfo,
 * 菜单状态/导航回调对齐 iOS [IosReadMenuState] (visibleState 可切, click 经 navigator/screenModel)。
 *
 * ArkTS 侧 TODO: legado_napi.cpp 实现 registerBatteryCallback + BatteryBridgeHandler.ets
 * 桥未就绪时 getBatteryLevel 回落 100 (电量恒显示, 用户拍板 2026-08, 与 desktop 一致)。
 */
object OhosReaderPlatformProvider : ReaderPlatformProvider {

    /** 查词请求 (选中词 → 暂存, 由 MainOhos 宿主渲染 DictDialogHost; 对照原版 menu_dict → DictDialog)。 */
    internal var dictWord by mutableStateOf<String?>(null)

    /** 当前浮动菜单的选中文本 (ArkTS 菜单项点击时经回调取参)。 */
    private var textActionText: String = ""

    init {
        // 菜单动作回调注册 (ArkTS 菜单项点击 → legado_text_action_callback → 本分发)
        OhosNativeBridge.textActionHandler = { action, text, src ->
            onTextAction(action, text, src)
        }
    }

    /** 空白长按回落: 原版 ContentTextView.longPress 未命中任何列时无动作，此处 no-op。 */
    override fun onLongPress(screenModel: ReaderScreenModel) = Unit

    /**
     * 页内文字选择完成：经 napi 桥弹 ArkTS 浮动菜单并跟随选区（平台原生 ArkUI 组件，
     * 对标 Android 原版 TextActionMenu；动作见 [onTextAction]）。
     */
    override fun onTextSelected(
        screenModel: ReaderScreenModel,
        text: String,
        anchorX: Float,
        anchorY: Float,
    ) {
        if (text.isBlank()) return
        textActionText = text
        OhosNativeBridge.showTextActionMenu(text, anchorX, anchorY)
    }

    /**
     * 页内选区已消失（点按取消选择/翻页/重排等任意路径）：收起 ArkTS 浮动菜单
     * （对照原版 onCancelSelect → textActionMenu.dismiss）。幂等：菜单未显示时无操作。
     */
    override fun onTextSelectionDismissed(screenModel: ReaderScreenModel) {
        OhosNativeBridge.hideTextActionMenu()
    }

    /**
     * 同步立即关闭浮动菜单（点按取消选择等手势分支同帧同步直调，对照原版 ACTION_DOWN →
     * textActionMenu.dismiss() 同步语义，避免事件链异步延迟的菜单"闪一下再消失"）。
     * hide 幂等（ArkTS 侧空 payload 隐藏已隐藏的菜单无操作），事件链兜底重复调用安全。
     */
    override fun dismissTextActionMenu(screenModel: ReaderScreenModel) {
        OhosNativeBridge.hideTextActionMenu()
    }

    /** 阅读页退出: 收起浮动菜单避免残留 (对照原版 onDestroy → textActionMenu.dismiss)。 */
    override fun onExit(screenModel: ReaderScreenModel) {
        OhosNativeBridge.hideTextActionMenu()
    }

    /**
     * 图片长按 (命中图片列): 经 napi 桥弹 ArkTS 图片浮动菜单 (查看/刷新/保存到相册)。
     * 查看/保存由 ArkTS 侧本地处理 (全屏预览 / photoAccessHelper 存相册),
     * 刷新回送本分发执行 [onTextAction] 的 "refresh" 分支 (清图片缓存 + 重排)。
     * 对照原版 ReadBookActivity.onImageLongPress (无"选择目录", 平台适配为保存到相册)。
     */
    override fun onImageLongPress(
        screenModel: ReaderScreenModel,
        src: String,
        x: Float,
        y: Float,
    ) {
        if (src.isBlank()) return
        OhosNativeBridge.showImageActionMenu(src, x, y)
    }

    /**
     * 文本菜单动作分发 (对标原版 ReadBookActivity.onMenuItemSelected/onMenuItemClick):
     * 替换/书签/全文搜索走 shared 能力; 复制走剪贴板; 查词暂存 dictWord;
     * 浏览器 URL 直开否则系统搜索; 朗读走系统 TTS 引擎 (TtsEngineProvider);
     * 图片菜单 refresh 清图片缓存并重排;
     * `__dismiss` = 菜单收起 → 取消页内选择 (对标原版 onMenuActionFinally)。
     */
    private fun onTextAction(action: String, text: String, src: String) {
        val text = text.ifBlank { textActionText }
        val screenModel = ReaderScreenModelRegistry.currentScreenModel
        when (action) {
            "replace" -> screenModel?.replaceTextCallback()?.invoke(text)
            "copy" -> PlatformCapabilityProviders.get().copyToClipboard(text)
            "bookmark" -> screenModel?.bookmarkTextCallback()?.invoke(text)
            "aloud" -> {
                // 朗读选中文本: 用已注册的系统 TTS 引擎 (经 TtsBridgeHandler → @ohos.textToSpeech,
                // 对照原版 menu_aloud → ReadAloudControllerShared)
                val engine = TtsEngineProvider.get()
                if (engine != null) {
                    engine.speak(text, "textActionAloud")
                } else {
                    Toasters.get().toast("朗读暂未支持")
                }
            }
            "dict" -> dictWord = text
            "search_content" -> screenModel?.searchContentTextCallback()?.invoke(text)
            "browser" -> openTextInBrowser(text)
            "share" -> PlatformCapabilityProviders.get().shareText(text)

            // 图片菜单"刷新": 清 shared ReaderImageCache (鸿蒙阅读页图片走 shared
            // ReaderImageResolver) + 发 LOAD_CONTENT 事件重排 (对照 app 端 refreshImage)
            "refresh" -> {
                ReaderImageCache.clear()
                ReadBookEvents.postConfig(ReadConfigChange.LOAD_CONTENT)
            }

            "__dismiss" -> ReadBookEvents.postSelectionCancel()
        }
    }

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = OhosReadMenuController(navigator, screenModel)

    // 自动翻页面板停止按钮: 本端 autoPage 仅开关状态 (无 AutoPager), 复位开关即可
    override fun autoPageStop(screenModel: ReaderScreenModel) {
        (screenModel.menuController.state as? OhosReadMenuState)?.autoPage = false
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
        OhosReadAloudHost.setSpeechRate(rate)
    }

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

    override fun clickReadAloud() {
        if (autoPage) autoPage = false
        OhosReadAloudHost.toggle()
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
