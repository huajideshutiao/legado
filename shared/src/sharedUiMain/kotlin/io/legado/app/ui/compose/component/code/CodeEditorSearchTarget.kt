package io.legado.app.ui.compose.component.code

import androidx.compose.ui.text.TextRange

/**
 * 把 [CodeEditorState] 适配成辅助键条的查找替换目标 (对齐原 CodeView 的 find/replace/replaceAll)。
 * 匹配区间写入 [searchHighlight], 由聚焦字段的 CodeTextField 叠加渲染
 * (全量黄底 + 当前命中强调色, 对齐原版 BackgroundColorSpan 高亮); 同时移动选区定位。
 *
 * 共享件: BookSourceEditScreen / CodeDialogContent / JsEditScreen 的 KeyboardToolbar 共用
 * (原版三者都是 CodeView 自带查找替换, 下沉后收为同一适配器)。
 */
class CodeEditorSearchTarget(
    private val editor: CodeEditorState,
    private val searchHighlight: CodeSearchHighlightState,
    private val onClearFocus: () -> Unit,
) : KeyboardToolbarTarget {

    override fun clearFocus() = onClearFocus()

    override fun find(
        keyword: String,
        useRegex: Boolean,
        matchCase: Boolean,
        wholeWord: Boolean,
        forward: Boolean?,
    ) {
        if (keyword.isEmpty()) {
            // 对齐原版 find("") → clearSearch
            searchHighlight.clear()
            return
        }
        // 对齐原版 needRecompute: 参数没变且已有匹配时按 currentMatchIndex 顺延, 否则从光标定位
        val needRecompute = searchHighlight.keyword != keyword ||
            searchHighlight.useRegex != useRegex ||
            searchHighlight.matchCase != matchCase ||
            searchHighlight.wholeWord != wholeWord ||
            searchHighlight.ranges.isEmpty() ||
            searchHighlight.currentIndex !in searchHighlight.ranges.indices
        val index: Int
        if (needRecompute) {
            val ranges =
                buildSearchRanges(keyword, useRegex, matchCase, wholeWord, editor.value.text)
            if (ranges.isEmpty()) {
                searchHighlight.update(keyword, useRegex, matchCase, wholeWord, emptyList(), -1)
                return
            }
            val cursor = editor.value.selection.min
            index = if (forward == true) {
                // forward=null (输入防抖触发) 按原版当作向上定位: 光标前最后一个匹配
                ranges.indexOfFirst { it.first >= cursor }.let { if (it == -1) 0 else it }
            } else {
                ranges.indexOfLast { it.last <= cursor }
                    .let { if (it == -1) ranges.size - 1 else it }
            }
            searchHighlight.update(keyword, useRegex, matchCase, wholeWord, ranges, index)
        } else {
            val size = searchHighlight.ranges.size
            index = if (forward == true) {
                (searchHighlight.currentIndex + 1) % size
            } else {
                (searchHighlight.currentIndex - 1 + size) % size
            }
            searchHighlight.update(
                keyword, useRegex, matchCase, wholeWord, searchHighlight.ranges, index
            )
        }
        val range = searchHighlight.ranges[index]
        editor.edit {
            selection = TextRange(range.first, range.last + 1)
        }
    }

    override fun replace(
        keyword: String,
        useRegex: Boolean,
        matchCase: Boolean,
        wholeWord: Boolean,
        replacement: String,
    ) {
        val selection = editor.value.selection
        val ranges = buildSearchRanges(keyword, useRegex, matchCase, wholeWord, editor.value.text)
        // 当前选区正好落在某个匹配上才替换, 否则先定位 (对齐原版 needFind 分支)
        val hit = ranges.any { it.first == selection.min && it.last + 1 == selection.max }
        if (!hit || selection.collapsed) {
            find(keyword, useRegex, matchCase, wholeWord, forward = true)
            return
        }
        // 对齐旧 editor.onValueChange(editor.value.insertAtCursor(replacement)):
        // 替换整个选区 (非仅插入)
        editor.edit { replace(selection.min, selection.max, replacement) }
        // 文本已变, 强制重算匹配区间再定位下一个 (对齐原版 replace 后 find(forward=true))
        val fresh = buildSearchRanges(keyword, useRegex, matchCase, wholeWord, editor.value.text)
        searchHighlight.update(keyword, useRegex, matchCase, wholeWord, fresh, -1)
        find(keyword, useRegex, matchCase, wholeWord, forward = true)
    }

    override fun replaceAll(replacement: String) {
        if (searchHighlight.keyword.isEmpty()) return
        val text = editor.value.text
        val ranges = buildSearchRanges(
            searchHighlight.keyword, searchHighlight.useRegex,
            searchHighlight.matchCase, searchHighlight.wholeWord, text
        )
        if (ranges.isEmpty()) return
        // 倒序替换避免坐标错位 (对齐原版 replaceAll), 替换内容按字面量使用 (不解释 $1)
        var replaced = text
        for (i in ranges.indices.reversed()) {
            replaced = replaced.replaceRange(ranges[i].first, ranges[i].last + 1, replacement)
        }
        if (replaced != text) {
            editor.edit {
                replace(0, length, replaced)
                selection = TextRange(
                    editor.value.selection.min.coerceAtMost(replaced.length)
                )
            }
        }
        // 对齐原版 replaceAll 末尾的 reHighlightSearch: 当前命中态清空, 匹配区间重算
        val fresh = buildSearchRanges(
            searchHighlight.keyword, searchHighlight.useRegex,
            searchHighlight.matchCase, searchHighlight.wholeWord, replaced
        )
        searchHighlight.update(
            searchHighlight.keyword, searchHighlight.useRegex,
            searchHighlight.matchCase, searchHighlight.wholeWord, fresh, -1
        )
    }
}
