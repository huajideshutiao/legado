package io.legado.app.help

import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import splitties.init.appCtx
import java.io.File

object RuleBigDataHelp {

    private val ruleDataDir = FileUtils.createFolderIfNotExist(appCtx.externalFiles, "ruleData")
    internal val bookData = FileUtils.createFolderIfNotExist(ruleDataDir, "book")

    fun putBookVariable(bookUrl: String, key: String, value: String?) {
        val md5BookUrl = MD5Utils.md5Encode(bookUrl)
        val md5Key = MD5Utils.md5Encode(key)
        if (value == null) {
            FileUtils.delete(FileUtils.getPath(bookData, md5BookUrl, "$md5Key.txt"), true)
        } else {
            val valueFile = FileUtils.createFileIfNotExist(bookData, md5BookUrl, "$md5Key.txt")
            valueFile.writeText(value)
            val bookUrlFile = File(FileUtils.getPath(bookData, md5BookUrl, "bookUrl.txt"))
            if (!bookUrlFile.exists()) {
                bookUrlFile.writeText(bookUrl)
            }
        }
    }

    fun getBookVariable(bookUrl: String, key: String?): String? {
        val md5BookUrl = MD5Utils.md5Encode(bookUrl)
        val md5Key = MD5Utils.md5Encode(key)
        val file = File(FileUtils.getPath(bookData, md5BookUrl, "$md5Key.txt"))
        if (file.exists()) {
            return file.readText()
        }
        return null
    }

    fun hasBookVariable(bookUrl: String, key: String): Boolean {
        val md5BookUrl = MD5Utils.md5Encode(bookUrl)
        val md5Key = MD5Utils.md5Encode(key)
        val file = File(FileUtils.getPath(bookData, md5BookUrl, "$md5Key.txt"))
        return file.exists()
    }

    fun putChapterVariable(bookUrl: String, chapterUrl: String, key: String, value: String?) {
        val md5BookUrl = MD5Utils.md5Encode(bookUrl)
        val md5ChapterUrl = MD5Utils.md5Encode(chapterUrl)
        val md5Key = MD5Utils.md5Encode(key)
        if (value == null) {
            FileUtils.delete(FileUtils.getPath(bookData, md5BookUrl, md5ChapterUrl, "$md5Key.txt"))
        } else {
            val valueFile =
                FileUtils.createFileIfNotExist(bookData, md5BookUrl, md5ChapterUrl, "$md5Key.txt")
            valueFile.writeText(value)
            val bookUrlFile = File(FileUtils.getPath(bookData, md5BookUrl, "bookUrl.txt"))
            if (!bookUrlFile.exists()) {
                bookUrlFile.writeText(bookUrl)
            }
        }
    }

    fun getChapterVariable(bookUrl: String, chapterUrl: String, key: String): String? {
        val md5BookUrl = MD5Utils.md5Encode(bookUrl)
        val md5ChapterUrl = MD5Utils.md5Encode(chapterUrl)
        val md5Key = MD5Utils.md5Encode(key)
        val file = File(FileUtils.getPath(bookData, md5BookUrl, md5ChapterUrl, "$md5Key.txt"))
        if (file.exists()) {
            return file.readText()
        }
        return null
    }

}