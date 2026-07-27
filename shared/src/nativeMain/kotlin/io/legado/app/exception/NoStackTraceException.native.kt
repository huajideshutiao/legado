package io.legado.app.exception

/**
 * NoStackTraceException 的 iOS/鸿蒙 actual 实现。
 *
 * 详见 commonMain/exception/NoStackTraceException.kt expect 注释。
 *
 * Kotlin/Native 默认栈采集与 JVM 不同 (K/N 异常栈采集能力受限, 通常无栈或仅原生栈),
 * 直接继承 Exception 即可, 无需 override fillInStackTrace (K/N 中该方法行为与 JVM 不同)。
 *
 * 行为与 jvmAndAndroidMain 等价: 异常创建不带栈, 抛出/捕获语义不变。
 */
actual open class NoStackTraceException actual constructor(msg: String) : Exception(msg)
