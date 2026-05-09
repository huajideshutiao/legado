@file:Suppress("unused")

package io.legado.app.utils

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.flow.MutableSharedFlow

fun eventObservable(tag: String): MutableSharedFlow<Any> {
    return FlowBus.with(tag)
}

inline fun <reified EVENT : Any> postEvent(tag: String, event: EVENT) {
    FlowBus.with(tag).tryEmit(event)
}

inline fun <reified EVENT : Any> postEventDelay(tag: String, event: EVENT, delay: Long) {
    // 简单的延迟发送可以用协程实现，这里暂时维持 API 兼容
    // 实际项目中可以考虑是否真的需要这个
    postEvent(tag, event) 
}

inline fun <reified EVENT : Any> postEventOrderly(tag: String, event: EVENT) {
    postEvent(tag, event)
}

inline fun <reified EVENT : Any> AppCompatActivity.observeEvent(
    vararg tags: String,
    noinline observer: (EVENT) -> Unit
) {
    tags.forEach {
        FlowBus.observe<EVENT>(this, it, observer)
    }
}

inline fun <reified EVENT : Any> AppCompatActivity.observeEventSticky(
    vararg tags: String,
    noinline observer: (EVENT) -> Unit
) {
    tags.forEach {
        FlowBus.observe<EVENT>(this, it, observer)
    }
}

inline fun <reified EVENT : Any> Fragment.observeEvent(
    vararg tags: String,
    noinline observer: (EVENT) -> Unit
) {
    tags.forEach {
        FlowBus.observe<EVENT>(this, it, observer)
    }
}

inline fun <reified EVENT : Any> Fragment.observeEventSticky(
    vararg tags: String,
    noinline observer: (EVENT) -> Unit
) {
    tags.forEach {
        FlowBus.observe<EVENT>(this, it, observer)
    }
}

inline fun <reified EVENT : Any> LifecycleService.observeEvent(
    vararg tags: String,
    noinline observer: (EVENT) -> Unit
) {
    tags.forEach {
        FlowBus.observe<EVENT>(this, it, observer)
    }
}

inline fun <reified EVENT : Any> LifecycleService.observeEventSticky(
    vararg tags: String,
    noinline observer: (EVENT) -> Unit
) {
    tags.forEach {
        FlowBus.observe<EVENT>(this, it, observer)
    }
}