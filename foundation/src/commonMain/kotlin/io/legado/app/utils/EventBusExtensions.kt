package io.legado.app.utils

import kotlinx.coroutines.flow.MutableSharedFlow

fun eventObservable(tag: String): MutableSharedFlow<Any> {
    return FlowBus.with(tag)
}

inline fun <reified EVENT : Any> postEvent(tag: String, event: EVENT) {
    FlowBus.with(tag).tryEmit(event)
    FlowBus.withSticky(tag).tryEmit(event)
}

inline fun <reified EVENT : Any> postEventDelay(tag: String, event: EVENT, delay: Long) {
    // 简单的延迟发送可以用协程实现，这里暂时维持 API 兼容
    // 实际项目中可以考虑是否真的需要这个
    postEvent(tag, event)
}

inline fun <reified EVENT : Any> postEventOrderly(tag: String, event: EVENT) {
    postEvent(tag, event)
}
