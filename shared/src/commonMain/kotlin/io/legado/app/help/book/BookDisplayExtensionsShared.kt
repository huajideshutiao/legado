@file:Suppress("unused")

package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.localDateNow
import kotlin.math.max
import kotlin.math.min

/*
 * Book/BookChapter 显示相关扩展下沉区 (shared commonMain)。
 *
 * 原 app 端 BookExtensions.kt / BookChapterExtensions.kt 中的部分扩展依赖
 * AppConfig + appDb.replaceRuleDao + ChineseUtils + CharSequence.replace(超时版),
 * 现 AppConfig/replaceRuleDao/超时版 replace 经 provider 间接访问
 * (AppConfigProviders/AppDbProviders/RegexReplacers), ChineseUtils 经 expect/actual
 * 桥接 (chineseT2S/chineseS2T 在 jvmMain/androidMain 调用 ChineseUtils.t2s/s2t),
 * 可整体下沉到本文件。
 *
 * 包名/函数签名不变, 消费方 import 零改动。
 * 注意: 同包名同签名扩展函数不允许在两个模块同时定义, 需从 app 端删除已下沉的扩展。
 */

/**
 * 是否模拟阅读进度。
 *
 * 原 app 端 BookExtensions.kt 中的扩展, 下沉到 shared (被 simulatedTotalChapterNum 调用)。
 */
fun Book.readSimulating(): Boolean {
    return config.readSimulating
}

/**
 * 根据当前日期计算章节总数 (模拟阅读进度)。
 *
 * 原 app 端 BookExtensions.kt 中的扩展, 下沉到 shared。
 */
fun Book.simulatedTotalChapterNum(): Int {
    return if (readSimulating()) {
        val currentDate = localDateNow()
        // 起始日期缺失 (旧备份/导入的 ReadConfig 可能 readSimulating=true 而 startDate=null)
        // 按今天起算, 对齐 getStartDate 与模拟追读弹窗空日期的语义; 直接传 null 会在
        // periodDaysBetween 崩 (原版 Period.between 同样崩, 此缺陷不复刻)
        val daysPassed = periodDaysBetween(config.startDate ?: currentDate, currentDate) + 1
        // 计算当前应该解锁到哪一章
        val chaptersToUnlock =
            max(0, (config.startChapter ?: 0) + (daysPassed * config.dailyChapters))
        min(totalChapterNum, chaptersToUnlock)
    } else {
        totalChapterNum
    }
}
