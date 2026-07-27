package io.legado.app.help

import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import splitties.init.appCtx

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

}
