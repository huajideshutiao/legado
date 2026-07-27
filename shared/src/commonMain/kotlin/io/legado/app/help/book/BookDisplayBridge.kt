package io.legado.app.help.book

import io.legado.app.data.entities.LocalDate

/**
 * Book 显示扩展的 JVM 专属桥接函数 (commonMain expect 声明)。
 *
 * BookDisplayExtensionsShared 中 getDisplayTitle 依赖 ChineseUtils.t2s/s2t
 * (ChineseUtils 公开签名泄漏 quick-transfer TransType, 整体留 jvmAndAndroidMain),
 * simulatedTotalChapterNum 依赖 java.time.Period.between, 二者均不可直接在
 * commonMain 引用, 经 expect/actual 桥接:
 * - chineseT2S/chineseS2T: jvmAndAndroidMain 调用 ChineseUtils.t2s/s2t
 * - periodDaysBetween: jvmAndAndroidMain 调用 Period.between(start, end).days
 *
 * 行为与原 jvmAndAndroidMain 直接调用完全一致, 仅多一层 expect/actual 间接。
 */
expect fun chineseT2S(content: String): String

expect fun chineseS2T(content: String): String

// start 接收 nullable 以复刻原 java.time.Period.between 的行为 (null 时抛 NPE, 与原 jvmAndAndroidMain 一致)
expect fun periodDaysBetween(start: LocalDate?, end: LocalDate): Int
