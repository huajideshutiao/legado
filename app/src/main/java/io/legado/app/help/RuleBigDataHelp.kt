package io.legado.app.help

import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import splitties.init.appCtx

/**
 * [RuleBigDataProvider] app 端实现 (Android 专属)。
 *
 * 核心逻辑已下沉 shared commonMain 的 [RuleBigDataShared] (纯文件持久化实现),
 * 本文件仅保留 Android 专属部分: appCtx.externalFiles/ruleData/book 路径注入,
 * put/get/has 等全部委托 [RuleBigDataShared], 行为与下沉前完全一致。
 *
 * desktop/ios/ohos 端各自注册对应路径的 [RuleBigDataShared] 实例,
 * 见 RegisterNativeSourceProviders / 各端 ProviderRegistry。
 */
object RuleBigDataHelp : RuleBigDataProvider {

    private val ruleDataDir = FileUtils.createFolderIfNotExist(appCtx.externalFiles, "ruleData")
    internal val bookData = FileUtils.createFolderIfNotExist(ruleDataDir, "book")

    // 注入 appCtx.externalFiles/ruleData/book 路径, 纯逻辑委托 shared commonMain
    private val shared = RuleBigDataShared(bookData.absolutePath)

    override fun putBookVariable(bookUrl: String, key: String, value: String?) =
        shared.putBookVariable(bookUrl, key, value)

    override fun getBookVariable(bookUrl: String, key: String?): String? =
        shared.getBookVariable(bookUrl, key)

    override fun hasBookVariable(bookUrl: String, key: String): Boolean =
        shared.hasBookVariable(bookUrl, key)

    override fun putChapterVariable(bookUrl: String, chapterUrl: String, key: String, value: String?) =
        shared.putChapterVariable(bookUrl, chapterUrl, key, value)

    override fun getChapterVariable(bookUrl: String, chapterUrl: String, key: String): String? =
        shared.getChapterVariable(bookUrl, chapterUrl, key)

    override fun listBookDataDirs(): List<String> =
        shared.listBookDataDirs()

    override fun clearInvalidBookData(bookUrls: Set<String>) =
        shared.clearInvalidBookData(bookUrls)

}
