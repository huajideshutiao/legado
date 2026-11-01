package io.legado.app.ui.compose.component.code

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查找匹配自动刷新回归: 直接驱动生产自动刷新循环 ([CodeSearchHighlightState.autoRefreshSearch])
 * 与真实查找目标 [CodeEditorSearchTarget]/[CodeEditorState] (与生产绑定共用同一实现, 无 mock),
 * 以同一参数的真实搜索实现 [buildSearchRanges] 为对照。计算调度器注入 TestScheduler
 * 管控的标准调度器 (生产默认 Default), 阶段全部确定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodeSearchHighlightStateTest {

    private fun longNeedleText(): String = buildString {
        repeat(2600) { append("padding-pad-") }
        append("needle")
    }

    @Test
    fun `初次绑定校验既有结果不重算`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val editor = CodeEditorState("alpha beta alpha gamma")
        val state = CodeSearchHighlightState()
        // 生产查找面板路径先行算好同一输入
        val target = CodeEditorSearchTarget(editor, state) {}
        target.find("alpha", useRegex = false, matchCase = false, wholeWord = false, forward = true)
        val version = state.version
        val ranges = state.ranges
        val loop = launch { state.autoRefreshSearch(editor, dispatcher) }
        advanceUntilIdle()
        assertEquals(version, state.version)
        assertEquals(ranges, state.ranges)
        assertEquals(
            SearchInput("alpha", useRegex = false, matchCase = false, wholeWord = false, text = editor.value.text),
            state.matchedInput,
        )
        loop.cancel()
    }

    @Test
    fun `文本变化经自动刷新重算并发布且下标钳制`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val editor = CodeEditorState("alpha beta alpha gamma")
        val state = CodeSearchHighlightState()
        val target = CodeEditorSearchTarget(editor, state) {}
        target.find("alpha", useRegex = false, matchCase = false, wholeWord = false, forward = true)
        val loop = launch { state.autoRefreshSearch(editor, dispatcher) }
        runCurrent()
        // 删除首个匹配: 匹配数变化, 旧命中下标越界
        // 纯 JVM 无 UI 全局快照应用调度, 文本变更包在真实 MutableSnapshot 内以投递应用通知
        Snapshot.withMutableSnapshot { editor.edit { replace(0, 5, "bet") } }
        advanceUntilIdle()
        assertEquals(
            buildSearchRanges("alpha", useRegex = false, matchCase = false, wholeWord = false, text = editor.value.text),
            state.ranges,
        )
        assertEquals(
            SearchInput("alpha", useRegex = false, matchCase = false, wholeWord = false, text = editor.value.text),
            state.matchedInput,
        )
        assertEquals(0, state.currentIndex)
        loop.cancel()
    }

    @Test
    fun `参数变化经自动刷新按新参数重算`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val editor = CodeEditorState("cat catalog CAT concat")
        val state = CodeSearchHighlightState()
        val loop = launch { state.autoRefreshSearch(editor, dispatcher) }
        // 首次匹配完成 (字面量, 忽略大小写); 查询参数变更走真实快照应用通知
        Snapshot.withMutableSnapshot { state.keyword = "cat" }
        advanceUntilIdle()
        assertEquals(
            buildSearchRanges("cat", useRegex = false, matchCase = false, wholeWord = false, text = editor.value.text),
            state.ranges,
        )
        assertEquals(
            SearchInput("cat", useRegex = false, matchCase = false, wholeWord = false, text = editor.value.text),
            state.matchedInput,
        )
        // 匹配完成后改单一参数: 既有结果按旧参数算出, 必须重算 (不得当作命中)
        Snapshot.withMutableSnapshot { state.wholeWord = true }
        advanceUntilIdle()
        assertEquals(
            buildSearchRanges("cat", useRegex = false, matchCase = false, wholeWord = true, text = editor.value.text),
            state.ranges,
        )
        assertEquals(
            SearchInput("cat", useRegex = false, matchCase = false, wholeWord = true, text = editor.value.text),
            state.matchedInput,
        )
        // 再改大小写
        Snapshot.withMutableSnapshot { state.matchCase = true }
        advanceUntilIdle()
        assertEquals(
            buildSearchRanges("cat", useRegex = false, matchCase = true, wholeWord = true, text = editor.value.text),
            state.ranges,
        )
        // 再改正则与关键词 (同一快照内一次应用)
        Snapshot.withMutableSnapshot {
            state.useRegex = true
            state.keyword = "c[au]t"
        }
        advanceUntilIdle()
        assertEquals(
            buildSearchRanges("c[au]t", useRegex = true, matchCase = true, wholeWord = true, text = editor.value.text),
            state.ranges,
        )
        assertEquals(
            SearchInput("c[au]t", useRegex = true, matchCase = true, wholeWord = true, text = editor.value.text),
            state.matchedInput,
        )
        loop.cancel()
    }

    @Test
    fun `clear后进行中的自动刷新不发布旧结果`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val editor = CodeEditorState("needle x needle")
        val state = CodeSearchHighlightState()
        val target = CodeEditorSearchTarget(editor, state) {}
        target.find("needle", useRegex = false, matchCase = false, wholeWord = false, forward = true)
        val loop = launch { state.autoRefreshSearch(editor, dispatcher) }
        runCurrent()
        // 文本变化触发重算链 (挂起在防抖 delay), 随后清空查询 (面板关闭路径)
        Snapshot.withMutableSnapshot {
            editor.edit { replace(editor.value.text.length, editor.value.text.length, " needle") }
        }
        runCurrent()
        Snapshot.withMutableSnapshot { state.clear() }
        advanceUntilIdle()
        assertEquals("", state.keyword)
        assertEquals(emptyList<IntRange>(), state.ranges)
        assertEquals(null, state.matchedInput)
        loop.cancel()
    }

    @Test
    fun `文本变化后find重算而不沿用旧区间`() = runTest {
        val editor = CodeEditorState("alpha beta")
        val state = CodeSearchHighlightState()
        val target = CodeEditorSearchTarget(editor, state) {}
        target.find("alpha", useRegex = false, matchCase = false, wholeWord = false, forward = true)
        editor.edit { replace(0, 5, "gamma") }
        target.find("alpha", useRegex = false, matchCase = false, wholeWord = false, forward = true)
        assertTrue(state.ranges.isEmpty())
        assertEquals(
            SearchInput("alpha", useRegex = false, matchCase = false, wholeWord = false, text = editor.value.text),
            state.matchedInput,
        )
    }

    @Test
    fun `超长文本经自动刷新仍重算不做静默跳过`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val text = longNeedleText()
        assertTrue(text.length > 20000)
        val editor = CodeEditorState(text)
        val state = CodeSearchHighlightState()
        Snapshot.withMutableSnapshot { state.keyword = "needle" }
        val loop = launch { state.autoRefreshSearch(editor, dispatcher) }
        advanceUntilIdle()
        assertEquals(1, state.ranges.size)
        assertEquals(text.length - 6, state.ranges[0].first)
        assertEquals(text.length - 1, state.ranges[0].last)
        assertEquals(
            SearchInput("needle", useRegex = false, matchCase = false, wholeWord = false, text = text),
            state.matchedInput,
        )
        loop.cancel()
    }
}
