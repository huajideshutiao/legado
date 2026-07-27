package io.legado.app.data.entities

/**
 * LocalDate 的 iOS/鸿蒙 (Native target) 共用 actual 实现。
 *
 * 详见 commonMain/data/entities/LocalDate.kt expect 注释。
 * 纯 Kotlin 公历日期类 (年月日三字段), 与 jvmMain/androidMain 的 java.time.LocalDate 行为对齐。
 *
 * 不依赖 kotlinx-datetime (避免引入新依赖), 直接用纯 Kotlin 实现:
 * - actual typealias 到本文件内 public 类 LocalDateImpl, 不暴露 year/month/day 属性
 *   (与 jvmMain 的 java.time.LocalDate 经 typealias 后属性不可见于 commonMain 一致)
 * - 所有 commonMain 访问经 [toYearMonthDay] / [localDateNow] / [localDateOf] expect fun 完成
 * - LocalDateAsGsonSerializer 用上述 expect fun 读写 LocalDate 字段, 行为与 jvmAndAndroidMain 一致
 *
 * # 共用原因
 * iOS 与鸿蒙两端 LocalDate 实现完全一致 (纯 Kotlin, 无平台 API 依赖),
 * 故下沉到 nativeMain 共用, 避免代码重复。
 */
actual typealias LocalDate = LocalDateImpl

// KMP 修复: LocalDateImpl 改 public (原 internal)
// 原因: Room KSP 在 iOS/鸿蒙/Kotlin-Native 下展开 actual typealias LocalDate = LocalDateImpl 时,
// internal 可见性让 KSP 生成的 AppDatabase_Impl 等外部代码无法访问 LocalDateImpl 元信息,
// 导致 BookChapter.ForeignKey 跨实体解析失败 (因 Book.ReadConfig.startDate: LocalDate?).
// commonMain 仍通过 expect fun (toYearMonthDay/localDateNow/localDateOf) 访问,
// 不直接暴露 year/month/day 属性 (因 LocalDate 是 expect class, commonMain 看不到 LocalDateImpl 属性).
class LocalDateImpl(val year: Int, val month: Int, val day: Int) {
    override fun toString(): String {
        val m = if (month < 10) "0$month" else month.toString()
        val d = if (day < 10) "0$day" else day.toString()
        return "$year-$m-$d"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalDateImpl) return false
        return year == other.year && month == other.month && day == other.day
    }

    override fun hashCode(): Int = 31 * (31 * year + month) + day
}

actual fun localDateNow(): LocalDate {
    val (y, m, d) = io.legado.app.utils.yearMonthDayFromMillis(io.legado.app.utils.systemCurrentTimeMillis())
    return LocalDateImpl(y, m, d)
}

actual fun localDateOf(year: Int, month: Int, dayOfMonth: Int): LocalDate =
    LocalDateImpl(year, month, dayOfMonth)

actual fun LocalDate.toYearMonthDay(): Triple<Int, Int, Int> {
    val impl = this as LocalDateImpl
    return Triple(impl.year, impl.month, impl.day)
}
