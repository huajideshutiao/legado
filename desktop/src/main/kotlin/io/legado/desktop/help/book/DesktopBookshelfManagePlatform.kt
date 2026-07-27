package io.legado.desktop.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.ui.book.manage.BookshelfManagePlatform
import io.legado.app.utils.EscapeUtils
import io.legado.app.utils.StringUtils
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 桌面端 [BookshelfManagePlatform] 实现。
 *
 * 对照 app 端 `AndroidBookshelfManagePlatform` (内类于 BookshelfManageViewModel):
 * - **migrateBook**: app 端委托 `Book.migrateTo(newBook, toc)` (内部走 BookHelp.getDurChapter +
 *   ContentProcessor.getTitleReplaceRules + getUseReplaceRule)。
 *   桌面端 [migrateBook] 内联 BookHelp.getDurChapter 的章节名相似度匹配算法
 *   (EscapeUtils.jaccardSimilarity + StringUtils.fullToHalf/stringToInt + Pattern, 均已下沉
 *   commonMain 或 JVM 可用), 不走 ContentProcessor 标题替换规则 (ContentProcessor 未下沉,
 *   属平台差异), 直接取 toc[newIndex].title 作为新章节标题。
 * - **clearCache**: 委托 [BookStorageProviders.get].clearCache(book) (JvmBookStorage 实现)。
 * - **getChapterFiles**: 委托 [BookStorageProviders.get].getChapterFiles(book).toHashSet()。
 * - **deleteLocalBook**: 委托 [LocalBookLocators.get].deleteBook(book) (JvmLocalBookLocator 实现,
 *   desktop Main.kt 注册)。deleteOriginal 参数在桌面端忽略 (本地书直接删除源文件,
 *   与 app 端 FileBook.deleteBook(book, deleteOriginal) 行为对齐: deleteOriginal=true 删源文件,
 *   deleteOriginal=false 仅删数据库记录由调用方处理)。
 * - **clearCacheSuccessMessage**: 硬编码 "清缓存成功" (桌面端无 R.string 资源系统)。
 *
 * 使用: 由桌面端 BookshelfManageScreen / ChangeSourceScreen 等调用方直接实例化,
 * 注入 [io.legado.app.ui.book.manage.BookshelfManageViewModelShared] 构造函数或直接调
 * platform 方法 (如 clearCache)。模式参考 [DesktopBookHelpAccessor] / [DesktopSourceHelpAccessor]
 * (那两个是 Provider 注册模式, 本 platform 不需要全局唯一, 调用方按需持有)。
 */
class DesktopBookshelfManagePlatform : BookshelfManagePlatform {

    // ---- 章节名相似度匹配辅助 (复刻 app 端 BookHelp 私有方法) ----

    /** 章节序号匹配模式 1: "第X章/节/篇/回/集/话"。 */
    private val chapterNamePattern1: Pattern by lazy {
        Pattern.compile(
            ".*?第([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话]"
        )
    }

    /** 章节序号匹配模式 2: 行首数字 + 分隔符。 */
    @Suppress("RegExpSimplifiable")
    private val chapterNamePattern2: Pattern by lazy {
        Pattern.compile(
            "^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、]|\\.[^\\d])"
        )
    }

    private val regexA by lazy { "\\s".toRegex() }

    /** 非字母数字中日韩文字 (CJK 区+扩展 A-F 区)。 */
    @Suppress("RegExpDuplicateCharacterInClass")
    private val regexOther by lazy {
        "[^\\w\\u4E00-\\u9FEF〇\\u3400-\\u4DBF\\u20000-\\u2A6DF\\u2A700-\\u2EBEF]".toRegex()
    }

    /** 章节序号去除模式 (排除处于结尾的状况, 避免将章节名替换为空字串)。 */
    @Suppress("RegExpUnnecessaryNonCapturingGroup", "RegExpSimplifiable")
    private val regexB by lazy {
        "^.*?第(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话](?!$)|^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、](?!$)|\\.(?=[^\\d]))".toRegex()
    }

    /** 前后附加内容 (括号等) 去除模式。 */
    private val regexC by lazy {
        "(?!^)(?:[〖【《〔\\[{(][^〖【《〔\\[{()〕》】〗\\]}]+)?[)〕》】〗\\]}]$|^[〖【《〔\\[{(](?:[^〖【《〔\\[{()〕》】〗\\]}]+[〕》】〗\\]})])?(?!$)".toRegex()
    }

    /**
     * 迁移旧书信息到新书 (对照 `Book.migrateTo(newBook, toc)`)。
     *
     * 1. 用 [getDurChapter] 章节名相似度匹配定位新书当前章节索引
     *    (复刻 app 端 BookHelp.getDurChapter 算法, 桌面端 JVM Pattern + commonMain 工具类);
     * 2. 取 toc[newIndex].title 作为新 durChapterTitle (不走 ContentProcessor 标题替换规则,
     *    ContentProcessor 未下沉, 属平台差异);
     * 3. 复制 durChapterPos / durChapterTime / group / order / customCoverUrl / customIntro /
     *    customTag / canUpdate / readConfig 等字段 (与 app 端 Book.migrateTo 一致)。
     */
    override fun migrateBook(oldBook: Book, newBook: Book, toc: List<BookChapter>): Book {
        val newIndex = getDurChapter(
            oldDurChapterIndex = oldBook.durChapterIndex,
            oldDurChapterName = oldBook.durChapterTitle,
            newChapterList = toc,
            oldChapterListSize = oldBook.totalChapterNum,
        )
        newBook.durChapterIndex = newIndex
        // 桌面端简化: 不走 ContentProcessor.getTitleReplaceRules + getUseReplaceRule
        // (ContentProcessor 未下沉), 直接取 toc[newIndex].title;
        // app 端用 toc[newIndex].getDisplayTitle(titleReplaceRules, useReplaceRules),
        // 此处差异不影响阅读功能 (仅章节标题展示, 不影响正文)
        newBook.durChapterTitle = toc.getOrNull(newIndex)?.title ?: oldBook.durChapterTitle
        newBook.durChapterPos = oldBook.durChapterPos
        newBook.durChapterTime = oldBook.durChapterTime
        newBook.group = oldBook.group
        newBook.order = oldBook.order
        newBook.customCoverUrl = oldBook.customCoverUrl
        newBook.customIntro = oldBook.customIntro
        newBook.customTag = oldBook.customTag
        newBook.canUpdate = oldBook.canUpdate
        newBook.readConfig = oldBook.readConfig
        return newBook
    }

    /** 清除书籍章节缓存: 委托 [BookStorageProviders.get].clearCache(book) (JvmBookStorage)。 */
    override fun clearCache(book: Book) {
        BookStorageProviders.get().clearCache(book)
    }

    /** 列出书籍已缓存章节文件名集合: 委托 [BookStorageProviders.get].getChapterFiles(book)。 */
    override fun getChapterFiles(book: Book): HashSet<String> {
        return BookStorageProviders.get().getChapterFiles(book).toHashSet()
    }

    /**
     * 删除本地书源文件: 委托 [LocalBookLocators.get].deleteBook(book) (JvmLocalBookLocator)。
     *
     * deleteOriginal 参数在桌面端忽略 (本地书直接删除源文件, 与 app 端 FileBook.deleteBook(book, true)
     * 行为一致; deleteOriginal=false 场景仅删数据库记录, 由调用方 BookshelfManageViewModelShared.deleteBook
     * 在 dao.delete 后判断 isLocal 决定是否调本方法, 故本方法被调用即意味着 deleteOriginal=true)。
     */
    override fun deleteLocalBook(book: Book, deleteOriginal: Boolean) {
        if (deleteOriginal) {
            LocalBookLocators.get().deleteBook(book)
        }
    }

    /** 清缓存成功提示文案 (硬编码, 与 app 端 R.string.clear_cache_success 对应)。 */
    override val clearCacheSuccessMessage: String = "清缓存成功"

    // ---- 章节名相似度匹配算法 (复刻 app 端 BookHelp.getDurChapter + 辅助方法) ----

    /**
     * 根据旧章节索引/名 + 新章节列表定位新章节索引 (复刻 `BookHelp.getDurChapter`)。
     *
     * 算法:
     * 1. 旧索引 <= 0 直接返回 0;
     * 2. 新列表为空返回旧索引;
     * 3. 估算位置 durIndex = oldIndex * oldSize / newSize (按比例换算);
     * 4. 在 [min, max] = [min(旧, 估)-10, max(旧, 估)+10] 范围内找:
     *    - 章节名 Jaccard 相似度最高的索引;
     *    - 若相似度 < 0.96 且旧章节号 > 0, 用章节号匹配;
     * 5. 相似度 > 0.96 或章节号差 < 1 返回 newIndex, 否则返回 min(末尾, 旧索引)。
     */
    private fun getDurChapter(
        oldDurChapterIndex: Int,
        oldDurChapterName: String?,
        newChapterList: List<BookChapter>,
        oldChapterListSize: Int = 0,
    ): Int {
        if (oldDurChapterIndex <= 0) return 0
        if (newChapterList.isEmpty()) return oldDurChapterIndex
        val oldChapterNum = getChapterNum(oldDurChapterName)
        val oldName = getPureChapterName(oldDurChapterName)
        val newChapterSize = newChapterList.size
        val durIndex =
            if (oldChapterListSize == 0) oldDurChapterIndex
            else oldDurChapterIndex * oldChapterListSize / newChapterSize
        val rangeMin = max(0, min(oldDurChapterIndex, durIndex) - 10)
        val rangeMax = min(newChapterSize - 1, max(oldDurChapterIndex, durIndex) + 10)
        var nameSim = 0.0
        var newIndex = 0
        var newNum = 0
        if (oldName.isNotEmpty()) {
            for (i in rangeMin..rangeMax) {
                val newName = getPureChapterName(newChapterList[i].title)
                val temp = EscapeUtils.jaccardSimilarity(oldName, newName)
                if (temp > nameSim) {
                    nameSim = temp
                    newIndex = i
                }
            }
        }
        if (nameSim < 0.96 && oldChapterNum > 0) {
            for (i in rangeMin..rangeMax) {
                val temp = getChapterNum(newChapterList[i].title)
                if (temp == oldChapterNum) {
                    newNum = temp
                    newIndex = i
                    break
                } else if (abs(temp - oldChapterNum) < abs(newNum - oldChapterNum)) {
                    newNum = temp
                    newIndex = i
                }
            }
        }
        return if (nameSim > 0.96 || abs(newNum - oldChapterNum) < 1) {
            newIndex
        } else {
            min(max(0, newChapterList.size - 1), oldDurChapterIndex)
        }
    }

    /** 章节号提取 (复刻 `BookHelp.getChapterNum`)。 */
    private fun getChapterNum(chapterName: String?): Int {
        chapterName ?: return -1
        val chapterName1 = StringUtils.fullToHalf(chapterName).replace(regexA, "")
        return StringUtils.stringToInt(
            (
                chapterNamePattern1.matcher(chapterName1).takeIf { it.find() }
                    ?: chapterNamePattern2.matcher(chapterName1).takeIf { it.find() }
                )?.group(1)
                ?: "-1"
        )
    }

    /** 章节名纯化 (复刻 `BookHelp.getPureChapterName`)。 */
    private fun getPureChapterName(chapterName: String?): String {
        return if (chapterName == null) "" else StringUtils.fullToHalf(chapterName)
            .replace(regexA, "")
            .replace(regexB, "")
            .replace(regexC, "")
            .replace(regexOther, "")
    }
}
