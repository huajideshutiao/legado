@file:Suppress("unused")

package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.localDateNow
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.utils.RegexReplacers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
 * java.time.Period 经 expect/actual 桥接 (periodDaysBetween), 可整体下沉到本文件。
 *
 * 包名/函数签名不变, 消费方 import 零改动。
 * 注意: 同包名同签名扩展函数不允许在两个模块同时定义, 需从 app 端删除已下沉的扩展。
 */

/**
 * 章节显示标题 (替换规则 + 简繁转换)。
 *
 * 原 app 端 BookChapterExtensions.kt 中的扩展, 下沉到 shared。
 * AppConfig.chineseConverterType → AppConfigProviders.get().chineseConverterType;
 * appDb.replaceRuleDao → AppDbProviders.get().replaceRuleDao;
 * CharSequence.replace(Regex, String, Long) → RegexReplacers.get().replace(...)
 * (app 端原扩展依赖 Coroutine.async/JsEngines/CrashHandler/appCtx 无法下沉)。
 * ChineseUtils.t2s/s2t → chineseT2S/chineseS2T expect/actual 桥接。
 */
fun BookChapter.getDisplayTitle(
    replaceRules: List<ReplaceRule>? = null,
    useReplace: Boolean = true,
    chineseConvert: Boolean = true,
): String {
    var displayTitle = title.replace(AppPattern.rnRegex, "")
    if (chineseConvert) {
        when (AppConfigProviders.get().chineseConverterType) {
            1 -> displayTitle = chineseT2S(displayTitle)
            2 -> displayTitle = chineseS2T(displayTitle)
        }
    }
    if (useReplace && replaceRules != null) kotlin.run {
        replaceRules.forEach { item ->
            if (item.pattern.isNotEmpty()) {
                try {
                    val mDisplayTitle = if (item.isRegex) {
                        RegexReplacers.get().replace(
                            displayTitle,
                            item.regex,
                            item.replacement,
                            item.getValidTimeoutMillisecond()
                        )
                    } else {
                        displayTitle.replace(item.pattern, item.replacement)
                    }
                    if (mDisplayTitle.isNotBlank()) {
                        displayTitle = mDisplayTitle
                    }
                } catch (_: RegexTimeoutException) {
                    item.isEnabled = false
                    // fire-and-forget: 错误恢复路径 (禁用坏规则), 不阻塞当前线程
                    // (避免 Native 端死锁, 与 ContentProcessorShared.kt 同语义实现)
                    GlobalScope.launch { AppDbProviders.get().replaceRuleDao.update(item) }
                } catch (_: CancellationException) {
                    return@run
                } catch (e: Exception) {
                    AppLog.put("${item.name}替换出错\n替换内容\n${displayTitle}", e)
                    AppLog.putNotSave("${item.name}替换出错", toast = true)
                }
            }
        }
    }
    return displayTitle
}

/**
 * 是否启用替换规则。
 *
 * 原 app 端 BookExtensions.kt 中的扩展, 下沉到 shared。
 * AppConfig.replaceEnableDefault → AppConfigProviders.get().replaceEnableDefault;
 * isImage/isEpub 已下沉到 BookExtensionsShared.kt。
 */
fun Book.getUseReplaceRule(): Boolean {
    return config.useReplaceRule ?: (!isImage && !isEpub && AppConfigProviders.get().replaceEnableDefault)
}

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
 * java.time.LocalDate.now() → localDateNow() (expect/actual);
 * java.time.Period.between(start, end).days → periodDaysBetween(start, end) (expect/actual)。
 */
fun Book.simulatedTotalChapterNum(): Int {
    return if (readSimulating()) {
        val currentDate = localDateNow()
        val startDate = config.startDate
        // startDate 为 null 时 periodDaysBetween 内部 Period.between 抛 NPE, 与原 jvmAndAndroidMain 行为一致
        val daysPassed = periodDaysBetween(startDate, currentDate) + 1
        // 计算当前应该解锁到哪一章
        val chaptersToUnlock =
            max(0, (config.startChapter ?: 0) + (daysPassed * config.dailyChapters))
        min(totalChapterNum, chaptersToUnlock)
    } else {
        totalChapterNum
    }
}

/**
 * 未读章节数 (原 app 端 BookExtensions.kt 中的扩展, 下沉到 shared)。
 *
 * 计算公式: (模拟总章节数 - 当前阅读章节索引) + (durChapterPos < 0 时 -1 兜底, 否则 0),
 * 结果 coerceAtLeast(0)。durChapterPos < 0 兜底 -1 与 app 端原实现一致 (pos 字段为负表示
 * 章节起始位置回退, 视为上一章未读完)。
 *
 * BookshelfComposables 下沉时由 shared 版 [io.legado.app.ui.bookshelf.ShelfListItem] 等条目调用。
 */
fun Book.getUnreadChapterNum(): Int =
    (simulatedTotalChapterNum() - durChapterIndex + if (durChapterPos < 0) -1 else 0)
        .coerceAtLeast(0)
