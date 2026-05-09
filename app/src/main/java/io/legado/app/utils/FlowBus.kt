package io.legado.app.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object FlowBus {
    private val bus = ConcurrentHashMap<String, MutableSharedFlow<Any>>()

    fun with(key: String): MutableSharedFlow<Any> {
        return bus.getOrPut(key) {
            MutableSharedFlow(
                replay = 1,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
    }

    inline fun <reified T> observe(
        lifecycleOwner: LifecycleOwner,
        key: String,
        noinline observer: (T) -> Unit
    ) {
        val flow = with(key)
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collectLatest { event ->
                    if (event is T) {
                        observer(event)
                    }
                }
            }
        }
    }
}
