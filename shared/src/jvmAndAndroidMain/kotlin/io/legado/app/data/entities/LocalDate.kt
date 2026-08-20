package io.legado.app.data.entities

/**
 * jvmAndAndroid actual: 直接 typealias 到 java.time.LocalDate
 * (Android minSdk 26 与 JDK 8+ 均含 java.time)。
 * Book.ReadConfig 等经 expect 引用的代码运行期即 java.time.LocalDate, 行为零变化。
 */
actual typealias LocalDate = java.time.LocalDate
