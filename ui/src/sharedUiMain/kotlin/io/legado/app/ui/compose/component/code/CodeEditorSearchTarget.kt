package io.legado.app.ui.compose.component.code

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * 把 [CodeEditorState] 适配成辅助键条的查找替换目标 (对齐原 CodeView 的 find/replace/replaceAll)。
 * 匹配区间写入 [searchHighlight], 由聚焦字段的 CodeTextField 叠加渲染
 * (全量黄底 + 当前命中强调色, 对齐原版 BackgroundColorSpan 高亮); 同时移动选区定位。
 *
 * 共享件: BookSourceEditScreen / SourceToolboxScreen / CodeDialog 的 KeyboardToolbar 共用
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
        // 对齐原版 needRecompute: 既有结果按当前输入 (查询参数 + 文本) 仍有效且可定位时
        // 顺延, 否则重算
        val needRecompute = searchHighlight.matchedInput !=
            SearchInput(keyword, useRegex, matchCase, wholeWord, editor.value.text) ||
            searchHighlight.ranges.isEmpty() ||
            searchHighlight.currentIndex !in searchHighlight.ranges.indices
        val index: Int
        if (needRecompute) {
            val ranges =
                buildSearchRanges(keyword, useRegex, matchCase, wholeWord, editor.value.text)
            if (ranges.isEmpty()) {
                searchHighlight.update(
                    keyword, useRegex, matchCase, wholeWord, emptyList(), -1, editor.value.text,
                )
                return
            }
            val cursor = editor.value.selection.min
            index = if (forward == true) {
                // 向下定位: 光标及其后的第一个匹配
                ranges.indexOfFirst { it.first >= cursor }.let { if (it == -1) 0 else it }
            } else {
                // forward=false 与 forward=null (输入防抖触发) 都按原版 find 的 forward
                // 默认值 false 走向上定位: 光标前最后一个匹配
                ranges.indexOfLast { it.last <= cursor }
                    .let { if (it == -1) ranges.size - 1 else it }
            }
            searchHighlight.update(keyword, useRegex, matchCase, wholeWord, ranges, index, editor.value.text)
        } else {
            val size = searchHighlight.ranges.size
            index = if (forward == true) {
                (searchHighlight.currentIndex + 1) % size
            } else {
                (searchHighlight.currentIndex - 1 + size) % size
            }
            searchHighlight.update(
                keyword, useRegex, matchCase, wholeWord, searchHighlight.ranges, index,
                editor.value.text,
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
        searchHighlight.update(keyword, useRegex, matchCase, wholeWord, fresh, -1, editor.value.text)
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
            searchHighlight.matchCase, searchHighlight.wholeWord, fresh, -1, replaced
        )
    }
}

/**
 * 激活编辑器 → 查找匹配区间的自动刷新循环 (生产绑定与测试共用): 观察当前文本与
 * 接收者的当前查询参数, 任一变化 (含初次绑定) 先校验既有结果是否已按同一输入算好
 * (查找面板 find 已写入则复用, 不重算); 未命中时防抖 [AsyncHighlightDelayMs] 并在
 * [matchDispatcher] (生产为 Default) 后台全文匹配, 回到调用协程发布。发布前按实时
 * 状态复核: 匹配期间文本/参数/clear 已变化则丢弃, 旧结果不写新查询。编辑器切换
 * 由调用方的 collectLatest 取消旧循环。
 */
internal suspend fun CodeSearchHighlightState.autoRefreshSearch(
    editor: CodeEditorState,
    matchDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    snapshotFlow {
        SearchInput(keyword, useRegex, matchCase, wholeWord, editor.textFieldState.text.toString())
    }.collectLatest { input ->
        if (input.keyword.isEmpty()) return@collectLatest
        // 既有结果已按同一输入算好 (find 已写入): 复用
        if (matchedInput == input) return@collectLatest
        delay(AsyncHighlightDelayMs)
        val ranges = withContext(matchDispatcher) {
            buildSearchRanges(input.keyword, input.useRegex, input.matchCase, input.wholeWord, input.text)
        }
        // 发布前复核: 同一输入已由 find 发布则不重复; 匹配期间文本/参数/clear 已变化 → 丢弃
        if (matchedInput == input) return@collectLatest
        if (keyword != input.keyword || useRegex != input.useRegex || matchCase != input.matchCase ||
            wholeWord != input.wholeWord || editor.textFieldState.text.toString() != input.text
        ) {
            return@collectLatest
        }
        val index = if (ranges.isEmpty()) -1 else currentIndex.coerceIn(0, ranges.size - 1)
        update(input.keyword, input.useRegex, input.matchCase, input.wholeWord, ranges, index, input.text)
    }
}

/**
 * 激活编辑器文本/查询参数 → 查找匹配区间的统一自动刷新绑定: 编辑器切换 (聚焦换
 * 字段 / 容器代次重建为 null) 时旧编辑器的刷新循环随 collectLatest 取消重启, 旧结果
 * 不写新查询; 字段控件重挂不换编辑器, 区间随文本保持有效。刷新逻辑见
 * [autoRefreshSearch] (生产与测试共用同一实现)。查找参数变化与命中定位由查找面板
 * 的 find 触发, 自动刷新对同一输入复用其结果。
 *
 * [editorProvider] 须为读稳定 State 持有者的 remember 实例 (本函数不在重组期读它,
 * 编辑器切换靠 snapshotFlow 追踪, 不触发宿主重组)。
 */
@Composable
fun CodeEditorSearchHighlightBinding(
    editorProvider: () -> CodeEditorState?,
    searchHighlight: CodeSearchHighlightState,
) {
    LaunchedEffect(editorProvider, searchHighlight) {
        snapshotFlow { editorProvider() }
            .collectLatest { editor ->
                if (editor == null) return@collectLatest
                searchHighlight.autoRefreshSearch(editor)
            }
    }
}
