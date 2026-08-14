package io.legado.app.service

import io.legado.app.constant.AppConst
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.mapAsync
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flow

/**
 * 导出书籍正文拼接纯逻辑下沉 (shared commonMain), 供全平台复用。
 *
 * 仅包含不依赖 epublib / OutputStream / Charset 的纯逻辑:
 * - [getAllContents]: 拼接全书正文文本 (供 TXT 导出复用)
 * - [getExportData]: 处理单章正文 (BookHelp.getContent + ContentProcessor)
 *
 * 平台依赖通过 [ExportBookContentDeps] 注入。依赖 epublib (EpubBook) 或
 * OutputStream (TXT 文件写入) 的逻辑见 jvmAndAndroidMain `ExportBookShared`。
 *
 * @see ExportBookContentDeps 平台依赖注入接口
 */
class ExportBookContentShared(private val deps: ExportBookContentDeps) {

    /**
     * 获取全书正文文本 (供 TXT 导出)。
     *
     * 1. 头部拼 "书名\n作者: xxx\n简介: xxx"
     * 2. 并发 (MAX_THREAD) 处理每章 [getExportData]
     * 3. 收集结果按顺序 [append]
     *
     * 章节列表来自 [AppDbProviders.get().bookChapterDao], 已缓存正文来自
     * [BookHelpProviders.get().getContent]。
     */
    suspend fun getAllContents(
        book: Book,
        append: (text: String) -> Unit
    ) = coroutineScope {
        val useReplace = deps.exportUseReplace && book.getUseReplaceRule()
        val qy = "${book.name}\n${
            deps.strAuthorShow(book.getRealAuthor())
        }\n${
            deps.strIntroShow(
                "\n" + HtmlFormatter.format(book.getDisplayIntro())
            )
        }"
        append(qy)
        flow {
            AppDbProviders.get().bookChapterDao.getChapterList(book.bookUrl).forEach { chapter ->
                emit(chapter)
            }
        }.mapAsync(AppConst.MAX_THREAD) { chapter ->
            getExportData(book, chapter, useReplace)
        }.collectIndexed { index, result ->
            deps.postExportEvent(book.bookUrl)
            deps.setExportProgress(book.bookUrl, index)
            append.invoke(result)
        }
    }

    /**
     * 处理单章正文 (BookHelp.getContent + ContentProcessor.getContent)。
     *
     * - 读取已缓存正文 (BookHelp.getContent), volume 取 "" 否则 "null"
     * - ContentProcessor 处理 (不导出 vip 标识, 是否含标题由
     *   [ExportBookContentDeps.exportNoChapterName] 决定, 不做简繁/重排)
     * - 返回 "\n\n" + 处理后正文
     *
     * @param useReplace 是否应用替换规则 (与 [Book.getUseReplaceRule] 取 AND 后传入)
     */
    private fun getExportData(
        book: Book,
        chapter: BookChapter,
        useReplace: Boolean
    ): String {
        val content = BookHelpProviders.get().getContent(book, chapter)
        val content1 = deps.processContent(
            book,
            // 不导出vip标识
            chapter.apply { isVip = false },
            content ?: if (chapter.isVolume) "" else "null",
            includeTitle = !deps.exportNoChapterName,
            useReplace = useReplace,
            chineseConvert = false,
            reSegment = false
        ).toString()
        return "\n\n$content1"
    }
}

/**
 * 导出书籍正文拼接平台依赖注入接口 (commonMain)。
 *
 * [ExportBookContentShared] 所需依赖子集, 各平台实现并注入。
 * jvmAndAndroidMain 的 `ExportBookDeps` 继承本接口, 复用同一份 deps 实现。
 */
interface ExportBookContentDeps {

    /** 是否启用替换规则 (对应 AppConfig.exportUseReplace)。 */
    val exportUseReplace: Boolean

    /** 是否不导出章节名 (对应 AppConfig.exportNoChapterName)。 */
    val exportNoChapterName: Boolean

    /** 格式化作者行 (对应 getString(R.string.author_show, author))。 */
    fun strAuthorShow(author: String): String

    /** 格式化简介行 (对应 getString(R.string.intro_show, intro))。 */
    fun strIntroShow(intro: String): String

    /**
     * 走完整正文处理 (替换规则 / 简繁 / 重排段 / 去重复标题),
     * 对应 `ContentProcessor.get(book).getContent(book, chapter, content, ...)`。
     */
    fun processContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean,
        useReplace: Boolean,
        chineseConvert: Boolean,
        reSegment: Boolean
    ): CharSequence

    /** 发布导出事件 (对应 postEvent(EventBus.EXPORT_BOOK, bookUrl))。 */
    fun postExportEvent(bookUrl: String)

    /** 设置导出进度 (对应 exportProgress[bookUrl] = progress)。 */
    fun setExportProgress(bookUrl: String, progress: Int)
}
