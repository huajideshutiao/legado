package io.legado.app.model.fileBook

/**
 * [ZipIOException] 的 iOS/鸿蒙 actual 实现 (nativeMain 中间源集共用)。
 *
 * 详见 commonMain/model/fileBook/ZipIOException.kt expect 注释。
 *
 * - jvmAndAndroidMain: `actual typealias ZipIOException = java.io.IOException`
 *   (JVM 半区 zip 解析异常即 java.io.IOException)
 * - iOS/鸿蒙 (Kotlin/Native): 无 java.io.IOException (JVM-only),
 *   这里用纯 Kotlin Exception 子类作为等价类型
 *
 * commonMain 侧 catch (e: ZipIOException) 在两端均可捕获:
 * - JVM/Android 端: 捕获 java.io.IOException
 * - iOS/鸿蒙端: 捕获本 actual class 实例
 *
 * 注: 两端不二进制兼容 (彼端 typealias 到 java.io.IOException), 但经 expect/actual
 * 在 commonMain 视角下类型统一, 不影响 commonMain 编译与跨平台异常处理语义。
 *
 * iOS/鸿蒙均走 Kotlin/Native target, 不能用 java.lang / java.util 包,
 * 纯 Kotlin Exception 子类编译通过, 两端 actual 实现策略一致, 下沉到 nativeMain 共用。
 */
actual open class ZipIOException actual constructor(message: String) : Exception(message)
