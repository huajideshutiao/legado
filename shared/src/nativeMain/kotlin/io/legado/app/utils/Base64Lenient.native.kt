package io.legado.app.utils

/**
 * Base64Lenient 的 iOS/鸿蒙 actual 实现。
 *
 * 详见 commonMain/utils/Base64Lenient.kt expect 注释。
 * 纯 Kotlin 委托 commonMain 的 Base64LenientCore, 行为与 jvmAndAndroidMain 字节级一致。
 */
actual object Base64Lenient {

    actual fun decode(source: String): ByteArray = Base64LenientCore.decode(source)

    actual fun decodeStr(source: String?): String? = Base64LenientCore.decodeStr(source)

    actual fun decode(input: ByteArray): ByteArray = Base64LenientCore.decode(input)
}
