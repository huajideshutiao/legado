package io.legado.app.utils

/**
 * [platformSleep] 的 JVM/Android actual: 委托 [Thread.sleep], 行为与原 jvmAndAndroidMain
 * 内联调用一致。
 */
internal actual fun platformSleep(millis: Long) {
    Thread.sleep(millis)
}
