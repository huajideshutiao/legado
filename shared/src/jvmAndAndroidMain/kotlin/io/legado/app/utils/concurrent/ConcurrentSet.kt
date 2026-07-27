package io.legado.app.utils.concurrent

import java.util.concurrent.ConcurrentHashMap

/**
 * `newConcurrentSet` 的 jvmAndAndroidMain actual 实现。
 *
 * 直接委托 `ConcurrentHashMap.newKeySet()`, 保持线程安全语义与原实现完全一致
 * (SearchViewModel.bookshelf 跨协程读写)。
 */
actual fun <T> newConcurrentSet(): MutableSet<T> = ConcurrentHashMap.newKeySet()
