package io.legado.app.ui.book.read

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.KeywordHighlight
import io.legado.app.help.HighlightAnchor
import io.legado.app.help.KeywordMatcher
import io.legado.app.model.ReadBookShared
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import io.legado.app.ui.book.read.page.overlay.HighlightOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * 阅读页章内静态高亮状态源 (划线回显 + 关键词命中, 共用一套 HighlightOverlay 输出)。
 *
 * 刷新时机 = 三章滑窗内任一章节 (重) 排版或高亮数据变更:
 * - 章节实例变换来自 [ReadBookShared] 的 prev/cur/next TextChapterShared StateFlow
 *   (StateFlow 按实例去重): 章节切换 / 视口重排 / 替换规则刷新 / 段评迟到重排均会换实例;
 * - 段评迟到重排走章内就地补丁时不换实例，靠 [contentVersion] 变更唤醒重算；
 * - 书籍切换后重订阅书签 (type=1 划线) 与关键词规则 (启用项) 的 DAO flow;
 * - 每章重算 = 章文本从页文本账本重建 (净化/替换/简繁后的排版输入文本) → 划线重锚 +
 *   关键词匹配, 即"每章匹配一次, 随排版缓存生命周期"。
 *
 * 重算按章缓存: 章实例引用 + 本章文本长度 + 规则表 + 本章划线全等则直接复用上次结果，
 * 三窗中只有变化的章重算。
 */
class ChapterHighlightState(
    private val scope: CoroutineScope,
    private val readBook: ReadBookShared,
    /**
     * 页内容原地变更版本号（段评就地补丁等）: 仅作重算触发源。章实例引用不变时
     * 该流是唯一唤醒信号，具体哪几章要算由每章文本长度判。
     */
    private val contentVersion: StateFlow<Int>,
) {

    private val _overlays = MutableStateFlow<List<HighlightOverlay>>(emptyList())

    /** 滑窗内各章的章内区间高亮, 绘制侧按页 chapterIndex 求交投影 */
    val overlays: StateFlow<List<HighlightOverlay>> = _overlays.asStateFlow()

    /**
     * 单章重算缓存：章实例引用 + 本章文本长度 + 输入（规则表 / 本章划线）全等才复用。
     * 字体/视口变化与章内容重排都会换新章实例；段评就地补丁只插入字符（不换实例），
     * 靠文本长度变化失效；关键词规则与划线变更走输入相等性。
     */
    private class ChapterCache(
        val chapter: TextChapterShared,
        val textLength: Int,
        val rules: List<KeywordMatcher.Rule>,
        val underlines: List<Bookmark>,
        val overlays: List<HighlightOverlay>,
    )

    /**
     * 仅 [observe] 的单收集器协程访问，无跨线程共享。
     * 仍以不可变快照引用发布：collectLatest 切书时旧体可能仍在非挂起点（重算）中，
     * 原地改 Map 会造成并发读写，故每次重建新 Map 后整体替换。
     */
    @Volatile
    private var cache: Map<Int, ChapterCache> = emptyMap()

    /**
     * 当前书的划线实体列表 (type=1): 点击命中经 overlay.underlineId (= Bookmark.time)
     * 在此同步反查实体, 供气泡展示批注与编辑/换色/删除。
     */
    private val _underlineBookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val underlineBookmarks: StateFlow<List<Bookmark>> = _underlineBookmarks.asStateFlow()

    fun start() {
        scope.launch { observe() }
    }

    private suspend fun observe() {
        readBook.book.collectLatest { book ->
            if (book == null) {
                cache = emptyMap()
                _overlays.value = emptyList()
                return@collectLatest
            }
            cache = emptyMap()
            val appDb = AppDbProviders.get()
            combine(
                appDb.bookmarkDao.flowUnderlinesByBook(book.name, book.author),
                appDb.keywordHighlightDao.flowEnabled(),
                combine(
                    readBook.prevTextChapter,
                    readBook.curTextChapter,
                    readBook.nextTextChapter,
                ) { prev, cur, next -> listOf<TextChapterShared?>(prev, cur, next) },
                contentVersion,
            ) { underlines, keywords, chapters, _ ->
                _underlineBookmarks.value = underlines
                // 关键词先按 scope 过滤 (语义对齐替换规则), 再进 KeywordMatcher;
                // book 从本层已 combine 的书籍流取, 无需额外订阅。
                // version 只作触发源: 段评就地补丁不换章实例, 靠它唤醒重算;
                // 真正失效判据是每章文本长度, 故只有内容变了的章重算。
                buildOverlays(
                    underlines,
                    keywords.filter { it.appliesToBook(book.name, book.origin) },
                    chapters,
                )
            }.collect { _overlays.value = it }
        }
    }

    private fun buildOverlays(
        underlines: List<Bookmark>,
        keywords: List<KeywordHighlight>,
        chapters: List<TextChapterShared?>,
    ): List<HighlightOverlay> {
        if (underlines.isEmpty() && keywords.isEmpty()) {
            cache = emptyMap()
            return emptyList()
        }
        val rules = keywords.map { KeywordMatcher.Rule(it.id, it.word, it.colorIndex, it.underline) }
        val previous = cache
        val next = HashMap<Int, ChapterCache>()
        val out = ArrayList<HighlightOverlay>()
        for (chapter in chapters) {
            if (chapter == null) continue
            val chapterIndex = chapter.chapterIndex
            val textLength = chapter.pages.sumOf { it.text.length }
            val chapterUnderlines = underlines.filter { it.chapterIndex == chapterIndex }
            val cached = previous[chapterIndex]
            if (cached != null &&
                cached.chapter === chapter &&
                cached.textLength == textLength &&
                cached.rules == rules &&
                cached.underlines == chapterUnderlines
            ) {
                out.addAll(cached.overlays)
                next[chapterIndex] = cached
                continue
            }
            val overlays = buildChapterOverlays(chapter, chapterUnderlines, rules)
            next[chapterIndex] =
                ChapterCache(chapter, textLength, rules, chapterUnderlines, overlays)
            out.addAll(overlays)
        }
        cache = next
        return out
    }

    private fun buildChapterOverlays(
        chapter: TextChapterShared,
        underlines: List<Bookmark>,
        rules: List<KeywordMatcher.Rule>,
    ): List<HighlightOverlay> {
        // 占位章（加载中/错误提示）的页内偏移与章内账本不同源, 不产出 overlay
        if (chapter.pages.firstOrNull()?.isMsgPage == true) return emptyList()
        val chapterIndex = chapter.chapterIndex
        val out = ArrayList<HighlightOverlay>()
        // 章文本与 TextLine.chapterPosition 账本同源: 页文本顺序拼接 (含段尾 "\n" 与标题段)
        val text = chapter.pages.joinToString("") { it.text }
        for (bookmark in underlines) {
            val anchor = HighlightAnchor.reanchor(
                text = text,
                start = bookmark.chapterPos,
                endExclusive = bookmark.endPos,
                bookText = bookmark.bookText,
            ) ?: continue
            out.add(
                HighlightOverlay(
                    chapterIndex = chapterIndex,
                    start = anchor.start,
                    endExclusive = anchor.endExclusive,
                    colorIndex = bookmark.colorIndex,
                    underlineId = bookmark.time,
                )
            )
        }
        for (match in KeywordMatcher.match(text, rules)) {
            out.add(
                HighlightOverlay(
                    chapterIndex = chapterIndex,
                    start = match.start,
                    endExclusive = match.endExclusive,
                    colorIndex = match.colorIndex,
                    underline = match.underline,
                )
            )
        }
        return out
    }
}

/**
 * scope 作用范围判定, 逐条对齐替换规则 DAO 的 SQL 过滤口径
 * (data/dao/ReplaceRuleDao.findEnabledByContentScope 的 WHERE 段):
 *
 * `scope LIKE '%' || :name || '%' or scope LIKE '%' || :origin || '%'
 *   or scope is null or scope = ''`
 * `and (excludeScope is null or (excludeScope not LIKE '%' || :name || '%'
 *   and excludeScope not LIKE '%' || :origin || '%'))`
 *
 * - scope null/空串 → 全局生效;
 * - 非空 → scope 整串包含书名或书源 origin 子串即生效 (不切分分隔符、不 trim);
 * - excludeScope null → 不排除; 非空 → 包含书名或书源 origin 子串即本条不生效;
 * - contains 忽略大小写, 对齐 SQLite LIKE 对 ASCII 不区分大小写的默认口径。
 */
private fun KeywordHighlight.appliesToBook(bookName: String, bookOrigin: String): Boolean {
    val excludeText = excludeScope
    if (excludeText != null &&
        (excludeText.contains(bookName, ignoreCase = true) ||
            excludeText.contains(bookOrigin, ignoreCase = true))
    ) {
        return false
    }
    val scopeText = scope ?: return true
    if (scopeText.isEmpty()) return true
    return scopeText.contains(bookName, ignoreCase = true) ||
        scopeText.contains(bookOrigin, ignoreCase = true)
}
