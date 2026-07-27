package io.legado.app.utils

/**
 * 时间相关 expect/actual 门面。
 *
 * 下沉件（commonMain）需获取"当前系统时间毫秒"或"任意时间戳对应的本地年月日"时，
 * 走本 expect；actual 在 jvmAndAndroidMain（android + jvm 共用 java.time/java.util.Calendar 实现）。
 *
 * 不引入 kotlinx-datetime 依赖：仅 ReadRecord.dayKey 用到 Calendar，包装为 expect/actual 即可。
 */

/** 等价于 java.lang.System.currentTimeMillis()，返回当前系统时间（UTC 毫秒）。 */
expect fun systemCurrentTimeMillis(): Long

/**
 * 等价于 java.lang.System.nanoTime()，返回当前 JVM 高精度纳秒计时值。
 *
 * IntentData.put(data) 原用 `System.nanoTime().toString()` 作为 bigData 键，
 * 下沉 commonMain 后走本 expect；actual 在 jvmAndAndroidMain 委托 System.nanoTime()。
 * 仅用作生成唯一键，不依赖绝对时间含义。
 */
expect fun systemNanoTime(): Long

/**
 * 将 epoch 毫秒时间戳转换为本地时区的 (年, 月, 日) 三元组。
 *
 * 月从 1 开始计数（与 java.util.Calendar.MONTH + 1 对齐），日从 1 开始。
 * 用于 ReadRecord.dayKey 等"按本地日期分桶"的场景。
 */
expect fun yearMonthDayFromMillis(epochMillis: Long): Triple<Int, Int, Int>

/**
 * 给定 yyyyMMdd 整数日期键（如 20260525），返回前一天的 yyyyMMdd 整数键。
 *
 * 用于 RestoreShared.restoreOldRecord 旧格式阅读记录迁移算法的"按天倒推"，
 * 与 app 端 [io.legado.app.help.storage.Restore.restoreOldRecord] 中
 * `cal.add(Calendar.DAY_OF_MONTH, -1)` 语义一致。
 *
 * @see io.legado.app.help.storage.RestoreShared
 */
expect fun prevDayKey(dayKey: Int): Int

/**
 * 给定 yyyyMMdd 整数日期键（如 20260525），返回当天本地 0 点的 epoch 秒时间戳。
 *
 * 用于 RestoreShared.restoreOldRecord 旧格式阅读记录迁移算法的"按天分桶窗口"，
 * 与 app 端 [io.legado.app.help.storage.Restore.restoreOldRecord] 中
 * `cal.clear(); cal.set(y, m-1, d); cal.timeInMillis / 1000` 语义一致。
 *
 * @see io.legado.app.help.storage.RestoreShared
 */
expect fun midnightSecFromDayKey(dayKey: Int): Long
