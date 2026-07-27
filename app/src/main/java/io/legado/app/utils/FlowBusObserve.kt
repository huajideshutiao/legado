package io.legado.app.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * FlowBus 的 LifecycleOwner 观察面（Android 渗漏）。
 * post 侧核心见 FlowBus.kt；KJ3 下沉时本面留 androidMain / CMP 走 collectAsState。
 * 扩展函数形式保持 FlowBus.observe/observeSticky 调用签名零变化。
 */
inline fun <reified T> FlowBus.observe(
    lifecycleOwner: LifecycleOwner, key: String, noinline observer: (T) -> Unit
) {
    val flow = with(key)
    lifecycleOwner.lifecycleScope.launch {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
            flow.collect { event ->
                if (event is T) {
                    observer(event)
                }
            }
        }
    }
}

inline fun <reified T> FlowBus.observeSticky(
    lifecycleOwner: LifecycleOwner, key: String, noinline observer: (T) -> Unit
) {
    val flow = withSticky(key)
    lifecycleOwner.lifecycleScope.launch {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
            flow.collect { event ->
                if (event is T) {
                    observer(event)
                }
            }
        }
    }
}
