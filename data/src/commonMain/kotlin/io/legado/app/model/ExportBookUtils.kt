package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book

/**
 * 导出书籍纯逻辑工具(下沉自 app 端 `ExportBookService`)。
 *
 * 原 app 端以下三个方法为纯逻辑(无 Android 依赖),下沉到 commonMain 供多端复用:
 * - [parseScope]: 解析章节范围字符串(`"1-3,5,7-9"` → 0-based 索引集合),
 *   原 `ExportBookService.CustomExporter.parseScope`
 * - [paresNumOfEpub]: 计算分割 epub 后的文件数量, 原 `CustomExporter.paresNumOfEpub`
 * - [buildComicInfo]: 构建 cbz 元数据 `ComicInfo.xml`, 原 `ExportBookService.buildComicInfo`
 *
 * ExportBookService 改为调用本 object, 行为与下沉前完全一致。
 *
 * # 平台差异
 * 无。三个方法仅依赖 [Book] 实体(commonMain)和 [AppLog](commonMain)。
 *
 * @see <a href="https://anansi-project.github.io/docs/comicinfo/schema">ComicInfo schema</a>
 */
object ExportBookUtils {

    /**
     * 解析分割 epub 后的数量。
     *
     * @param total 章节总数
     * @param size 每个 epub 文件包含多少章节
     * @return 分割后的 epub 文件数(余数进位)
     */
    fun paresNumOfEpub(total: Int, size: Int): Int {
        val i = total % size
        var result = total / size
        if (i > 0) {
            result++
        }
        return result
    }

    /**
     * 解析范围字符串。
     *
     * 支持单值(`"5"`)和区间(`"1-3"`)混合, 逗号分隔。
     * 输入为 1-based 章节号, 输出为 0-based 索引集合(与 app 端 `CustomExporter` 原逻辑一致)。
     *
     * @param scope 范围字符串(如 `"1-3,5,7-9"`)
     * @return 0-based 索引集合(保持插入顺序, linkedSetOf)
     */
    fun parseScope(scope: String): Set<Int> {
        val split = scope.split(",")

        val result = linkedSetOf<Int>()
        for (s in split) {
            val v = s.split("-")
            if (v.size != 2) {
                result.add(s.toInt() - 1)
                continue
            }
            val left = v[0].toInt()
            val right = v[1].toInt()
            if (left > right) {
                AppLog.put("Error expression : $s; left > right")
                continue
            }
            for (i in left..right)
                result.add(i - 1)
        }
        return result
    }

    /**
     * 构建 `ComicInfo.xml`(cbz 元数据), 符合 ComicInfo schema 规范。
     *
     * @param book 书籍(取 name/author/intro/kind)
     * @param pageCount 总页数
     * @return ComicInfo.xml 字符串
     */
    fun buildComicInfo(book: Book, pageCount: Int): String {
        fun esc(s: String?): String = (s ?: "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            append("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n")
            append("  <Title>").append(esc(book.name)).append("</Title>\n")
            book.getRealAuthor().takeIf { it.isNotBlank() }?.let {
                append("  <Writer>").append(esc(it)).append("</Writer>\n")
            }
            book.getDisplayIntro().takeIf { !it.isNullOrBlank() }?.let {
                append("  <Summary>").append(esc(it)).append("</Summary>\n")
            }
            book.kind?.takeIf { it.isNotBlank() }?.let {
                append("  <Genre>").append(esc(it.replace(",", ", "))).append("</Genre>\n")
            }
            append("  <PageCount>").append(pageCount).append("</PageCount>\n")
            append("  <LanguageISO>zh</LanguageISO>\n")
            append("  <Manga>Yes</Manga>\n")
            append("</ComicInfo>\n")
        }
    }
}
