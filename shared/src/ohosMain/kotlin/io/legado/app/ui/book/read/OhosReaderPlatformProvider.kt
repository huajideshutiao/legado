package io.legado.app.ui.book.read

import androidx.compose.animation.core.MutableTransitionState
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.root.AppNavigator
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable

/**
 * 鸿蒙端 [ReaderPlatformProvider]: 电池电量经 napi Battery 桥查询 @ohos.batteryInfo,
 * 菜单 UI 由 shared Compose 渲染 (同 iOS IosReaderPlatformProvider, 菜单控制器 no-op)。
 *
 * ArkTS 侧 TODO: legado_napi.cpp 实现 registerBatteryCallback + BatteryBridgeHandler.ets
 * 桥未就绪时 getBatteryLevel 返回 -1 (不显示, 同未启用电池监控的 iOS 设备)。
 */
object OhosReaderPlatformProvider : ReaderPlatformProvider {

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = OhosReadMenuController

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

// no-op 菜单控制器: 阅读 Compose 菜单 UI 由 shared ReadMenuOverlay 渲染 (同 iOS)
private object OhosReadMenuController : ReadMenuController {
    override val state: ReadMenuState = OhosReadMenuState
    override fun showMenu() = Unit
    override fun hideMenu() = Unit
}

// no-op 菜单状态: 只读属性取默认值, 动作回调空操作 (与 IosReadMenuState 一致)
private object OhosReadMenuState : ReadMenuState {
    override val visibleState = MutableTransitionState(false)
    override val animate: Boolean = false
    override val isVisible: Boolean = false
    override val canShowMenu: Boolean = false
    override val immersive: Boolean = false
    override val bgColor: Int = 0
    override val textColor: Int = 0
    override val hasBgImage: Boolean = false
    override val title: String? = null
    override val chapterName: String? = null
    override val chapterUrl: String? = null
    override val chapterNameVisible: Boolean = false
    override val chapterUrlVisible: Boolean = false
    override val sourceActionText: String = ""
    override val sourceActionVisible: Boolean = false
    override val titleBarAdditionVisible: Boolean = false
    override val topMenu: TopMenuState = TopMenuState()
    override val seekMax: Int = 0
    override val seekValue: Int = 0
    override val prevEnabled: Boolean = false
    override val nextEnabled: Boolean = false
    override val autoPage: Boolean = false
    override val isNightTheme: Boolean = false

    override fun onTransitionIdle(shown: Boolean) = Unit
    override fun onBgClick() = Unit
    override fun onChapterViewClick() = Unit
    override fun onChapterViewLongClick() = Unit
    override fun onOverflowOpened() = Unit
    override fun sourceLoginVisible(): Boolean = false
    override fun sourcePayVisible(): Boolean = false
    override fun onSourceAction(action: SourceAction) = Unit
    override fun openBookInfoActivity() = Unit
    override fun supportFinishAfterTransition() = Unit
    override fun onTopMenuAction(action: ReadMenuAction) = Unit
    override fun onSeekDragStart() = Unit
    override fun onSeekStop(progress: Int) = Unit
    override fun clickSearch() = Unit
    override fun clickAutoPage() = Unit
    override fun clickReplaceRule() = Unit
    override fun clickNightTheme() = Unit
    override fun clickPre() = Unit
    override fun clickNext() = Unit
    override fun clickCatalog() = Unit
    override fun clickReadAloud() = Unit
    override fun longClickReadAloud() = Unit
    override fun clickFont() = Unit
    override fun clickSetting() = Unit
    override fun onRefresh() = Unit
}
