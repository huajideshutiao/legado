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
import io.legado.app.help.coroutine.runBlockingInScope
import io.legado.app.utils.RegexReplacers
import io.legado.app.utils.escapeRegex
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext

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
 * [Book.config.reSegment] / [String.escapeRegex] / [RegexTimeoutException] 均已下沉 commonMain。
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

    init {
        // 注册到 BookHelpShared 的注册表, 让 setRemoveSameTitleMarker 翻转标记时能同步本实例缓存;
        // 判据 getContent 读的是本 Set, 不注册则各端实例永不感知新状态 ("去重"菜单点了不生效)
        BookHelpShared.registerRemoveSameTitleCache(bookName, bookOrigin, removeSameTitleCache)
    }

    // @Volatile + 不可变 List 替代 CopyOnWriteArrayList (commonMain 无 COW)
    @Volatile
    private var titleReplaceRules: List<ReplaceRule> = emptyList()

    @Volatile
    private var contentReplaceRules: List<ReplaceRule> = emptyList()

    /**
     * 本会话已超时熔断的规则 id。超时只写库、不刷内存快照，不记这一笔的话同一条坏规则每章再烧一次。
     * 与规则列表同套语义：@Volatile + 不可变集合整体替换。
     */
    @Volatile
    private var timedOutRuleIds: Set<Long> = emptySet()

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
        // 规则以库为准重新装载, 熔断记录随之作废 (用户手动重新启用的规则要能再试一次)
        timedOutRuleIds = emptySet()
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

    /**
     * 设置替换规则（供测试或外部直接注入替换规则使用）。
     */
    internal fun setReplaceRules(
        titleRules: List<ReplaceRule> = emptyList(),
        contentRules: List<ReplaceRule> = emptyList()
    ) {
        titleReplaceRules = titleRules
        contentReplaceRules = contentRules
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
            // 去除重复标题 (P1-3 防回溯截断优化: 仅对正文头部子串做正则匹配与截断)
            val fileName = chapter.getFileName("nr")
            if (!removeSameTitleCache.contains(fileName) && chapter.title.isNotBlank()) try {
                // 安全截断：去重标题仅在正文最头部生效，只对前部子串做正则匹配，避免对整章数万字执行深回溯。
                // 窗口必须覆盖前缀 (\s|\p{P}|书名)* 实际能吃掉的长度，否则正文开头有大段空行或
                // "————————" 时原本能去掉的重复标题会去不掉；这里用「非字母数字」取 \s|\p{P} 的超集，
                // 只会放大窗口不会缩小匹配集合
                var prefixEnd = 0
                while (prefixEnd < mContent.length) {
                    val ch = mContent[prefixEnd]
                    if (!ch.isLetterOrDigit()) {
                        prefixEnd++
                    } else if (book.name.isNotEmpty() && mContent.startsWith(book.name, prefixEnd)) {
                        prefixEnd += book.name.length
                    } else {
                        break
                    }
                }
                val maxHeadLength = (prefixEnd + (chapter.title.length + book.name.length) * 2 + 128)
                    .coerceAtMost(mContent.length)
                val headContent = if (mContent.length > maxHeadLength) mContent.substring(0, maxHeadLength) else mContent

                // Pattern.quote(book.name) → escapeRegex (非空时才入分支，避免空分支造成回溯/死循环)
                val namePattern = if (book.name.isNotEmpty()) "|${book.name.escapeRegex()}" else ""
                var title = chapter.title.escapeRegex().replace(spaceRegex, "\\\\s*")
                // Pattern.compile(...).matcher(mContent).find() → Regex(...).find(mContent);
                // 前缀用贪婪 * (对齐原版): 标题以书名/标点开头(如"斗破苍穹 第一章"、"【第1章】")需回溯匹配;
                // 尾部仅匹配本行水平空白及单次换行 ([ \\t\\u3000\\u00A0]*\\r?\\n?), 严禁使用 (\\s|\\p{P})*
                // 避免越过换行误吞后续正文段落开头的首字引号/标点符号 (如“《【) 或空行
                var regex = Regex("^(\\s|\\p{P}$namePattern)*${title}[ \\t\\u3000\\u00A0]*\\r?\\n?")
                var match = regex.find(headContent)
                if (match != null) {
                    // matcher.end() (exclusive) → match.range.last + 1
                    mContent = mContent.substring(match.range.last + 1)
                    sameTitleRemoved = true
                } else if (useReplace && book.getUseReplaceRule()) {
                    val displayTitle = chapter.getDisplayTitle(
                        titleReplaceRules,
                        chineseConvert = false
                    )
                    if (displayTitle.isNotBlank()) {
                        // 对照原版: 第二分支只做 Pattern.quote, 不把空白放宽成 \s*
                        title = displayTitle.escapeRegex()
                        regex = Regex("^(\\s|\\p{P}$namePattern)*${title}[ \\t\\u3000\\u00A0]*\\r?\\n?")
                        match = regex.find(headContent)
                        if (match != null) {
                            mContent = mContent.substring(match.range.last + 1)
                            sameTitleRemoved = true
                        }
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
                // 替换 (正则规则前置关键字探测, 未命中直接跳过)
                effectiveReplaceRules = arrayListOf()
                getContentReplaceRules().forEach { item ->
                    if (item.pattern.isEmpty()) {
                        return@forEach
                    }

                    // 本会话已超时熔断的规则不再重试 (内存快照要等 upReplaceRules 才刷新)
                    if (item.id in timedOutRuleIds) {
                        return@forEach
                    }

                    // 前置短路探测: 只对纯字面量正则生效, 省下病态正则的超时熔断
                    if (FastReplaceRuleMatcher.shouldSkip(mContent, item)) {
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
                        // 库里禁用之外再记一笔内存熔断: 本会话内这条规则不再进正则引擎
                        timedOutRuleIds = timedOutRuleIds + item.id
                        // 对照原版: `appDb.replaceRuleDao.update(item)` 同步写库 (Room KMP DAO suspend,
                        // 经 runBlockingInScope 桥接; 项目内同步 DB 写先例: CacheManager.put/delete、
                        // SharedCookieStore.onInsertCookieToDb、ReadBookShared.saveReadProgress 同模式)。
                        // 同步保证坏规则立即落库禁用, 不会出现“写库失败让坏规则每章反复超时”。
                        runBlockingInScope(EmptyCoroutineContext) { replaceRuleDao.update(item) }
                        // 超时只禁用规则并提示, 保留原文; 与标题净化路径的同名 catch 一致
                        AppLog.put("替换净化: 规则 ${item.name} 替换超时, 已禁用", e)
                        deps.toastOnUi("替换净化: 规则 ${item.name} 替换超时, 已禁用")
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

/**
 * 替换规则快速短路匹配器。
 *
 * 只认「pattern 不含任何正则元字符」这一种一眼可判的情形：此时 pattern 自身就是必现字面量，
 * indexOf 未命中即可跳过，省下病态正则在无关章节上撞 3s 超时熔断的代价。
 * 含元字符一律不短路；非正则规则也不探测 —— `String.replace(oldValue, newValue)`
 * 内部先 indexOf，未命中直接返回原串且不分配。
 */
internal object FastReplaceRuleMatcher {

    /**
     * 判断指定替换规则是否可安全短路跳过。
     */
    fun shouldSkip(content: CharSequence, rule: ReplaceRule): Boolean {
        val pattern = rule.pattern
        if (pattern.isEmpty()) return true
        if (!rule.isRegex) return false
        val keyword = extractFastKeyword(pattern) ?: return false
        return !content.contains(keyword)
    }

    /**
     * 不含元字符（含内联修饰符所需的括号）的 pattern 就是必现字面量，其余一律返回 null 让正则照跑。
     */
    fun extractFastKeyword(pattern: String): String? {
        if (pattern.isEmpty()) return null
        for (c in pattern) {
            when (c) {
                '\\', '[', ']', '(', ')', '{', '}', '|', '?', '*', '+', '.', '^', '$' -> return null
            }
        }
        return pattern
    }
}
