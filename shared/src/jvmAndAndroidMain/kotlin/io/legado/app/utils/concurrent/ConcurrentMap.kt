package io.legado.app.utils.concurrent

import java.util.concurrent.ConcurrentHashMap

/**
 * `newConcurrentMap` 的 jvmAndAndroidMain actual 实现。
 *
 * 直接用 `ConcurrentHashMap`, 保持线程安全语义与原实现完全一致。
 */
actual fun <K, V> newConcurrentMap(): MutableMap<K, V> = ConcurrentHashMap()
