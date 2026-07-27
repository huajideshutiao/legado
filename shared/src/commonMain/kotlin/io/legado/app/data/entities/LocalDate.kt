package io.legado.app.data.entities

/**
 * K5-c Phase 4: Book/ReadConfig 下沉 commonMain 的 java.time.LocalDate 抽象面。
 *
 * commonMain 不允许直接引用 java.time.*; 经 expect class + actual typealias 桥接到
 * 各端 java.time.LocalDate (androidMain/jvmMain)。
 *
 * Kotlin 限制: actual typealias 到 Java 类时, Java 类的 getter (getYear()/getMonthValue()/
 * getDayOfMonth()) 不被识别为 expect class 的 val 成员, 故 expect class 不能声明这些属性。
 * 改用 top-level expect fun (localDateNow / localDateOf / toYearMonthDay) 暴露所需 API。
 *
 * Book.ReadConfig.startDate 字段 + LocalDateAsGsonSerializer 用本 expect 类型; jvmAndAndroidMain
 * 中 BookDisplayExtensionsShared 等通过 commonMain 间接引用, 编译期解析为 java.time.LocalDate,
 * 原有 Period.between / LocalDate.parse / LocalDate.now 等调用零行为变化。
 */
expect class LocalDate

/**
 * 桥接 java.time.LocalDate.now()。
 */
expect fun localDateNow(): LocalDate

/**
 * 桥接 java.time.LocalDate.of(year, month, dayOfMonth)。
 */
expect fun localDateOf(year: Int, month: Int, dayOfMonth: Int): LocalDate

/**
 * 桥接 java.time.LocalDate 的 year/monthValue/dayOfMonth 三个属性,
 * 用于 LocalDateAsGsonSerializer.serialize() 中读取字段值。
 * 返回 Triple<year, monthValue, dayOfMonth>。
 */
expect fun LocalDate.toYearMonthDay(): Triple<Int, Int, Int>
