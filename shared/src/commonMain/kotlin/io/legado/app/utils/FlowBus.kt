package io.legado.app.utils

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 自研 EventBus 的 post 侧：MutableSharedFlow 逻辑，平台无关。
 * LifecycleOwner 观察面见 app 侧 FlowBusObserve.kt（照 FileDocIo 渗漏分层）。
 *
 * 原 ConcurrentHashMap 在 commonMain 无对应，改用 atomicfu 锁守护的 getOrPut，
 * 语义等价（同 key 只建一个 flow），emit/collect 走 flow 自身线程安全。
 */
object FlowBus {
    private val lock = SynchronizedObject()
    private val bus = HashMap<String, MutableSharedFlow<Any>>()
    private val stickyBus = HashMap<String, MutableSharedFlow<Any>>()

    fun with(key: String): MutableSharedFlow<Any> {
        return synchronized(lock) {
            bus.getOrPut(key) {
                MutableSharedFlow(
                    replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
            }
        }
    }

    fun withSticky(key: String): MutableSharedFlow<Any> {
        return synchronized(lock) {
            stickyBus.getOrPut(key) {
                MutableSharedFlow(
                    replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
            }
        }
    }
}
