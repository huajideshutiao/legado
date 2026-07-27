package io.legado.app.help

import io.legado.app.help.storage.BackupFileOps
import io.legado.app.utils.MD5Utils

/**
 * RuleBigData 文件存储纯逻辑 (shared commonMain)。
 *
 * app 端 [RuleBigDataHelp] 持有 Android 专属的 appCtx.externalFiles 路径派生,
 * 本类仅接收 bookDataDir 字符串路径, 委托 [BackupFileOps] 跨平台文件 I/O,
 * 行为与 app 端原实现逐分支一致。
 *
 * MD5 走 commonMain 的 [MD5Utils] expect; 文件操作走 [BackupFileOps] expect
 * (jvmAndAndroidMain actual 委托 FileUtilsBase, 行为零变化)。
 *
 * 桌面端 RuleBigDataProvider 用 in-memory 实现 (DesktopRuleBigDataProvider),
 * 不复用本类; 本类供 app 端及未来需要文件持久化的端复用。
 */
class RuleBigDataShared(
    private val bookDataDir: String
) : RuleBigDataProvider {

    private val sep = BackupFileOps.separator

    override fun putBookVariable(bookUrl: String, key: String, value: String?) {
        val md5BookUrl = MD5Utils.md5Encode(bookUrl)
        val md5Key = MD5Utils.md5Encode(key)
        if (value == null) {
            BackupFileOps.delete(joinPath(bookDataDir, md5BookUrl, "$md5Key.txt"))
        } else {
            BackupFileOps.writeText(joinPath(bookDataDir, md5BookUrl, "$md5Key.txt"), value)
            val bookUrlPath = joinPath(bookDataDir, md5BookUrl, "bookUrl.txt")
            if (!BackupFileOps.exists(bookUrlPath)) {
                BackupFileOps.writeText(bookUrlPath, bookUrl)
            }
        }
    }

    override fun getBookVariable(bookUrl: String, key: String?): String? {
        val md5BookUrl = MD5Utils.md5Encode(bookUrl)
        val md5Key = MD5Utils.md5Encode(key)
        val path = joinPath(bookDataDir, md5BookUrl, "$md5Key.txt")
        return if (BackupFileOps.exists(path)) BackupFileOps.readText(path) else null
    }

    override fun hasBookVariable(bookUrl: String, key: String): Boolean {
        val md5BookUrl = MD5Utils.md5Encode(bookUrl)
        val md5Key = MD5Utils.md5Encode(key)
        return BackupFileOps.exists(joinPath(bookDataDir, md5BookUrl, "$md5Key.txt"))
    }

    override fun putChapterVariable(bookUrl: String, chapterUrl: String, key: String, value: String?) {
        val md5BookUrl = MD5Utils.md5Encode(bookUrl)
        val md5ChapterUrl = MD5Utils.md5Encode(chapterUrl)
        val md5Key = MD5Utils.md5Encode(key)
        if (value == null) {
            BackupFileOps.delete(joinPath(bookDataDir, md5BookUrl, md5ChapterUrl, "$md5Key.txt"))
        } else {
            BackupFileOps.writeText(
                joinPath(bookDataDir, md5BookUrl, md5ChapterUrl, "$md5Key.txt"), value
            )
            val bookUrlPath = joinPath(bookDataDir, md5BookUrl, "bookUrl.txt")
            if (!BackupFileOps.exists(bookUrlPath)) {
                BackupFileOps.writeText(bookUrlPath, bookUrl)
            }
        }
    }

    override fun getChapterVariable(bookUrl: String, chapterUrl: String, key: String): String? {
        val md5BookUrl = MD5Utils.md5Encode(bookUrl)
        val md5ChapterUrl = MD5Utils.md5Encode(chapterUrl)
        val md5Key = MD5Utils.md5Encode(key)
        val path = joinPath(bookDataDir, md5BookUrl, md5ChapterUrl, "$md5Key.txt")
        return if (BackupFileOps.exists(path)) BackupFileOps.readText(path) else null
    }

    override fun listBookDataDirs(): List<String> {
        return listOf(bookDataDir)
    }

    override fun clearInvalidBookData(bookUrls: Set<String>) {
        // 待后续实现: 遍历 bookDataDir 子目录, 读 bookUrl.txt, 不在 bookUrls 中则删除
    }

    /** 路径拼接 (对齐 FileUtils.getPath: 跳过空串, 仅在非分隔符结尾时补分隔符)。 */
    private fun joinPath(root: String, vararg parts: String): String {
        val sb = StringBuilder(root)
        parts.forEach { part ->
            if (part.isNotEmpty()) {
                if (!sb.endsWith(sep)) sb.append(sep)
                sb.append(part)
            }
        }
        return sb.toString()
    }
}
