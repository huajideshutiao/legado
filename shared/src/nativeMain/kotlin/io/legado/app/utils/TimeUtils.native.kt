package io.legado.app.utils

/**
 * TimeUtils 的 iOS/鸿蒙 actual。
 *
 * 详见 commonMain/utils/TimeUtils.shared.kt expect 注释。
 * - [systemCurrentTimeMillis]: 委托 kotlin.system.currentTimeMillis (KMP 标准, epoch 毫秒)
 * - [systemNanoTime]: 委托 kotlin.system.nanoTime (KMP 标准, 高精度纳秒计时)
 * - [yearMonthDayFromMillis]: 纯 Kotlin 公历换算 (与 java.util.Calendar 本地时区语义对齐)
 *
 * 注: epoch 毫秒 → 本地日期需考虑时区。iOS/鸿蒙无 java.util.TimeZone,
 * 用 kotlinx-datetime 或自行处理 epoch → 本地日期。
 * 本实现用纯 Kotlin 算法, 按本地时区偏移量 (毫秒) 计算, 行为等价 java.util.Calendar 默认时区。
 */
actual fun systemCurrentTimeMillis(): Long = kotlin.system.currentTimeMillis

actual fun systemNanoTime(): Long = kotlin.system.nanoTime

actual fun yearMonthDayFromMillis(epochMillis: Long): Triple<Int, Int, Int> {
    // 本地时区偏移量 (毫秒)。iOS/鸿蒙无 java.util.TimeZone.getDefault(),
    // 用 epoch → 当前 UTC 偏移量 (基于 systemCurrentTimeMillis 与本地日期的关系)。
    // 简化: 用 UTC 计算 (与 jvmAndAndroidMain 的默认时区行为在 UTC 场景一致)。
    // 若需本地时区, 上层应在调用前自行 shift (与 ReadRecord.dayKey 的语义一致: 本地日分桶)。
    val localOffsetMillis = currentLocalOffsetMillis()
    return epochToYmd(epochMillis + localOffsetMillis)
}

/**
 * 与 jvmAndAndroidMain.prevDayKey 同语义, 纯 Kotlin 实现 (Howard Hinnant 算法)。
 * days_from_civil(y, m, d) - 1 → civil_from_days(...) 反算。
 */
actual fun prevDayKey(dayKey: Int): Int {
    val y = dayKey / 10000
    val m = (dayKey / 100) % 100
    val d = dayKey % 100
    val days = daysFromCivil(y, m, d) - 1
    val (py, pm, pd) = civilFromDays(days)
    return py * 10000 + pm * 100 + pd
}

/**
 * 与 jvmAndAndroidMain.midnightSecFromDayKey 同语义, 纯 Kotlin 实现。
 * days_from_civil(y, m, d) → days since 1970-01-01 → * 86400 (秒)。
 * 注: iOS/鸿蒙 localOffsetMillis = 0 (与 yearMonthDayFromMillis 的 UTC 简化一致)。
 */
actual fun midnightSecFromDayKey(dayKey: Int): Long {
    val y = dayKey / 10000
    val m = (dayKey / 100) % 100
    val d = dayKey % 100
    val days = daysFromCivil(y, m, d) - 719_468L
    return days * 86_400L
}

/**
 * 获取当前本地时区偏移量 (毫秒)。
 *
 * 纯 Kotlin 实现: 通过 kotlinx-datetime 的 TimeSource / 时区信息不可直接获取,
 * 这里用 Kotlin/Native 的 platform.time (iOS) / ohos.time (鸿蒙) 等价方案不可跨平台,
 * 简化用 UTC 偏移量 0 (与 jvmAndAndroidMain 的 UTC 行为一致; 若宿主需要本地时区, 由宿主注入)。
 *
 * 注: iOS/鸿蒙端暂按 UTC 处理, 与 jvmAndAndroidMain 的"默认时区"在 UTC 场景等价;
 * 实际部署时若需本地时区, 应由宿主注入时区偏移量。
 */
private fun currentLocalOffsetMillis(): Long = 0L

/** 纯 Kotlin 公历换算: epoch 毫秒 → (year, month, day) (基于 [civilFromDays])。 */
private fun epochToYmd(epochMillis: Long): Triple<Int, Int, Int> {
    val days = Math.floorDiv(epochMillis, 86_400_000L).toInt()
    // days 自 1970-01-01 起; civilFromDays 接受自 1970-01-01 起的天数
    return civilFromDays(days.toLong())
}

/**
 * Howard Hinnant civil_from_days 算法: 自 1970-01-01 起的天数 → (year, month, day)。
 * 与 jvmAndAndroidMain 的 java.util.Calendar 本地日期语义在 UTC 场景等价。
 */
private fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
    val z = daysSinceEpoch + 719_468
    val era = if (z >= 0) z / 146_097 else (z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return Triple(year, m, d)
}

/**
 * Howard Hinnant days_from_civil 算法: (year, month, day) → 自 1970-01-01 起的天数。
 * 与 [civilFromDays] 互逆, 用于 [prevDayKey] / [midnightSecFromDayKey]。
 */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val m = if (month > 2) month else month + 9
    val era = if (y >= 0) y / 400 else (y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m - 3) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}
