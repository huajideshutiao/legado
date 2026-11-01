package io.legado.app.ui.compose.component.code

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 着色缓存行为回归: 全部直接调用真实 [CodeHighlightCache]/[buildHighlightedCode]/
 * [CodeEditorState] 持久槽与真实正则匹配, 以全量匹配结果为对照基准, 不复制任何匹配/
 * 平移算法, 不写死单个书源。计算调度器注入 TestScheduler 管控的标准调度器
 * (生产默认 Default), 取消/重挂/串行屏障的阶段全部确定, 不依赖真实线程时序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodeHighlightCacheTest {

    private val orange = Color(0xFFE65100)
    private val blue = Color(0xFF039BE5)
    private val grey = Color(0xFF607D8B)

    /** 真实规则集 (与 rememberFullCodeSyntax 同构的 legado + json + js 三组) */
    private val fullRules = listOf(
        CodeSyntaxRule(CodePatterns.legado, orange),
        CodeSyntaxRule(CodePatterns.json, blue),
        CodeSyntaxRule(CodePatterns.wrap, grey),
        CodeSyntaxRule(CodePatterns.operation, orange),
        CodeSyntaxRule(CodePatterns.js, blue),
    )
    private val fullScheme = CodeSyntaxScheme(fullRules)

    private val fullRange = 0..Int.MAX_VALUE

    private val jsonSource = """
        {
          "bookSourceUrl": "https://example.com/search?q={{key}}",
          "bookSourceName": "示例源",
          "bookSourceGroup": "测试",
          "searchUrl": "/s?page={{page}}",
          "ruleSearch": {
            "bookList": "class.result||@css:div.item",
            "name": "h3@text",
            "checkKeyWord": "我的"
          },
          "ruleContent": {
            "content": "@XPath://div[@id='content']/text()"
          },
          "jsLib": "function pad(s){return s.length<2?'0'+s:s;}"
        }
    """.trimIndent()

    private val jsSource = """
        function search(book) {
          var url = book.searchUrl + "?q=" + key;
          let page = 1;
          const MAX = 10;
          while (page <= MAX) {
            if (page == 1) {
              url += "\n" + "first";
            } else {
              url += "&p=" + page;
            }
            page = page + 1;
          }
          return url;
        }
    """.trimIndent()

    /** 长文本: 160 行, 每行含 json 串/legado 符号/运算符多类匹配, 明确超过生产初始预算 4000 字符 */
    private val longMultiLine = buildString {
        (1..160).forEach { i ->
            append("  \"field$i\": \"@js:val$i || value$i\",")
            if (i % 10 == 0) append(" // note")
            append('\n')
        }
        append("\"tail\": \"done\"")
    }

    private fun cache(
        dispatcher: CoroutineDispatcher,
        persistent: CodeTextFieldPersistentState = CodeTextFieldPersistentState(),
        scheme: CodeSyntaxScheme = fullScheme,
    ) = CodeHighlightCache(scheme, persistent, dispatcher)

    private fun spansOf(s: AnnotatedString): List<Triple<Int, Int, SpanStyle>> =
        s.spanStyles.map { Triple(it.start, it.end, it.item) }

    private fun assertFullMatchesReference(
        actual: AnnotatedString,
        text: String,
        rules: List<CodeSyntaxRule> = fullRules,
    ) {
        assertEquals(text, actual.text)
        assertEquals(spansOf(buildHighlightedCode(text, rules)), spansOf(actual))
    }

    /** 窗口挂载 = 全量结果中与窗口相交的 span (起点二分须把跨窗口起点的长 span 找回) */
    private fun assertWindowMatchesReference(
        actual: AnnotatedString,
        text: String,
        range: IntRange,
        rules: List<CodeSyntaxRule> = fullRules,
    ) {
        assertEquals(text, actual.text)
        val reference = spansOf(buildHighlightedCode(text, rules))
            .filter { it.second > range.first && it.first <= range.last }
        assertEquals(reference, spansOf(actual))
    }

    @Test
    fun `多文本多长度首次重算与全量匹配一致`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val texts = listOf("", "a", jsonSource, jsSource, longMultiLine)
        for (text in texts) {
            val target = cache(dispatcher)
            target.recompute(text)
            assertFullMatchesReference(target.highlight(text, fullRange, layoutReady = true), text)
        }
    }

    @Test
    fun `连续多类编辑每次重算与全量匹配一致`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val target = cache(dispatcher)
        var text = jsonSource
        target.recompute(text)
        assertFullMatchesReference(target.highlight(text, fullRange, layoutReady = true), text)
        val edits: List<(String) -> String> = listOf(
            // 单字符插入 (行中)
            { t -> t.replace("\"checkKeyWord\": \"我的\"", "\"checkKeyWord\": \"我的书\"") },
            // 单字符删除
            { t -> t.replace("示例源", "示例") },
            // 行中插入空格
            { t -> t.replace("s.length<2", "s.length < 2") },
            // 跨两行替换 (键名不动以免影响后续拆行/并行编辑)
            {
                t ->
                t.replace(
                    "\"bookList\": \"class.result||@css:div.item\",\n    \"name\"",
                    "\"bookList\": \"@css:div.result\",\n    \"name\"",
                )
            },
            // 插入换行 (一行拆两行)
            { t -> t.replace("\"name\": \"h3@text\",", "\"name\":\n          \"h3@text\",") },
            // 删除换行 (两行并一行)
            { t -> t.replace("\"name\":\n          \"h3@text\",", "\"name\": \"h3@text\",") },
            // 头部缩进 (变更点在文首)
            { t -> "    " + t },
            // 末尾追加
            { t -> t + "\n  \"extra\": \"@js:tail\"" },
            // 未闭合引号 (占有量词匹配失败, 该处 span 消失)
            { t -> t.replace("\"h3@text\"", "\"h3@text") },
            // 中段大段删除
            { t -> t.substring(0, t.length / 2) + t.substring(t.length / 2 + 7) },
        )
        for (edit in edits) {
            text = edit(text)
            target.recompute(text)
            assertFullMatchesReference(target.highlight(text, fullRange, layoutReady = true), text)
        }
    }

    @Test
    fun `跨行闭合符删除与新增后与全量一致`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val commentRules = listOf(CodeSyntaxRule(Regex("/\\*[\\s\\S]*?\\*/"), grey))
        val target = cache(dispatcher, scheme = CodeSyntaxScheme(commentRules))
        var text = "code = 1\n/* a */ x = 2\n/* b */ y = 3\n"
        target.recompute(text)
        assertFullMatchesReference(target.highlight(text, fullRange, layoutReady = true), text, commentRules)
        // 删除首个闭合符: 注释跨到更晚的闭合符 (匹配区间必须按完整文本重算)
        text = text.replace("/* a */ x", "/* a  x")
        target.recompute(text)
        assertFullMatchesReference(target.highlight(text, fullRange, layoutReady = true), text, commentRules)
        // 重新补上闭合符: 两段注释恢复
        text = text.replace("/* a  x", "/* a */ x")
        target.recompute(text)
        assertFullMatchesReference(target.highlight(text, fullRange, layoutReady = true), text, commentRules)
    }

    @Test
    fun `未闭合跨行注释补上闭合符后与全量一致`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val commentRules = listOf(CodeSyntaxRule(Regex("/\\*[\\s\\S]*?\\*/"), grey))
        val target = cache(dispatcher, scheme = CodeSyntaxScheme(commentRules))
        var text = "code = 1\n/* a\nb = 2\nc = 3\n"
        target.recompute(text)
        // 未闭合无匹配
        assertEquals(0, buildHighlightedCode(text, commentRules).spanStyles.size)
        // 末尾追加闭合符: 匹配自文首 /* 跨到新闭合符 (变更点远在匹配起点之后)
        text += " */\n"
        target.recompute(text)
        assertFullMatchesReference(target.highlight(text, fullRange, layoutReady = true), text, commentRules)
    }

    @Test
    fun `两段跨行注释合并后与全量一致且跨窗口span不漏挂`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val commentRules = listOf(CodeSyntaxRule(Regex("/\\*[\\s\\S]*?\\*/"), grey))
        val target = cache(dispatcher, scheme = CodeSyntaxScheme(commentRules))
        var text = "val a = 1\n/* 块注释开始\n第二行 */\nval b = 2\n/* 第二段 */\nval c = 3\n"
        target.recompute(text)
        assertFullMatchesReference(target.highlight(text, fullRange, layoutReady = true), text, commentRules)
        // 在跨行 span 内部编辑
        text = text.replace("第二行", "第二行追加")
        target.recompute(text)
        assertFullMatchesReference(target.highlight(text, fullRange, layoutReady = true), text, commentRules)
        // 删除两段之间的内容使两段注释并成一段
        text = text.replace("第二行追加 */\nval b = 2\n/* 第二段 */", "第二行追加直到合并第二段 */")
        target.recompute(text)
        assertFullMatchesReference(target.highlight(text, fullRange, layoutReady = true), text, commentRules)
        // 窗口从注释中间开始: 起点在窗口前的长 span 仍须挂上
        val mid = text.indexOf("第二行追加")
        assertWindowMatchesReference(
            target.highlight(text, mid..Int.MAX_VALUE, layoutReady = true),
            text, mid..Int.MAX_VALUE, commentRules,
        )
    }

    @Test
    fun `跨dirty区lookbehind与文首文末锚点多样本与全量一致`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // lookbehind: 匹配依赖变更区间之外的前置字符
        val lookRules = listOf(CodeSyntaxRule(Regex("(?<=@)js:"), orange))
        val lookTarget = cache(dispatcher, scheme = CodeSyntaxScheme(lookRules))
        var text = "a = \"@js:1\"\nb = \"@js:2\"\n"
        lookTarget.recompute(text)
        assertFullMatchesReference(lookTarget.highlight(text, fullRange, layoutReady = true), text, lookRules)
        text = text.replace("\"@js:1\"", "\"js:1\"")
        lookTarget.recompute(text)
        assertFullMatchesReference(lookTarget.highlight(text, fullRange, layoutReady = true), text, lookRules)
        // 文首/文末锚点 (多行模式), 远端编辑与锚点处编辑多样本
        val anchorRules = listOf(
            CodeSyntaxRule(Regex("(?m)^name"), orange),
            CodeSyntaxRule(Regex("(?m)1$"), blue),
        )
        val anchorTarget = cache(dispatcher, scheme = CodeSyntaxScheme(anchorRules))
        val anchorTexts = listOf(
            "name: a\nmid = 1\nname: b\n",
            "name: a\nmid = 1\n",
            "x = 1\nname: a\n1\n",
        )
        for (sample in anchorTexts) {
            var t = sample
            anchorTarget.recompute(t)
            assertFullMatchesReference(anchorTarget.highlight(t, fullRange, layoutReady = true), t, anchorRules)
            // 远端编辑: 锚点语义保持
            t = t.replaceFirst("mid = 1", "mid = 12")
            anchorTarget.recompute(t)
            assertFullMatchesReference(anchorTarget.highlight(t, fullRange, layoutReady = true), t, anchorRules)
            // 锚点处编辑: 文首/行末锚点随之失效或恢复
            t = t.replaceFirst("name:", "NAME:")
            anchorTarget.recompute(t)
            assertFullMatchesReference(anchorTarget.highlight(t, fullRange, layoutReady = true), t, anchorRules)
        }
    }

    @Test
    fun `等span数量但位置平移后全窗口与窄窗口均不错位`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val target = cache(dispatcher)
        target.recompute(longMultiLine)
        // 前段插入定长文本: 后段 span 全部平移、数量不变 (等数量不同位置)
        val shifted = longMultiLine.replaceFirst("\"field1\"", "\"field1XY\"")
        target.recompute(shifted)
        assertFullMatchesReference(target.highlight(shifted, fullRange, layoutReady = true), shifted)
        // 窄窗口 (文档中部起 / 尾部): 窗口起点落在行中间时, 起点在窗口前的行内匹配仍须挂上
        assertWindowMatchesReference(
            target.highlight(shifted, shifted.length / 2..Int.MAX_VALUE, layoutReady = true),
            shifted, shifted.length / 2..Int.MAX_VALUE,
        )
        assertWindowMatchesReference(
            target.highlight(shifted, shifted.length - 120..shifted.length, layoutReady = true),
            shifted, shifted.length - 120..shifted.length,
        )
    }

    @Test
    fun `布局未就绪且基线命中时按已完成结果全文挂载`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val target = cache(dispatcher)
        target.recompute(longMultiLine)
        // 样本必须长于初始预算窗口, 否则无法区分旧错误窗口与全文挂载
        assertTrue(longMultiLine.length > 4000)
        // 生产初帧条件: 有效基线 + 本次挂载尚无布局 + 初始预算窗口 → 全文挂载, 不重跑正则
        assertFullMatchesReference(
            target.highlight(longMultiLine, 0..4000, layoutReady = false),
            longMultiLine,
        )
    }

    @Test
    fun `布局就绪后按真实视口裁剪`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val target = cache(dispatcher)
        target.recompute(longMultiLine)
        val tail = longMultiLine.length - 300
        assertWindowMatchesReference(
            target.highlight(longMultiLine, tail..longMultiLine.length, layoutReady = true),
            longMultiLine, tail..longMultiLine.length,
        )
        assertWindowMatchesReference(
            target.highlight(longMultiLine, longMultiLine.length / 2..Int.MAX_VALUE, layoutReady = true),
            longMultiLine, longMultiLine.length / 2..Int.MAX_VALUE,
        )
    }

    @Test
    fun `基线未命中时布局未就绪仍只按请求窗口挂载`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // 无基线: 不做首开同步全文挂载
        val fresh = cache(dispatcher)
        val plain = fresh.highlight(jsonSource, 0..4000, layoutReady = false)
        assertEquals(jsonSource, plain.text)
        assertTrue(plain.spanStyles.isEmpty())
        // 基线过期 (文本已变): stale 平移只挂请求窗口内 (初始预算)
        val persistent = CodeTextFieldPersistentState()
        val target = cache(dispatcher, persistent)
        target.recompute(longMultiLine)
        val changed = "xx" + longMultiLine
        val stale = target.highlight(changed, 0..4000, layoutReady = false)
        assertEquals(changed, stale.text)
        assertTrue(stale.spanStyles.all { it.start <= 4000 })
    }

    @Test
    fun `规则不匹配时布局未就绪不采纳旧规则基线`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persistent = CodeTextFieldPersistentState()
        val legadoScheme = CodeSyntaxScheme(listOf(CodeSyntaxRule(CodePatterns.legado, orange)))
        val legadoTarget = cache(dispatcher, persistent, legadoScheme)
        legadoTarget.recompute(jsonSource)
        // 同一持久槽换规则集: 布局未就绪也不按旧规则全文挂载
        val switched = cache(dispatcher, persistent)
        val before = switched.highlight(jsonSource, 0..4000, layoutReady = false)
        assertEquals(jsonSource, before.text)
        assertTrue(before.spanStyles.isEmpty())
        switched.recompute(jsonSource)
        assertFullMatchesReference(
            switched.highlight(jsonSource, fullRange, layoutReady = true), jsonSource,
        )
    }

    @Test
    fun `同字段并发同文本经屏障只算一次`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persistent = CodeTextFieldPersistentState()
        val target = cache(dispatcher, persistent)
        coroutineScope {
            launch { target.recompute(jsonSource) }
            launch { target.recompute(jsonSource) }
        }
        assertEquals(1, target.version)
        assertEquals(jsonSource, persistent.snapshot!!.text)
    }

    @Test
    fun `同字段并发不同文本按最新落盘`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persistent = CodeTextFieldPersistentState()
        val target = cache(dispatcher, persistent)
        coroutineScope {
            launch { target.recompute(jsonSource) }
            launch { target.recompute(jsSource) }
        }
        assertEquals(jsSource, persistent.snapshot!!.text)
        assertEquals(2, target.version)
        assertFullMatchesReference(target.highlight(jsSource, fullRange, layoutReady = true), jsSource)
    }

    @Test
    fun `取消防抖窗口内计算不落盘`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persistent = CodeTextFieldPersistentState()
        val target = cache(dispatcher, persistent)
        target.recompute(jsonSource)
        val job = launch { target.recompute(jsSource) }
        runCurrent()          // 启动并挂起在防抖 delay (TestScheduler 确定阶段)
        job.cancelAndJoin()
        assertEquals(1, target.version)
        assertEquals(jsonSource, persistent.snapshot!!.text)
    }

    @Test
    fun `取消已派发未执行的计算段不落盘`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persistent = CodeTextFieldPersistentState()
        val target = cache(dispatcher, persistent)
        val job = launch { target.recompute(jsonSource) }
        // 取消任务排在计算段派发之前: 计算段已进入屏障但未执行时取消
        val canceller = launch { job.cancel() }
        runCurrent()
        canceller.join()
        job.join()
        assertEquals(0, target.version)
        assertNull(persistent.snapshot)
    }

    @Test
    fun `重挂跨缓存经屏障采纳已算结果并刷新显示`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persistent = CodeTextFieldPersistentState()
        // 重挂先组合 (持久槽尚未落盘): 新缓存自身无基线, 显示无着色
        val oldCache = cache(dispatcher, persistent)
        val remounted = cache(dispatcher, persistent)
        val beforeAdopt = remounted.highlight(jsonSource, fullRange, layoutReady = true)
        assertEquals(jsonSource, beforeAdopt.text)
        assertTrue(beforeAdopt.spanStyles.isEmpty())
        // 并发同文本: 旧缓存计算并落盘, 新缓存等锁后采纳同一份持久快照不重算
        var committedByOld: CodeHighlightSnapshot? = null
        coroutineScope {
            launch {
                oldCache.recompute(jsonSource)
                // 同一运行步内捕获: 等待屏障的重挂缓存此刻尚未继续
                committedByOld = persistent.snapshot
            }
            launch { remounted.recompute(jsonSource) }
        }
        assertEquals(1, oldCache.version)
        assertEquals(1, remounted.version)
        // 两任务结束后持久槽仍是旧缓存落盘的那一份对象: 全程只发生一次真实计算
        assertSame(committedByOld, persistent.snapshot)
        assertEquals(jsonSource, persistent.snapshot!!.text)
        // 采纳后显示失效重挂, 结果与真实全量匹配一致
        assertFullMatchesReference(
            remounted.highlight(jsonSource, fullRange, layoutReady = true), jsonSource,
        )
        // 重复采纳同一快照不重复失效
        val adoptedVersion = remounted.version
        remounted.recompute(jsonSource)
        assertEquals(adoptedVersion, remounted.version)
    }

    @Test
    fun `规则换代跨缓存经屏障按新规则落盘且取消不落盘`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persistent = CodeTextFieldPersistentState()
        val legadoScheme = CodeSyntaxScheme(listOf(CodeSyntaxRule(CodePatterns.legado, orange)))
        val legadoCache = cache(dispatcher, persistent, legadoScheme)
        val fullCache = cache(dispatcher, persistent)
        // 旧规则计算段在屏障内被取消: 不写持久基线
        val legadoJob = launch { legadoCache.recompute(jsonSource) }
        val canceller = launch { legadoJob.cancel() }
        runCurrent()
        canceller.join()
        legadoJob.join()
        assertEquals(0, legadoCache.version)
        assertNull(persistent.snapshot)
        // 新规则缓存随后计算: 按新规则落盘
        fullCache.recompute(jsonSource)
        assertSame(fullRules, persistent.snapshot!!.rules)
        assertFullMatchesReference(fullCache.highlight(jsonSource, fullRange, layoutReady = true), jsonSource)
    }

    @Test
    fun `基线未命中时按全文基线平移覆盖任意窗口且落盘后转精确`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persistent = CodeTextFieldPersistentState()
        val target = cache(dispatcher, persistent)
        val text0 = (1..40).joinToString("\n") { "  \"k$it\": \"@js:$it || $it\"," }
        target.recompute(text0)
        val baselineSpans = persistent.snapshot!!.spans
        assertTrue(baselineSpans.isNotEmpty())
        // 头部插入 2 字符: 全部基线 span 右移 2, 新增字符无色
        val text1 = "xx" + text0
        val stale = target.highlight(text1, fullRange, layoutReady = true)
        assertEquals(text1, stale.text)
        val expected = baselineSpans.map { Triple(it.start + 2, it.end + 2, SpanStyle(color = it.color)) }
        assertEquals(expected, stale.spanStyles.map { Triple(it.start, it.end, it.item) })
        // 同一待算文本期间重复 filter 复用同一实例
        assertSame(stale, target.highlight(text1, fullRange, layoutReady = true))
        // 远离变更点的尾部窗口: 平移结果与全量精确着色一致 (新滚入区不漏挂)
        assertWindowMatchesReference(
            target.highlight(text1, text1.length - 60..text1.length, layoutReady = true),
            text1, text1.length - 60..text1.length,
        )
        // 重算落盘后显示转为精确着色
        target.recompute(text1)
        assertFullMatchesReference(target.highlight(text1, fullRange, layoutReady = true), text1)
    }

    @Test
    fun `编辑器持久槽跨重挂保留基线`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val editor = CodeEditorState(jsonSource)
        val first = CodeHighlightCache(fullScheme, editor.codeFieldState, dispatcher)
        first.recompute(editor.value.text)
        // 重挂: 新缓存从编辑器持久槽播种, 首帧直接命中
        val remounted = CodeHighlightCache(fullScheme, editor.codeFieldState, dispatcher)
        assertFullMatchesReference(remounted.highlight(jsonSource, fullRange, layoutReady = true), jsonSource)
    }

    // ---- 真实 JVM 性能观测: 无耗时阈值断言, 输出供人工提取 ----

    private val jsonScheme = CodeSyntaxScheme(
        listOf(
            CodeSyntaxRule(CodePatterns.json, blue),
            CodeSyntaxRule(CodePatterns.operation, orange),
        )
    )
    private val jsScheme = CodeSyntaxScheme(
        listOf(
            CodeSyntaxRule(CodePatterns.wrap, grey),
            CodeSyntaxRule(CodePatterns.operation, orange),
            CodeSyntaxRule(CodePatterns.js, blue),
        )
    )

    /**
     * 按类型生成至少 [size] 字符的真实样本:
     * dense=规则密集 JSON 行, json=JSON 多行, js=JS 多行,
     * plain=同长度单行普通文本, unclosed=开引号 + 同长度无换行不闭合内容
     */
    private fun perfSample(size: Int, type: String): String {
        val base = buildString(size + 64) {
            var i = 0
            while (length <= size + 32) {
                i++
                when (type) {
                    "json" -> append("{\"k$i\": \"v $i\", \"n\": [1, 2, 3], \"b\": true}\n")
                    "js" -> append("function f$i(a) {\n  var x = a + $i;\n  if (x == null) { return \"s$i\"; }\n  return x;\n}\n")
                    // 普通长行/普通文本: 特殊字符稀疏
                    "plain", "unclosed" -> append("segment$i plain text words and digits $i ")
                    else -> append("  \"field$i\": \"@js:val$i || value$i\", // note $i\n")
                }
            }
        }
        val singleLine = base.replace('\n', ' ')
        return when (type) {
            // 单行普通文本 (真实 fullScheme)
            "plain" -> singleLine.take(size)
            // 开引号 + 同长度无换行内容, 不闭合: 占有量词整条线性失败 (真实 jsonScheme)
            "unclosed" -> "\"" + singleLine.take(size - 1)
            else -> base.take(size)
        }
    }

    private inline fun measureMs(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000.0
    }

    private fun statsMs(values: List<Double>): String {
        val sorted = values.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            sorted[sorted.size / 2]
        }
        return "min=${fmtMs(sorted.first())} median=${fmtMs(median)} " +
            "max=${fmtMs(sorted.last())} n=${sorted.size}"
    }

    private fun fmtMs(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    @Test
    fun `真实缓存多尺寸全量匹配与挂载耗时打印`() {
        val sizes = listOf(10_000, 100_000, 500_000)
        val types = listOf("dense", "json", "js", "plain", "unclosed")
        for (type in types) {
            val scheme = when (type) {
                "json", "unclosed" -> jsonScheme
                "js" -> jsScheme
                else -> fullScheme
            }
            for (size in sizes) {
                val text = perfSample(size, type)
                val measured = if (size <= 10_000) 3 else 2
                val compute = ArrayList<Double>(measured)
                val mountFull = ArrayList<Double>(measured)
                val mountWindow = ArrayList<Double>(measured)
                repeat(measured + 1) { round ->
                    // 每轮新建槽与缓存: 走真实首算路径 (无基线, 不经 150ms 防抖),
                    // 计算经真实 Dispatchers.Default 派发并计入耗时
                    val cache = CodeHighlightCache(scheme, CodeTextFieldPersistentState())
                    val computeMs = measureMs { runBlocking { cache.recompute(text) } }
                    // 每轮新建缓存, 窗口切片缓存为空, 两次挂载都真实重建
                    val fullMs = measureMs {
                        cache.highlight(text, 0..Int.MAX_VALUE, layoutReady = true)
                    }
                    val windowStart = text.length / 2
                    val windowMs = measureMs {
                        cache.highlight(
                            text,
                            windowStart..(windowStart + 4000),
                            layoutReady = true,
                        )
                    }
                    if (round > 0) {
                        compute += computeMs
                        mountFull += fullMs
                        mountWindow += windowMs
                    }
                }
                println(
                    "perf type=$type size=${text.length} compute_ms=${statsMs(compute)} " +
                        "mount_full_ms=${statsMs(mountFull)} mount_window_ms=${statsMs(mountWindow)}"
                )
            }
        }
    }
}
