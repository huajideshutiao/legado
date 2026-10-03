package io.legado.app.help

/**
 * 划线位置重锚 (纯函数, 无平台依赖)。
 *
 * 划线存的是章内绝对字符偏移 (口径同 `TextLine.chapterPosition`, 即净化/替换/简繁之后的
 * 排版输入文本), 而净化/替换/重新分段都在排版之前改动正文长度, 正文编辑后存量划线整体漂移。
 * 以创建时保存的原文 bookText 在重排后的章节文本里就近搜回真实位置。
 *
 * 章节文本由阅读层从 `TextPage.text` 顺序拼接重建, 与保存划线时的账本同源:
 * 每个 TextColumn 消耗 charData.length, 非文字列消耗 1, 段尾行追加 "\n"。
 */
object HighlightAnchor {

    /** 重锚后的章内半开区间 [start, endExclusive) */
    data class Anchor(val start: Int, val endExclusive: Int)

    /**
     * @param text 重排后的整章文本 (与保存划线时同一账本口径)
     * @param start 创建时存下的章内起点
     * @param endExclusive 创建时存下的章内终点 (半开); 无原文时原样沿用
     * @param bookText 创建时存下的划线原文; 空 = 无从搜索, 直接沿用存量偏移
     * @return 重锚后的区间; null = 原文已不存在, 调用方隐藏该划线, 不得报错
     */
    fun reanchor(text: String, start: Int, endExclusive: Int, bookText: String): Anchor? {
        if (bookText.isEmpty()) {
            return if (start in 0..text.length) Anchor(start, endExclusive) else null
        }
        if (text.isEmpty()) return null
        // 未漂移的常见情形: 原位即命中, 免掉全章搜索
        if (start in 0..text.length - bookText.length && text.startsWith(bookText, start)) {
            return Anchor(start, start + bookText.length)
        }
        val hit = nearestOccurrence(text, bookText, start) ?: return null
        return Anchor(hit, hit + bookText.length)
    }

    /** 取距 [target] 最近的一处出现; 前后等距时取靠前者 */
    private fun nearestOccurrence(text: String, pattern: String, target: Int): Int? {
        val before = text.lastIndexOf(pattern, target)
        val after = text.indexOf(pattern, target)
        return when {
            before < 0 -> if (after < 0) null else after
            after < 0 -> before
            target - before <= after - target -> before
            else -> after
        }
    }
}
