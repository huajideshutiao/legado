package io.legado.app.model.chapter

/**
 * 「前一章 / 当前章 / 后一章」三章窗口的公共判定。
 *
 * 四种阅读模式 (文字 / 漫画 / 音频 / 视频) 的章节装载都以当前章为中心：
 * 文字与漫画持有 prev/cur/next 三槽容器, 音视频引擎一次只装一条资源, 窗口退化成
 * 「是否仍是当前章」的作废判定 —— 两者判定式同源, 收敛在此。
 *
 * 原版依据: `ReadBook.contentLoadFinish` 的 `chapter.index !in durChapterIndex - 1..durChapterIndex + 1`
 * 与 `ReadMangaViewModel.contentLoadFinish` 同款守卫; 音频 `AudioPlayService.contentLoadFinish`
 * 的 `chapter.index != durChapterIndex` 即半径 0 的同一判定。
 */

/** 窗口半径: 当前章 ±1。 */
const val CHAPTER_WINDOW_RADIUS = 1

/** 窗口槽位 (相对当前章的偏移)。 */
enum class ChapterWindowSlot {
    PREV,
    CUR,
    NEXT,
}

/** 以 [center] 为中心的三章窗口。 */
fun chapterWindowOf(center: Int): IntRange =
    center - CHAPTER_WINDOW_RADIUS..center + CHAPTER_WINDOW_RADIUS

/** [index] 是否落在以 [center] 为中心的三章窗口内。 */
fun isInChapterWindow(index: Int, center: Int): Boolean = index in chapterWindowOf(center)

/** [index] 在窗口中的槽位; 窗口外返回 null (调用方按「已作废」处理)。 */
fun chapterWindowSlotOf(index: Int, center: Int): ChapterWindowSlot? =
    when (index - center) {
        -1 -> ChapterWindowSlot.PREV
        0 -> ChapterWindowSlot.CUR
        1 -> ChapterWindowSlot.NEXT
        else -> null
    }

/**
 * 三章装载顺序: 当前章优先, 再后章、前章。
 *
 * 顺序对齐原版 `ReadBook.loadContent()` / `ReadMangaViewModel.loadContent()` 的三发次序,
 * 后章先于前章 (顺读时后章更可能被立刻用到)。
 */
fun chapterWindowIndices(center: Int): IntArray = intArrayOf(center, center + 1, center - 1)
