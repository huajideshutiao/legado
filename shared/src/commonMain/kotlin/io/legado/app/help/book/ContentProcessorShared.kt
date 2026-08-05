package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.spaceRegex
import io.legado.app.data.AppDbProviders
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.ReplaceRuleDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.utils.RegexReplacers
import io.legado.app.utils.escapeRegex
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * ContentProcessor 核心正文处理逻辑下沉 (commonMain)。
 *
 * 原 app 端 [io.legado.app.help.book.ContentProcessor] 的 `getContent` 主体逻辑
 * (替换规则 / 简繁转换 / 段落重排 / 去重复标题) 与 Android 无强耦合, 仅 6 处依赖
 * 需要平台桥接, 现全部通过 commonMain 已下沉的 provider 间接访问:
 *
 * - `appDb.replaceRuleDao` → [AppDbProviders.get].replaceRuleDao (已下沉)
 * - `appDb.bookDao.getBookByOrigin` → [AppDbProviders.get].bookDao (已下沉)
 * - `BookHelp.getChapterFiles` → [ContentProcessorDeps.getChapterFiles]
 *   (Android 走 BookHelp, 其他端走 [BookStorageProviders])
 * - `AppConfig.chineseConverterType` → [AppConfigProviders.get].chineseConverterType (已下沉)
 * - `ChineseUtils.t2s/s2t` → [chineseT2S]/[chineseS2T] expect/actual (已下沉)
 * - `mContent.replace(regex, replacement, timeout)` → [RegexReplacers.get].replace (已下沉)
 * - `appCtx.toastOnUi` / `isAndroid8` → [ContentProcessorDeps] (平台注入)
 *
 * 其余依赖 [AppLog] / [AppPattern.spaceRegex] / [ContentHelp.reSegment] /
 * [BookChapter.getFileName] / [BookChapter.getDisplayTitle] / [Book.getUseReplaceRule] /
 * [Book.config.reSegment] / [String.escapeRegex] / [RegexTimeoutException] /
 * [Throwable.stackTraceStr] 均已下沉 commonMain。
 *
 * # 缓存策略
 *
 * 不带 WeakReference 缓存 (WeakReference 是 JVM 专属, commonMain 不可用);
 * 由各平台 [ContentProcessorAccessor] 实现自行决定是否缓存 (Android 端 ContentProcessor
 * 仍保留 WeakReference 缓存层, 其他端可每次 new 或在 accessor 内做缓存)。
 *
 * 内部 `titleReplaceRules` / `contentReplaceRules` 用 `@Volatile var` + 不可变 List,
 * 替代原 app 端 `CopyOnWriteArrayList` (commonMain 无 CopyOnWriteArrayList);
 * 行为等价: 每次 [upReplaceRules] 整体替换 List, [getContent] 遍历时拿到当前 snapshot,
 * 不会被并发 [upReplaceRules] 干扰 (与 CopyOnWriteArrayList 迭代语义一致)。
 *
 * # 调用方
 *
 * - Android: [io.legado.app.help.book.ContentProcessor] 持有 ContentProcessorShared 实例,
 *   `getContent` / `getTitleReplaceRules` / `upReplaceRules` 委托本类
 *   (保留 WeakReference 缓存 + CopyOnWriteArrayList 兼容性, API 不变)
 * - Desktop / iOS / 鸿蒙: ContentProcessorAccessor 实现直接 new ContentProcessorShared
 *   使用, 完整逻辑 (含简繁 / 段落重排 / 去重标题), 替代原 desktop 简化实现
 */
class ContentProcessorShared(
    private val bookName: String,
    private val bookOrigin: String,
    private val deps: ContentProcessorDeps
) {

    /** 缓存的替换规则 DAO, 每次访问通过 [AppDbProviders] 取最新单例。 */
    private val replaceRuleDao: ReplaceRuleDao get() = AppDbProviders.get().replaceRuleDao

    /** 缓存的书籍 DAO, 每次访问通过 [AppDbProviders] 取最新单例。 */
    private val bookDao: BookDao get() = AppDbProviders.get().bookDao

    /**
     * 去重标题缓存 (对应 app 端 ContentProcessor.removeSameTitleCache)。
     *
     * 存放已禁用"去除重复标题"的章节文件名 (`*.nr`)。
     * [io.legado.app.help.book.BookHelp.setRemoveSameTitle] 直接 add/remove 此 Set。
     *
     * 用 MutableSet 而非 HashSet (commonMain 没有 HashSet 的 Concurrent 版本);
     * 调用方 (BookHelp.setRemoveSameTitle) 单线程操作, 无并发风险。
     */
    val removeSameTitleCache: MutableSet<String> = mutableSetOf()

    // @Volatile + 不可变 List 替代 CopyOnWriteArrayList (commonMain 无 COW)
    @Volatile
    private var titleReplaceRules: List<ReplaceRule> = emptyList()

    @Volatile
    private var contentReplaceRules: List<ReplaceRule> = emptyList()

    /**
     * 刷新替换规则缓存 (对应 app 端 ContentProcessor.upReplaceRules 实例方法)。
     *
     * suspend 因 ReplaceRuleDao.findEnabledByTitleScope/ContentScope 是 suspend
     * (Room KMP 强制); app 端原实现用 runBlocking 同步调用, 下沉后保持 suspend
     * 由调用方决定 (Android init 用 runBlocking, 其他端可走协程上下文)。
     */
    suspend fun upReplaceRules() {
        titleReplaceRules = replaceRuleDao.findEnabledByTitleScope(bookName, bookOrigin)
        contentReplaceRules = replaceRuleDao.findEnabledByContentScope(bookName, bookOrigin)
    }

    /**
     * 初始化去重标题缓存 (对应 app 端 ContentProcessor.upRemoveSameTitle)。
     *
     * suspend 因 BookDao.getBookByOrigin 是 suspend (Room KMP 强制)。
     */
    suspend fun upRemoveSameTitle() {
        val book = bookDao.getBookByOrigin(bookName, bookOrigin) ?: return
        removeSameTitleCache.clear()
        val files = deps.getChapterFiles(book).filter { it.endsWith("nr") }
        removeSameTitleCache.addAll(files)
    }

    fun getTitleReplaceRules(): List<ReplaceRule> = titleReplaceRules

    fun getContentReplaceRules(): List<ReplaceRule> = contentReplaceRules

    /**
     * 走完整正文处理 (替换规则 / 简繁 / 重排段 / 去重复标题), 返回 [BookContent]。
     *
     * 主体逻辑与 app 端 ContentProcessor.getContent 完全一致, 仅平台依赖替换为
     * commonMain provider / [ContentProcessorDeps] 间接访问 (详见类注释)。
     *
     * @param includeTitle 是否在结果头部附加章节显示标题
     * @param useReplace   是否应用替换规则 (与 [book.getUseReplaceRule] 取 AND)
     * @param chineseConvert 是否做简繁转换
     * @param reSegment    是否做段落重排 (与 [book.config.reSegment] 取 AND)
     */
    fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean = true,
        useReplace: Boolean = true,
        chineseConvert: Boolean = true,
        reSegment: Boolean = true
    ): BookContent {
        var mContent = content
        var sameTitleRemoved = false
        var effectiveReplaceRules: ArrayList<ReplaceRule>? = null
        if (content != "null") {
            // 去除重复标题
            val fileName = chapter.getFileName("nr")
            if (!removeSameTitleCache.contains(fileName)) try {
                // Pattern.quote(book.name) → escapeRegex (语义等价: 字面量匹配)
                val name = book.name.escapeRegex()
                var title = chapter.title.escapeRegex().replace(spaceRegex, "\\\\s*")
                // Pattern.compile(...).matcher(mContent).find() → Regex(...).find(mContent);
                // 前缀量词用占有 *+ : 标题内空格展开的 \s* 与前缀 (\s|\p{P}|name)* 重叠,
                // 正文开头大量空白/标点时 O(n²) 回溯 (举一反三: 对齐 CodeSyntax 修法)
                var regex = Regex("^(\\s|\\p{P}|${name})*+${title} *\\n?")
                var match = regex.find(mContent)
                if (match != null) {
                    // matcher.end() (exclusive) → match.range.last + 1
                    mContent = mContent.substring(match.range.last + 1)
                    sameTitleRemoved = true
                } else if (useReplace && book.getUseReplaceRule()) {
                    title = chapter.getDisplayTitle(
                        titleReplaceRules,
                        chineseConvert = false
                    ).escapeRegex()
                    regex = Regex("^(\\s|\\p{P}|${name})*+${title} *\\n?")
                    match = regex.find(mContent)
                    if (match != null) {
                        mContent = mContent.substring(match.range.last + 1)
                        sameTitleRemoved = true
                    }
                }
            } catch (e: Exception) {
                AppLog.put("去除重复标题出错\n${e.message}", e)
            }
            if (reSegment && book.config.reSegment) {
                // 段落重排
                mContent = ContentHelp.reSegment(mContent, chapter.title)
            }
            if (chineseConvert) {
                // 简繁转换
                try {
                    when (AppConfigProviders.get().chineseConverterType) {
                        1 -> mContent = chineseT2S(mContent)
                        2 -> mContent = chineseS2T(mContent)
                    }
                } catch (_: Exception) {
                    deps.toastOnUi("简繁转换出错")
                }
            }
            if (useReplace && book.getUseReplaceRule()) {
                // 替换
                effectiveReplaceRules = arrayListOf()
                getContentReplaceRules().forEach { item ->
                    if (item.pattern.isEmpty()) {
                        return@forEach
                    }
                    try {
                        val tmp = if (item.isRegex) {
                            // CharSequence.replace(regex, replacement, timeout) → RegexReplacers.get().replace
                            RegexReplacers.get().replace(
                                mContent,
                                item.regex,
                                item.replacement,
                                item.getValidTimeoutMillisecond()
                            )
                        } else {
                            mContent.replace(item.pattern, item.replacement)
                        }
                        if (mContent != tmp) {
                            effectiveReplaceRules.add(item)
                            mContent = tmp
                        }
                    } catch (e: RegexTimeoutException) {
                        item.isEnabled = false
                        // runBlocking { appDb.replaceRuleDao.update(item) } → AppDbProviders.get().replaceRuleDao
                        // fire-and-forget: 错误恢复路径 (禁用超时规则), 不阻塞当前线程 (避免 Native 端死锁)
                        // 写库失败会让坏规则每章反复超时, invokeOnCompletion 记日志暴露
                        GlobalScope.launch { replaceRuleDao.update(item) }.invokeOnCompletion { cause ->
                            if (cause != null && cause !is CancellationException) {
                                AppLog.put("禁用超时替换规则失败: ${item.name}\n${cause.message}", cause)
                            }
                        }
                        mContent = item.name + e.stackTraceStr
                    } catch (_: CancellationException) {
                    } catch (e: Exception) {
                        AppLog.put("替换净化: 规则 ${item.name}替换出错.\n${mContent}", e)
                        deps.toastOnUi("替换净化: 规则 ${item.name}替换出错")
                    }
                }
            }
        }
        val contentList = mutableListOf<String>()
        if (includeTitle) {
            // 重新添加标题
            contentList.add(chapter.getDisplayTitle(
                getTitleReplaceRules(),
                useReplace = useReplace && book.getUseReplaceRule()
            ))
        }
        if (deps.isAndroid8) {
            mContent = mContent.replace('\u00A0', ' ')
        }
        contentList.add(mContent)
        return BookContent(sameTitleRemoved, contentList, effectiveReplaceRules)
    }
}
