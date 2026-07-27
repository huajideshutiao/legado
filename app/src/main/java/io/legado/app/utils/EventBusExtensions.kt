@file:JvmName("EventBusObserveExtensions")

package io.legado.app.utils

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleService

/**
 * EventBus 观察面扩展 (Android 平台专属)。
 *
 * post 侧 (postEvent/postEventDelay/postEventOrderly) 已下沉到
 * modules/shared/src/commonMain/kotlin/io/legado/app/utils/EventBusExtensions.kt。
 *
 * 注意: 本文件使用 @file:JvmName("EventBusObserveExtensions") 改变生成的 class 文件名,
 * 避免与 shared 模块的 EventBusExtensionsKt.class 同名冲突导致符号遮蔽。
 */

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
        FlowBus.observeSticky<EVENT>(this, it, observer)
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
        FlowBus.observeSticky<EVENT>(this, it, observer)
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
        FlowBus.observeSticky<EVENT>(this, it, observer)
    }
}
