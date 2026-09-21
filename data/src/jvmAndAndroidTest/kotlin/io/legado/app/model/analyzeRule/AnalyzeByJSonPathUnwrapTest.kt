package io.legado.app.model.analyzeRule

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSONPath 层保持 JsonElement，进入 JS 边界时再按 master/jayway 的值模型深度解包。
 */
class AnalyzeByJSonPathUnwrapTest {

    private val tagsJson = """{"tags":[{"name":"玄幻","id":1},{"name":"修真","id":2}]}"""

    @Test
    fun `getList 对象元素保持 JsonObject`() {
        val list = AnalyzeByJSonPath(tagsJson).getList("$.tags")
        assertEquals(2, list.size)
        assertTrue(list[0] is JsonObject)
    }

    @Test
    fun `getObject 五型保持 JsonElement`() {
        val json = """{"a":{"s":"文本","i":42,"f":3.14,"b":true,"n":null}}"""
        val obj = AnalyzeByJSonPath(json).getObject("$.a") as JsonObject
        assertEquals("文本", (obj["s"] as JsonPrimitive).content)
        assertEquals("42", (obj["i"] as JsonPrimitive).content)
        assertEquals("3.14", (obj["f"] as JsonPrimitive).content)
        assertEquals("true", (obj["b"] as JsonPrimitive).content)
        assertTrue(obj["n"] is JsonNull)
    }

    @Test
    fun `嵌套结构保持 JsonElement`() {
        val json = """{"book":{"chapters":[{"title":"第一章","extra":{"words":1000}}]}}"""
        val book = AnalyzeByJSonPath(json).getObject("$.book") as JsonObject
        val chapters = book["chapters"] as JsonArray
        val chapter = chapters[0] as JsonObject
        assertEquals("第一章", (chapter["title"] as JsonPrimitive).content)
        val extra = chapter["extra"] as JsonObject
        assertEquals("1000", (extra["words"] as JsonPrimitive).content)
    }

    @Test
    fun `getList 保序 JsonObject 键序与 JSON 一致`() {
        val json = """{"list":[{"z":1,"a":2,"m":3}]}"""
        val obj = AnalyzeByJSonPath(json).getList("$.list")[0] as JsonObject
        assertEquals(listOf("z", "a", "m"), obj.keys.toList())
    }

    @Test
    fun `JsonElement 可直接回流再次查询`() {
        val obj = AnalyzeByJSonPath(tagsJson).getObject("$.tags[0]")!!
        assertEquals("玄幻", AnalyzeByJSonPath(obj).getString("$.name"))
    }

    @Test
    fun `JsonElement 数组可直接回流再次查询`() {
        val list = AnalyzeByJSonPath(tagsJson).getObject("$.tags")!!
        assertTrue(list is List<*>)
        assertEquals(listOf("玄幻", "修真"), AnalyzeByJSonPath(list).getStringList("$[*].name"))
    }

    @Test
    fun `JsonNull 回流读取仍为 null 语义`() {
        val obj = AnalyzeByJSonPath("""{"a":{"n":null}}""").getObject("$.a")!!
        assertTrue(AnalyzeByJSonPath(obj).getObject("$.n") is JsonNull)
    }

    @Test
    fun `JsonElement JS 转换器深度解包 getList 结果`() {
        val json = """{"nums":[1,2.5,"x",true,null]}"""
        val list = AnalyzeByJSonPath(json).getList("$.nums")
        assertEquals(listOf<Any?>(1L, 2.5, "x", true, null), JsonElementJsConverter.convert(list))
    }

    @Test
    fun `JsonElement JS 转换器深度解包对象五型`() {
        val raw = AnalyzeByJSonPath("""{"a":{"s":"文本","i":42,"f":3.14,"b":true,"n":null}}""")
            .getObject("$.a")!!
        val obj = JsonElementJsConverter.convert(raw) as Map<*, *>
        assertEquals("文本", obj["s"])
        assertEquals(42L, obj["i"])
        assertEquals(3.14, obj["f"])
        assertEquals(true, obj["b"])
        assertTrue(obj.containsKey("n"))
        assertNull(obj["n"])
    }

    @Test
    fun `JsonElement JS 转换器不改变普通业务集合`() {
        val plain = arrayListOf<Any?>("x", 1L, linkedMapOf("ok" to true))
        assertTrue(JsonElementJsConverter.convert(plain) === plain)
    }

    @Test
    fun `不存在的路径 getString 返回空字符串`() {
        val analyzer = AnalyzeByJSonPath("""{"name":"玄幻"}""")
        assertEquals("", analyzer.getString("$.missing"))
        assertEquals("", analyzer.getString("$.missing.value"))
    }

    @Test
    fun `显式 JsonNull 文本入口返回空字符串`() {
        val analyzer = AnalyzeByJSonPath("""{"body":null,"text":"null"}""")
        assertEquals("", analyzer.getString("$.body"))
        assertEquals("null", analyzer.getString("$.text"))
    }

    @Test
    fun `不存在的路径经 AnalyzeRule 返回空字符串`() {
        val analyzer = AnalyzeRuleCore().setContent("""{"name":"玄幻"}""")
        assertEquals("", analyzer.getString("$.missing"))
    }

    @Test
    fun `显式 JsonNull 经内嵌规则返回空字符串`() {
        val analyzer = AnalyzeRuleCore().setContent("""{"body":null}""")
        assertEquals("", analyzer.getString("{{$.body}}"))
    }

    @Test
    fun `parse 带空白前缀的 JSON 文本仍正常解析`() {
        val parsed: JsonElement = AnalyzeByJSonPath.parse("  \n {\"name\":\"玄幻\"}")
        assertEquals("玄幻", AnalyzeByJSonPath(parsed).getString("$.name"))
    }

    @Test
    fun `HTML 响应误用 JSONPath 时按无匹配处理`() {
        val html = "<div id=\"comment-create-form-wrapper\">登录后发表评论</div>"
        val analyzer = AnalyzeByJSonPath(html)

        assertNull(analyzer.getString("$.comments"))
        assertTrue(analyzer.getStringList("$.comments").isEmpty())
        assertNull(analyzer.getObject("$.comments"))
        assertTrue(analyzer.getList("$.comments").isEmpty())

        // AnalyzeRuleCore 的显式/自动 JSONPath 入口也不应把 HTML 错误页抛到请求协程。
        val ruleAnalyzer = AnalyzeRuleCore().setContent(html)
        assertEquals("", ruleAnalyzer.getString("$.comments"))
        assertTrue(ruleAnalyzer.getElements("$.comments").isEmpty())
    }
}
