@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui.book.read

import androidx.compose.animation.core.MutableTransitionState
import io.legado.app.ui.root.AppNavigator
import platform.UIKit.UIDevice

// iOS 阅读页平台能力: 电池状态用 UIDevice, 菜单 UI 由 shared Compose 渲染
object IosReaderPlatformProvider : ReaderPlatformProvider {

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = IosReadMenuController

    // UIDevice 电池监控: 返回 0~100, 未启用或未知返回 -1
    override fun getBatteryLevel(): Int {
        val device = UIDevice.currentDevice
        if (!device.batteryMonitoringEnabled) {
            device.batteryMonitoringEnabled = true
        }
        val level = device.batteryLevel
        return if (level < 0f) -1 else (level * 100).toInt()
    }
}

private object IosReadMenuController : ReadMenuController {
    override val state: ReadMenuState = IosReadMenuState
    override fun showMenu() = Unit
    override fun hideMenu() = Unit
}

// 菜单状态: 阅读 Compose 菜单 UI 由 shared ReadMenuOverlay 渲染, 此处只持有状态
private object IosReadMenuState : ReadMenuState {
    override val visibleState = MutableTransitionState(false)
    override val animate: Boolean = false
    override val isVisible: Boolean get() = false
    override val canShowMenu: Boolean get() = false
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
