package io.legado.app.ui.book.bookmark

import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.constant.AppLog
import io.legado.app.constant.ThreadSafeDateFormat
import io.legado.app.data.AppDbProviders
import io.legado.app.data.dao.sortedByLocalizedOrder
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 书签导出共用实现 (目录页 TocRoute 与全本书签页 BookmarkRoute 两入口共用)。
 *
 * 原两入口各自内联同构流程 (文件选择器 → 读库 → 拼内容 → 写文件), 收敛于此:
 * - JSON: 书签列表全量序列化 (对照原版 viewModel.saveBookmark)
 * - Markdown: 每本书一节 (书名作者 + 导出时间/书签数元信息), 节内按章节聚合,
 *   条目为引用块原文 + 可选笔记 + 创建时间 (对照原版 viewModel.saveBookmarkMd, 格式重排)
 *
 * 文件名由调用方决定 (目录页带书名, 全本书签页带时间戳), 保持各入口现状。
 */
object BookmarkExporter {

    private val dateFormat = ThreadSafeDateFormat("yyyy-MM-dd HH:mm")

    /**
     * 导出书签 JSON。
     *
     * @param bookName 非空时仅导出该书书签; 空则导出全部书签 (全本书签页入口)
     */
    suspend fun exportJson(fileName: String, bookName: String?, bookAuthor: String?) {
        export(fileName) {
            Json.encodeToString(loadBookmarks(bookName, bookAuthor))
        }
    }

    /** 导出书签 Markdown, 参数语义同 [exportJson]。 */
    suspend fun exportMd(fileName: String, bookName: String?, bookAuthor: String?) {
        val exportedAt = systemCurrentTimeMillis()
        export(fileName) {
            buildMd(loadBookmarks(bookName, bookAuthor), exportedAt)
        }
    }

    /**
     * 导出书签分享卡片 PNG: 位图 → PNG 编码 → 平台图片保存通道, 文件名对齐全本书签页
     * 导出惯例 (bookmark-书名-时间戳.png)。
     *
     * 渲染由调用方完成 (预览对话框经 GraphicsLayer 录制 [BookmarkShareCard], 预览与
     * 烘焙共用同一 Composable), 本入口只负责编码与落盘。不走 files.saveFile: Android 端
     * 它返回 content:// Uri (SAF) 而 common 层无字节写入通道 (BackupFileOps 仅有
     * writeText), 故用 saveImageBytes —— 四端已验证的图片字节保存通道
     * (对照图片查看器长按保存: true=写入成功 / false=写入失败 / null=用户取消)。
     *
     * @return 是否已保存 (用户取消返回 false 且静默, 不 toast)
     */
    suspend fun exportImage(bookmark: Bookmark, image: ImageBitmap): Boolean {
        return try {
            val fileName = sanitizeFileName(
                "bookmark-${bookmark.bookName}-${
                    ThreadSafeDateFormat("yyMMddHHmmss").format(systemCurrentTimeMillis())
                }.png"
            )
            val bytes = withContext(IoDispatcher) { image.encodePngBytes() }
            when (withContext(IoDispatcher) {
                PlatformServiceProviders.get().files.saveImageBytes(fileName, bytes)
            }) {
                true -> {
                    Toasters.get().toast("导出成功")
                    true
                }
                null -> false
                else -> {
                    Toasters.get().toast("导出失败\n写入失败")
                    AppLog.put("导出失败\n写入失败", null)
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toasters.get().toast("导出失败\n${e.message}")
            AppLog.put("导出失败\n${e.message}", e)
            false
        }
    }

    /** 文件选择器取目标路径 (用户取消返回 null 静默结束), 写入成功 toast, 失败 toast + 记 AppLog。 */
    private suspend fun export(fileName: String, content: suspend () -> String) {
        try {
            val files = PlatformServiceProviders.get().files
            val path = withContext(IoDispatcher) { files.saveFile(sanitizeFileName(fileName)) }
                ?: return
            withContext(IoDispatcher) {
                BackupFileOps.writeText(path, content())
            }
            Toasters.get().toast("导出成功")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toasters.get().toast("导出失败\n${e.message}")
            AppLog.put("导出失败\n${e.message}", e)
        }
    }

    /** 书签源: 按书过滤走 getByBook (SQL 章节序), 全量走 all + 拼音序 (对照 BookmarkRoute)。 */
    private suspend fun loadBookmarks(bookName: String?, bookAuthor: String?): List<Bookmark> {
        val dao = AppDbProviders.get().bookmarkDao
        return if (bookName != null) {
            dao.getByBook(bookName, bookAuthor.orEmpty())
        } else {
            dao.all().sortedByLocalizedOrder()
        }
    }

    /**
     * 组装 Markdown: 依赖输入已按书/章节排序 (SQL 序), 章节组与条目保持输入序,
     * 章节名取组内首条。
     *
     * 书名/章名/笔记先做行内转义: 原文里的 `#` 会让标题层级塌陷, 换行会把标题截断。
     */
    private fun buildMd(bookmarks: List<Bookmark>, exportedAt: Long): String {
        val sb = StringBuilder()
        val exportTime = dateFormat.format(exportedAt)
        bookmarks.groupBy { it.bookName to it.bookAuthor }.forEach { (book, bookBookmarks) ->
            sb.append("## ${escapeMdInline(book.first)} ${escapeMdInline(book.second)}\n\n")
            sb.append("导出时间：$exportTime · 共 ${bookBookmarks.size} 条书签\n\n")
            bookBookmarks.groupBy { it.chapterIndex }.forEach { (_, chapterBookmarks) ->
                sb.append("### ${escapeMdInline(chapterBookmarks.first().chapterName)}\n\n")
                chapterBookmarks.forEach { bookmark ->
                    if (bookmark.bookText.isNotBlank()) {
                        sb.append(bookmark.bookText.lines().joinToString("\n") { "> $it" })
                        sb.append("\n\n")
                    }
                    if (bookmark.content.isNotBlank()) {
                        sb.append("**笔记**：${escapeMdInline(bookmark.content)}\n\n")
                    }
                    sb.append("创建于 ${dateFormat.format(bookmark.time)}\n\n")
                }
            }
        }
        return sb.toString()
    }

    /**
     * 行内文本 Markdown 转义: 换行折成空格 (`#` + 换行会把标题截断); `#` 与行内强调/
     * 链接/代码/表格字符加反斜杠。只在行首有语义的字符 (如 `-`、`1.`) 不转义, 避免导出文本里满是反斜杠。
     */
    private fun escapeMdInline(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '\\', '`', '*', '_', '{', '}', '[', ']', '<', '>', '|', '#', '~' -> {
                    append('\\')
                    append(c)
                }

                '\n', '\r' -> append(' ')
                else -> append(c)
            }
        }
    }

    /**
     * 文件名消毒: 书名/作者可能含 `/ \ : * ? " < > |` 等各端文件系统非法字符,
     * 拼进导出文件名会让保存静默失败 (SAF/文件选择器直接拒绝)。
     */
    private fun sanitizeFileName(fileName: String): String =
        fileName.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
}
