package io.legado.app.ui.compose.platform

import io.legado.app.constant.EventBus
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 跨平台事件总线 provider。
 * Android actual 包装 app.utils.FlowBus.with(EventBus.RECREATE); 桌面/iOS/鸿蒙 actual
 * 用 SharedFlow 实现。
 *
 * 仅暴露主题刷新需要的 recreateEvent 事件流（Flow<Unit>），屏蔽 FlowBus 的
 * MutableSharedFlow<Any> 泛型细节与 key 字符串，便于桌面/iOS/鸿蒙独立实现。
 */
interface EventBusProvider {
    /** 主题变更/重建事件流, 对应 EventBus.RECREATE */
    val recreateEvent: Flow<Unit>

    /**
     * 触发 recreate 事件 (供 ThemeCustomizeDialog/ThemeListDialog 下沉后跨平台调用)。
     *
     * - Android actual: 包装 postEvent(EventBus.RECREATE, "")
     * - 桌面/iOS/鸿蒙 actual: emit 本地 SharedFlow 触发 AppTheme 重组
     */
    fun emitRecreate()
}

/**
 * 委托 commonMain [FlowBus]`[EventBus.RECREATE]` 的实现，桌面 / iOS / 鸿蒙共用
 * （三端曾各写一份, 函数体逐字相同）。
 *
 * 多处 new 的实例共享同一全局 bus, 故 [FileThemeConfigProvider][io.legado.app.help.config.FileThemeConfigProvider]
 * 等非 UI 层发出的 recreate 也能到达 AppTheme。Android 端另有实现 (包装 postEvent)。
 */
class SharedEventBusProvider : EventBusProvider {
    override val recreateEvent: Flow<Unit> = FlowBus.with(EventBus.RECREATE).map { }

    override fun emitRecreate() {
        FlowBus.with(EventBus.RECREATE).tryEmit("")
    }
}
