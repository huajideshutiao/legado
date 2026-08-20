package io.legado.app.help.book

import io.legado.app.data.entities.LocalDate
import io.legado.app.utils.ChineseUtils
import java.time.Period

/**
 * BookDisplayBridge actual (jvmAndAndroidMain)。
 *
 * 直接桥接到 jvmAndAndroidMain 的 ChineseUtils 和 java.time.Period。
 * Android (minSdk 26) 与 JVM (JDK 8+) 均含 java.time, 实现完全一致, 上提共用。
 */
actual fun chineseT2S(content: String): String = ChineseUtils.t2s(content)

actual fun chineseS2T(content: String): String = ChineseUtils.s2t(content)

actual fun periodDaysBetween(start: LocalDate?, end: LocalDate): Int =
    Period.between(start, end).days
