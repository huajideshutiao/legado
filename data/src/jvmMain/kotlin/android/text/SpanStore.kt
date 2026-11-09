// android.text span 合同的共享适配: JVM 兼容层的可变文本对象 (TextEditable / 桌面适配输入框)
// 组合本类承载 Spanned/Spannable 读写面。文本经读取器取活值, 本类不持第二文本副本。
// 仅存在于 :data 的 jvm 变体 (data/src/jvmMain)。
package android.text

import java.util.IdentityHashMap

/** span flags 的起点/终点位划分 (对齐 android-35 SpannableStringBuilder 同一常量)。 */
private const val FLAG_START_MASK = 0xF0
private const val FLAG_END_MASK = 0x0F
private const val FLAG_START_SHIFT = 4
private const val FLAG_MARK = 1
private const val FLAG_POINT = 2
private const val FLAG_PARAGRAPH = 3

/**
 * 过滤链结果: 最终来源文本与其有效区间。
 *
 * 对齐 android.text.Editable#replace 的合同: 链上每个非 null 结果替换来源 (新区间按结果整段),
 * null 接受原替换, 空串拒绝本次替换。
 */
class InputFilterResult(val text: CharSequence, val start: Int, val end: Int)

/** 按声明顺序执行过滤链 (Editable.replace 与 TextView.setText 共用同一实现)。 */
fun applyInputFilters(
    filters: Array<InputFilter>,
    source: CharSequence,
    start: Int,
    end: Int,
    dest: Spanned,
    dstart: Int,
    dend: Int,
): InputFilterResult {
    var text = source
    var from = start
    var to = end
    for (filter in filters) {
        val replacement = filter.filter(text, from, to, dest, dstart, dend)
        if (replacement != null) {
            text = replacement
            from = 0
            to = replacement.length
        }
    }
    return InputFilterResult(text, from, to)
}

/**
 * 一次替换的来源记录: 切片文字与来源 span (相对切片起点的区间)。在改动前一次性取出,
 * 供文本修改后按新文本复制 span; 临时对象, 不是第二文本状态。
 */
class ReplaceSource internal constructor(
    val text: String,
    private val spans: List<CapturedSpan>,
) {

    internal class CapturedSpan(val what: Any, val start: Int, val end: Int, val flags: Int)

    /** 来源在该片段上没有可扩展 span (零长度替换的免通知条件)。 */
    val hasNoExpandableSpan: Boolean
        get() = spans.all { it.flags == Spanned.SPAN_EXCLUSIVE_EXCLUSIVE }

    internal fun capturedSpans(): List<CapturedSpan> = spans

    companion object {

        /** 取出 [source] 在 [start, end) 的切片文字与 span (按切片裁切, 不改来源)。 */
        fun capture(source: CharSequence, start: Int, end: Int): ReplaceSource {
            val slice = source.subSequence(start, end).toString()
            if (source !is Spanned) return ReplaceSource(slice, emptyList())
            val spans = ArrayList<CapturedSpan>()
            for (span in source.getSpans(start, end, Any::class.java)) {
                val spanStart = source.getSpanStart(span)
                val spanEnd = source.getSpanEnd(span)
                if (spanStart < 0 || spanEnd < 0) continue
                spans.add(
                    CapturedSpan(
                        span,
                        spanStart.coerceIn(start, end) - start,
                        spanEnd.coerceIn(start, end) - start,
                        source.getSpanFlags(span),
                    )
                )
            }
            return ReplaceSource(slice, spans)
        }
    }
}

/**
 * Spanned/Spannable 合同面的共享适配: span 按对象身份 (===) 登记, 区间在文本替换时按
 * MARK/POINT 语义调整, 来源 Spanned 的 span 按切片复制, 读取按 AOSP 的命中边界与
 * 优先级/登记顺序返回。内部标记只服务宿主事务定位, 不经公开 span 面暴露/清除。
 */
class SpanStore(private val text: () -> CharSequence) : Spannable {

    private class SpanRecord(
        val what: Any,
        var start: Int,
        var end: Int,
        var flags: Int,
        val order: Long,
    )

    private val records = ArrayList<SpanRecord>()

    private val recordOf = IdentityHashMap<Any, SpanRecord>()

    /** 宿主事务定位标记 (位置随同一坐标调整更新, 不参与公开 span 面)。 */
    private val internalMarkers = IdentityHashMap<Any, Int>()

    private var nextOrder: Long = 0

    // ===== CharSequence: 活文本只读视图 =====

    override val length: Int get() = text().length

    override fun get(index: Int): Char = text()[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = slice(startIndex, endIndex)

    override fun toString(): String = text().toString()

    /** 区间切片: 复制落在切片内 (含部分重叠) 的 span, NoCopySpan 不复制 (对齐切片构造合同)。 */
    fun slice(startIndex: Int, endIndex: Int): SpanStore {
        val source = text()
        checkTextRange("subSequence", startIndex, endIndex, source.length)
        val sliceText = source.subSequence(startIndex, endIndex).toString()
        val slice = SpanStore { sliceText }
        for (span in getSpans(startIndex, endIndex, Any::class.java)) {
            if (span is NoCopySpan) continue
            val spanStart = (getSpanStart(span) - startIndex).coerceIn(0, sliceText.length)
            val spanEnd = (getSpanEnd(span) - startIndex).coerceIn(0, sliceText.length)
            slice.setSpanFromCopy(span, spanStart, spanEnd, getSpanFlags(span))
        }
        return slice
    }

    /** 对齐 Editable/GetChars.getChars: 范围校验后把该段字符拷入目标数组。 */
    fun getChars(start: Int, end: Int, dest: CharArray, destoff: Int) {
        val source = text()
        checkTextRange("getChars", start, end, source.length)
        for (index in start until end) {
            dest[destoff + index - start] = source[index]
        }
    }

    // ===== Spannable / Spanned =====

    override fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {
        if (what == null) return
        val textLength = length
        checkTextRange("setSpan", start, end, textLength)
        val startFlag = startFlagOf(flags)
        val endFlag = endFlagOf(flags)
        if (isInvalidParagraph(start, startFlag, textLength)) {
            throw RuntimeException("PARAGRAPH span must start at paragraph boundary")
        }
        if (isInvalidParagraph(end, endFlag, textLength)) {
            throw RuntimeException("PARAGRAPH span must end at paragraph boundary")
        }
        // 0 长度的 SPAN_EXCLUSIVE_EXCLUSIVE 不允许登记
        if (startFlag == FLAG_POINT && endFlag == FLAG_MARK && start == end) return
        register(what, start, end, flags)
    }

    override fun removeSpan(what: Any?) {
        if (what == null) return
        val record = recordOf.remove(what) ?: return
        records.remove(record)
    }

    override fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<T> {
        val matched = ArrayList<SpanRecord>()
        for (record in records) {
            if (record.start > end) continue
            if (record.end < start) continue
            if (record.start != record.end && start != end) {
                if (record.start == end) continue
                if (record.end == start) continue
            }
            if (!type.isInstance(record.what)) continue
            matched.add(record)
        }
        matched.sortWith(
            compareByDescending<SpanRecord> { it.flags and Spanned.SPAN_PRIORITY }
                .thenBy { it.order }
        )
        @Suppress("UNCHECKED_CAST")
        val result = java.lang.reflect.Array.newInstance(type, matched.size) as Array<T>
        matched.forEachIndexed { index, record -> result[index] = type.cast(record.what) }
        return result
    }

    override fun getSpanStart(tag: Any?): Int = if (tag == null) -1 else recordOf[tag]?.start ?: -1

    override fun getSpanEnd(tag: Any?): Int = if (tag == null) -1 else recordOf[tag]?.end ?: -1

    override fun getSpanFlags(tag: Any?): Int = if (tag == null) 0 else recordOf[tag]?.flags ?: 0

    override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int {
        var next = limit
        for (record in records) {
            if (type != null && !type.isInstance(record.what)) continue
            if (record.start > start && record.start < next) next = record.start
            if (record.end > start && record.end < next) next = record.end
        }
        return next
    }

    /** 清空用户 span (Editable.clearSpans); 内部事务标记不受影响。 */
    fun clearSpans() {
        records.clear()
        recordOf.clear()
    }

    /** 登记宿主事务定位标记 (不暴露给 getSpans/clearSpans)。 */
    fun setInternalMarker(marker: Any, position: Int) {
        internalMarkers[marker] = position
    }

    /** 移除宿主事务定位标记。 */
    fun removeInternalMarker(marker: Any?) {
        if (marker == null) return
        internalMarkers.remove(marker)
    }

    /** 读宿主事务定位标记当前位置 (未登记 = -1)。 */
    fun internalMarkerPosition(marker: Any): Int = internalMarkers[marker] ?: -1

    /**
     * 受控替换 (经编辑事务修改缓冲): 同一区间内移动用户 span 与宿主事务标记, 两条职责共用
     * 同一 mapBoundary。库外部已提交的变更只对齐用户 span, 见 [adjustUserSpansForReplace]。
     */
    fun adjustForReplace(start: Int, end: Int, newLength: Int, oldLength: Int) {
        val delta = newLength - (end - start)
        adjustUserSpans(start, end, newLength, delta, oldLength)
        for (marker in internalMarkers.keys.toList()) {
            val position = internalMarkers.getValue(marker)
            internalMarkers[marker] = mapBoundary(
                position,
                start,
                end,
                newLength,
                delta,
                FLAG_POINT,
                oldLength,
            )
        }
    }

    /** 库外部已提交的净变更: 只按旧值区间对齐用户 span; 事务标记已属当前原文基准, 不再平移。 */
    fun adjustUserSpansForReplace(start: Int, end: Int, newLength: Int, oldLength: Int) {
        adjustUserSpans(start, end, newLength, newLength - (end - start), oldLength)
    }

    private fun adjustUserSpans(start: Int, end: Int, newLength: Int, delta: Int, oldLength: Int) {
        val iterator = records.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next()
            val startFlag = startFlagOf(record.flags)
            val endFlag = endFlagOf(record.flags)
            val mappedStart = mapBoundary(record.start, start, end, newLength, delta, startFlag, oldLength)
            val mappedEnd = mapBoundary(record.end, start, end, newLength, delta, endFlag, oldLength)
            val removed = mappedStart > mappedEnd ||
                (mappedStart == mappedEnd && startFlag == FLAG_POINT && endFlag == FLAG_MARK)
            if (removed) {
                iterator.remove()
                recordOf.remove(record.what)
            } else {
                record.start = mappedStart
                record.end = mappedEnd
            }
        }
    }

    /** 把改动前取下的来源 span 复制到新文本 [destStart] 起的位置 (段落不合法/已存在则不复制)。 */
    fun copySourceSpans(source: ReplaceSource, destStart: Int) {
        for (span in source.capturedSpans()) {
            if (getSpanStart(span.what) >= 0) continue
            setSpanFromCopy(
                span.what,
                destStart + span.start,
                destStart + span.end,
                span.flags,
            )
        }
    }

    private fun register(what: Any, start: Int, end: Int, flags: Int) {
        val existing = recordOf[what]
        if (existing != null) {
            existing.start = start
            existing.end = end
            existing.flags = flags
        } else {
            val record = SpanRecord(what, start, end, flags, nextOrder++)
            records.add(record)
            recordOf[what] = record
        }
    }

    /** 复制路径的登记: 不满足段落边界约束的 span 不保留 (对齐库复制语义)。 */
    private fun setSpanFromCopy(what: Any, start: Int, end: Int, flags: Int) {
        val textLength = length
        if (start < 0 || end < start || end > textLength) return
        val startFlag = startFlagOf(flags)
        val endFlag = endFlagOf(flags)
        if (isInvalidParagraph(start, startFlag, textLength)) return
        if (isInvalidParagraph(end, endFlag, textLength)) return
        if (startFlag == FLAG_POINT && endFlag == FLAG_MARK && start == end) return
        register(what, start, end, flags)
    }

    private fun isInvalidParagraph(index: Int, flag: Int, textLength: Int): Boolean =
        flag == FLAG_PARAGRAPH && index != 0 && index != textLength && text()[index - 1] != '\n'

    private fun startFlagOf(flags: Int): Int = (flags and FLAG_START_MASK) shr FLAG_START_SHIFT

    private fun endFlagOf(flags: Int): Int = flags and FLAG_END_MASK

    private fun mapBoundary(
        boundary: Int,
        start: Int,
        end: Int,
        newLength: Int,
        delta: Int,
        flag: Int,
        oldLength: Int,
    ): Int {
        // 段落边界在全文末尾时按 POINT 处理 (SPAN_PARAGRAPH 的端点约定)
        val effectiveFlag = if (flag == FLAG_PARAGRAPH && boundary == oldLength) FLAG_POINT else flag
        if (start == end) {
            // 纯插入点: 标记留在原处, 点被推到插入文本之后
            if (boundary == start) return if (effectiveFlag == FLAG_POINT) start + newLength else start
            return if (boundary < start) boundary else boundary + delta
        }
        return when {
            boundary < start -> boundary
            boundary > end -> boundary + delta
            boundary == start -> start
            boundary == end -> end + delta
            effectiveFlag == FLAG_POINT -> start + newLength
            else -> start
        }
    }
}

/** 对齐 SpannableStringBuilder.checkRange 的区间报错 (外部入参先校验, 不做 clamp)。 */
fun checkTextRange(operation: String, start: Int, end: Int, length: Int) {
    if (end < start) throw IndexOutOfBoundsException("$operation ($start ... $end) has end before start")
    if (start > length || end > length) {
        throw IndexOutOfBoundsException("$operation ($start ... $end) ends beyond length $length")
    }
    if (start < 0 || end < 0) throw IndexOutOfBoundsException("$operation ($start ... $end) starts before 0")
}
