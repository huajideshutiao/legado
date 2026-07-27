package io.legado.app.utils.concurrent

import java.util.Collections

/**
 * `newSynchronizedList` 的 jvmAndAndroidMain actual 实现。
 *
 * 直接委托 `java.util.Collections.synchronizedList(delegate)`, 保持线程安全语义
 * 与原实现完全一致 (返回的 List 所有方法均 synchronized)。
 */
actual fun <T> newSynchronizedList(delegate: MutableList<T>): MutableList<T> =
    Collections.synchronizedList(delegate)
