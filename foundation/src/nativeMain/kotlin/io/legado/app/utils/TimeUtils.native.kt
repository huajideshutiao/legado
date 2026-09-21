package io.legado.app.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.CLOCK_MONOTONIC
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.localtime_r
import platform.posix.time
import platform.posix.time_tVar
import platform.posix.timespec
import platform.posix.tm

/**
 * TimeUtils 的 iOS/鸿蒙 actual。
 *
 * 详见 commonMain/utils/TimeUtils.shared.kt expect 注释。
 * - [systemCurrentTimeMillis]: posix clock_gettime(CLOCK_REALTIME) (epoch 毫秒)
 * - [systemNanoTime]: posix clock_gettime(CLOCK_MONOTONIC) (单调递增纳秒计时)
 * - [yearMonthDayFromMillis]: 纯 Kotlin 公历换算 (与 java.util.Calendar 本地时区语义对齐)
 *
 * 注: epoch 毫秒 → 本地日期需考虑时区。iOS/鸿蒙无 java.util.TimeZone,
 * 用 kotlinx-datetime 或自行处理 epoch → 本地日期。
 * 本实现用纯 Kotlin 算法, 按本地时区偏移量 (毫秒) 计算, 行为等价 java.util.Calendar 默认时区。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun systemCurrentTimeMillis(): Long = memScoped {
    // kotlin.system.getTimeMillis 自 2.1 起为 error 级 deprecation, 改走 posix
    // CLOCK_* 常量与 clockid_t 的宽度各平台绑定不一 (darwin UInt / linux Int), 用 convert() 统一
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME.convert(), ts.ptr)
    ts.tv_sec * 1_000L + ts.tv_nsec / 1_000_000L
}

@OptIn(ExperimentalForeignApi::class)
actual fun systemNanoTime(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_MONOTONIC.convert(), ts.ptr)
    ts.tv_sec * 1_000_000_000L + ts.tv_nsec
}

actual fun yearMonthDayFromMillis(epochMillis: Long): Triple<Int, Int, Int> {
    // 本地时区偏移量 (毫秒), 见 [currentLocalOffsetMillis] (POSIX localtime_r, iOS/鸿蒙共用)。
    // 与 jvmAndAndroidMain 的 java.util.Calendar 默认时区语义对齐: 本地日分桶 (ReadRecord.dayKey)。
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
 * 与 jvmAndAndroidMain.midnightSecFromDayKey 同语义 (本地 0 点 epoch 秒), 纯 Kotlin 实现。
 * days_from_civil(y, m, d) → days since 1970-01-01 → * 86400 (秒) + 本地时区偏移。
 * 注: 偏移量取当前时区偏移 (与 jvmAndAndroidMain Calendar 的"当日本地午夜"在 DST 切换日
 * 存在 ±1h 近似差, 常规日完全一致)。
 */
actual fun midnightSecFromDayKey(dayKey: Int): Long {
    val y = dayKey / 10000
    val m = (dayKey / 100) % 100
    val d = dayKey % 100
    // daysFromCivil 已返回"自 1970-01-01 起的天数", 不能再减一次纪元偏移
    return daysFromCivil(y, m, d) * 86_400L + currentLocalOffsetMillis() / 1_000L
}

/**
 * 漫画信息条 HH:mm: 本地时区换算 (与 jvmAndAndroidMain Calendar 的本地时区语义对齐)。
 */
actual fun formatTimeOfDay(epochMillis: Long): String {
    val localMillis = epochMillis + currentLocalOffsetMillis()
    val millisOfDay = localMillis.mod(86_400_000L)
    val totalMinutes = (millisOfDay / 60_000L).toInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}

/**
 * 获取当前本地时区偏移量 (毫秒)。
 *
 * POSIX localtime_r (iOS/鸿蒙共用, nativeMain 不依赖平台专属 API, 与 clock_gettime 同模式):
 * 1. time() 取当前 epoch 秒
 * 2. localtime_r 换算为本地钟面时间 (已含 DST)
 * 3. 把本地钟面时间按 UTC 解释回算 epoch 秒 (days_from_civil 反算)
 * 4. 差值即本地偏移 (标准时 + 夏令时), 与 NSTimeZone.localTimeZone.secondsFromGMT 等价
 *
 * 失败 (localtime_r 返回 null) 时回退 0 (UTC), 不抛异常。
 * internal: iOS/鸿蒙 AppLogHost 的 timeZoneOffsetMillis 复用同一份换算, 不再各自接平台 TZ API。
 */
@OptIn(ExperimentalForeignApi::class)
internal fun currentLocalOffsetMillis(): Long = memScoped {
    val now = alloc<time_tVar>()
    time(now.ptr)
    val tm = alloc<tm>()
    if (localtime_r(now.ptr, tm.ptr) == null) return@memScoped 0L
    // tm 字段是 Int (Darwin/Linux 绑定一致): tm_year 自 1900, tm_mon 0 起
    val localAsUtc = daysFromCivil(tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday) * 86_400L +
        tm.tm_hour * 3_600L + tm.tm_min * 60L + tm.tm_sec
    (localAsUtc - now.value) * 1_000L
}

/** 纯 Kotlin 公历换算: epoch 毫秒 → (year, month, day) (基于 [civilFromDays])。 */
private fun epochToYmd(epochMillis: Long): Triple<Int, Int, Int> {
    val days = epochMillis.floorDiv(86_400_000L).toInt()
    // days 自 1970-01-01 起; civilFromDays 接受自 1970-01-01 起的天数
    return civilFromDays(days.toLong())
}

/**
 * Howard Hinnant civil_from_days 算法: 自 1970-01-01 起的天数 → (year, month, day)。
 * 与 jvmAndAndroidMain 的 java.util.Calendar 本地日期语义对齐 (配合 [currentLocalOffsetMillis])。
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
    // 算术传播使 year/m/d 为 Long; 调用方期望 Int (年月日均落在 Int 范围内)
    return Triple(year.toInt(), m.toInt(), d.toInt())
}

/**
 * Howard Hinnant days_from_civil 算法: (year, month, day) → 自 1970-01-01 起的天数。
 * 与 [civilFromDays] 互逆, 用于 [prevDayKey] / [midnightSecFromDayKey]。
 */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    // Hinnant 的 mp = m>2 ? m-3 : m+9; 下面 doy 里再减 3, 故这里对 1/2 月加 12
    val m = if (month > 2) month else month + 12
    val era = if (y >= 0) y / 400 else (y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m - 3) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return (era * 146_097 + doe - 719_468).toLong()
}
