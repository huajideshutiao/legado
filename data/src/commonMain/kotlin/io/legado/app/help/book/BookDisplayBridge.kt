package io.legado.app.help.book

/**
 * Book 显示扩展的跨端桥接函数 (commonMain expect 声明)。
 *
 * getDisplayTitle 依赖 ChineseUtils.t2s/s2t (ChineseUtils 公开签名泄漏 quick-transfer
 * TransType, 整体留各端 actual), 经 expect/actual 桥接。
 *
 * periodDaysBetween 已随 LocalDate 纯 Kotlin 化下沉 commonMain, 见同包 PeriodDays.kt。
 */
expect fun chineseT2S(content: String): String

expect fun chineseS2T(content: String): String
