package io.legado.app.data.entities

/**
 * Android actual: 直接 typealias 到 java.time.LocalDate (minSdk 26 已含 java.time)。
 * Book.ReadConfig 等经 expect 引用的代码运行期即 java.time.LocalDate, 行为零变化。
 */
actual typealias LocalDate = java.time.LocalDate
