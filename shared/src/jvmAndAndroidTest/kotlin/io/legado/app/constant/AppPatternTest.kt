package io.legado.app.constant

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.regex.Pattern

/**
 * AppPattern 五常量 Pattern→Regex 迁移金样：对照旧 Pattern 定义逐匹配验证。
 */
class AppPatternTest {

    private fun assertSameMatches(oracle: Pattern, ours: Regex, input: String) {
        val m = oracle.matcher(input)
        val kIter = ours.findAll(input).iterator()
        while (m.find()) {
            org.junit.Assert.assertTrue("Regex 少匹配 @${m.start()}", kIter.hasNext())
            val k = kIter.next()
            assertEquals("匹配区间 start", m.start(), k.range.first)
            assertEquals("匹配区间 end", m.end(), k.range.last + 1)
            for (g in 0..m.groupCount()) {
                assertEquals("group($g)", m.group(g), k.groups[g]?.value)
            }
        }
        org.junit.Assert.assertFalse("Regex 多匹配", kIter.hasNext())
    }

    @Test
    fun `JS_PATTERN 含大小写与 @js 分支`() {
        val oracle = Pattern.compile("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", Pattern.CASE_INSENSITIVE)
        listOf(
            "https://x.com/{{page}},<js>result.replace('a','b')</js>",
            "<JS>\nresult\n</JS>后缀", // 大小写不敏感 + 跨行
            "@js:java.ajax(url)",
            "@JS:result+1",
            "前缀<js>a</js>中缀<js>b</js>@js:tail",
            "无匹配纯文本",
        ).forEach { assertSameMatches(oracle, AppPattern.JS_PATTERN, it) }
    }

    @Test
    fun `EXP_PATTERN 花括号表达式`() {
        val oracle = Pattern.compile("\\{\\{([\\w\\W]*?)\\}\\}")
        listOf(
            "https://x.com/search?q={{key}}&p={{page}}",
            "{{java.get('u')}}/{{page-1}}",
            "{{跨\n行}}",
            "无表达式",
        ).forEach { assertSameMatches(oracle, AppPattern.EXP_PATTERN, it) }
    }

    @Test
    fun `urlParamPattern 逗号后紧跟花括号`() {
        val oracle = Pattern.compile("\\s*,\\s*(?=\\{)")
        listOf(
            "https://x.com/s?q=a,{'method':'POST'}",
            "https://x.com/s , {\"charset\":\"gbk\"}",
            "https://x.com/a,b,c", // 逗号后非 { 不切
            "url, {opt}, {opt2}",
        ).forEach { assertSameMatches(oracle, AppPattern.urlParamPattern, it) }
    }

    @Test
    fun `imgPattern 格式化后图片标签`() {
        val oracle = Pattern.compile("<img src=\"([^\"]*(?:\"[^>]+\\})?)[^>]+>")
        listOf(
            "<img src=\"https://x.com/1.jpg\">",
            "文字<img src=\"a.png\" style=\"width:1px\">尾巴",
            "<img src=\"b.webp\",{\"headers\":{\"referer\":\"x\"}}>", // src 带 option 的书源形态
            "<img src=\"\" onclick=\"go()\">",
        ).forEach { assertSameMatches(oracle, AppPattern.imgPattern, it) }
    }

    @Test
    fun `titleNumPattern 章节序号`() {
        val oracle = Pattern.compile("(第)(.+?)(章)")
        listOf(
            "第一百二十三章 风起",
            "第3章",
            "序章无匹配",
            "第1章第2章", // 多匹配
        ).forEach { assertSameMatches(oracle, AppPattern.titleNumPattern, it) }
    }
}
