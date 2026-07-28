package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import kotlin.concurrent.Volatile

/**
 * ContentProcessor 跨模块只读访问接口。
 *
 * ContentProcessor 依赖 android.os.Build / appCtx.toastOnUi / appDb / BookHelp 等
 * Android-only 资源, 留 app 端。本接口仅暴露 webBook 编排层 (BookChapterList)
 * 用到的 getTitleReplaceRules(book), 由 app 端 ContentProcessorAccessorImpl 包装
 * ContentProcessor 实现, 在 App.onCreate 经 [ContentProcessorProviders.register] 注册。
 *
 * 模式参考 BookInfoRefreshers / SourceDebugLoggers。
 *
 * 下沉 SearchContentViewModelShared 新增 [getContent] 方法: 走完整正文处理
 * (替换规则/简繁/重排段/去重复标题), 返回 CharSequence (BookContent.toString 拼接文本)。
 */
interface ContentProcessorAccessor {
    /** 返回书籍的标题替换规则列表 (对应 ContentProcessor.get(book).getTitleReplaceRules())。 */
    fun getTitleReplaceRules(book: Book): List<ReplaceRule>

    /**
     * 走完整正文处理 (替换规则/简繁/重排段/去重复标题), 返回拼接后的正文文本。
     *
     * 对应 `ContentProcessor.get(book).getContent(book, chapter, content, useReplace = useReplace).toString()`。
     *
     * @param book 书籍 (用于取 name/origin 定位 ContentProcessor 缓存)
     * @param chapter 章节 (用于取 title 和 fileName)
     * @param content 原始正文 (BookHelp.getContent 返回)
     * @param useReplace 是否应用替换规则 (SearchContentViewModel 用 replaceEnabled)
     * @return 处理后的正文文本 (BookContent.textList joinToString "\n")
     */
    fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        useReplace: Boolean = true
    ): CharSequence

    /**
     * 走完整正文处理并显式指定是否包含章节标题。
     *
     * 对应 `ContentProcessor.get(book).getContent(book, chapter, content, includeTitle = includeTitle, useReplace = useReplace).toString()`。
     *
     * BookController.getBookContent web API 下沉新增: 原 app 端调用
     * `ContentProcessor.get(name, origin).getContent(book, chapter, content, includeTitle = false)`,
     * 既有 [getContent] 重载未暴露 includeTitle (默认 true), 无法等价表达 "正文不含标题" 语义,
     * 故新增本重载。default 实现退化为 [getContent] (includeTitle 取默认 true 行为),
     * 由各平台 actual 实现覆写以真正传递 includeTitle 给 ContentProcessorShared/ContentProcessor。
     *
     * @param book 书籍 (用于取 name/origin 定位 ContentProcessor 缓存)
     * @param chapter 章节 (用于取 title 和 fileName)
     * @param content 原始正文 (BookHelp.getContent / WebBook.getContentAwait 返回)
     * @param includeTitle 是否在正文前拼接章节标题 (BookController web API 传 false)
     * @param useReplace 是否应用替换规则
     * @return 处理后的正文文本 (BookContent.textList joinToString "\n")
     */
    fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean,
        useReplace: Boolean
    ): CharSequence = getContent(book, chapter, content, useReplace = useReplace)

    /**
     * 返回完整正文处理结果，保留 [BookContent.textList]、去重标题标记和实际生效规则。
     *
     * 阅读排版不能先把 [BookContent] 压平成字符串再 `split/trim/filter`：原版
     * `TextChapterLayout` 逐项消费 `textList`，并在每项内部按换行遍历；提前压平会丢失
     * 空段、首尾空白以及 HTML 图片标签的解析边界。默认实现仅用于兼容第三方实现，
     * 正式平台实现必须覆写并直接返回 ContentProcessor 的原始 [BookContent]。
     */
    fun getBookContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean = true,
        useReplace: Boolean = true,
        chineseConvert: Boolean = true,
        reSegment: Boolean = true,
    ): BookContent = BookContent(
        sameTitleRemoved = false,
        textList = listOf(
            getContent(
                book = book,
                chapter = chapter,
                content = content,
                includeTitle = includeTitle,
                useReplace = useReplace,
            ).toString()
        ),
        effectiveReplaceRules = null,
    )

    /**
     * 刷新所有已缓存 ContentProcessor 实例的替换规则 (对应 `ContentProcessor.upReplaceRules()`)。
     *
     * 书源导入完成后调用, 让后续正文加载使用最新的 replaceRuleDao 数据。
     * 由 ImportBookSourceViewModelShared.importSelect 内部调用 (替代原 app 端
     * `ContentProcessor.upReplaceRules()` 直接调用), 行为等价。
     */
    fun upReplaceRules()
}

/**
 * ContentProcessor provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用
 * `ContentProcessorProviders.get().getTitleReplaceRules(book)` 替代
 * 原 `ContentProcessor.get(book).getTitleReplaceRules()`, 行为完全一致,
 * 仅多一层 provider 间接。
 */
object ContentProcessorProviders {
    @Volatile
    private var impl: ContentProcessorAccessor? = null

    /** 宿主启动早期注册一次(任何 webBook 调用之前)。 */
    fun register(impl: ContentProcessorAccessor) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): ContentProcessorAccessor =
        impl ?: error("ContentProcessorProviders not registered")
}
