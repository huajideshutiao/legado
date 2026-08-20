package io.legado.app.data.entities

/**
 * jvmAndAndroid actual: 桥接 java.time.LocalDate 静态方法与属性到 commonMain top-level expect fun。
 * LocalDate 类型本身经 actual typealias 直接等于 java.time.LocalDate
 * (Android minSdk 26 与 JDK 8+ 均含 java.time)。
 */
actual fun localDateNow(): LocalDate = java.time.LocalDate.now()

actual fun localDateOf(year: Int, month: Int, dayOfMonth: Int): LocalDate =
    java.time.LocalDate.of(year, month, dayOfMonth)

actual fun LocalDate.toYearMonthDay(): Triple<Int, Int, Int> =
    Triple(year, monthValue, dayOfMonth)
