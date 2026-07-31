package io.legado.app.exception

/**
 * Kotlin/Native 无 java.lang.SecurityException, 补一个同名类型供 iOS/鸿蒙侧路径校验抛出。
 */
class SecurityException(message: String) : Exception(message)
