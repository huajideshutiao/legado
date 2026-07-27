package io.legado.app.ui.compose.platform

import kotlinx.coroutines.flow.Flow

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
}
