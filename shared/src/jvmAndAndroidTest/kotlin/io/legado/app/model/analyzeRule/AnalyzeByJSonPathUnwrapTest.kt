package io.legado.app.model.analyzeRule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * getObject/getList 源头解包 JsonElement 的行为锁(jayway→RJPath 回归修复):
 * JS 侧属性访问需拿到裸 String/Long/Double/Boolean/null, 而非 JsonPrimitive
 * (其 toString 是 JSON 编码文本, 字符串会带双引号)。
 */
class AnalyzeByJSonPathUnwrapTest {

    private val tagsJson = """{"tags":[{"name":"玄幻","id":1},{"name":"修真","id":2}]}"""

    @Test
    fun `getList 对象元素为 Map 字符串属性无引号`() {
        val list = AnalyzeByJSonPath(tagsJson).getList("$.tags")
        assertEquals(2, list.size)
        val first = list[0]
        assertTrue("对象元素应解包为 Map", first is Map<*, *>)
        first as Map<*, *>
        //模拟 JS `i.name + '::' + ...` 字符串拼接: 裸 String 拼接不出引号
        val joined = "${first["name"]}::${first["id"]}"
        assertEquals("玄幻::1", joined)
    }

    @Test
    fun `五型 oracle 字符串 整数 浮点 布尔 null`() {
        val json = """{"a":{"s":"文本","i":42,"f":3.14,"b":true,"n":null}}"""
        val obj = AnalyzeByJSonPath(json).getObject("$.a")
        assertTrue(obj is Map<*, *>)
        obj as Map<*, *>
        assertEquals("文本", obj["s"])
        assertEquals(42L, obj["i"]) //整数对齐 LONG_OR_DOUBLE → Long
        assertEquals(3.14, obj["f"]) //浮点 → Double
        assertEquals(true, obj["b"])
        assertNull(obj["n"])
        assertTrue("键包含 n(值为 null)", obj.containsKey("n"))
    }

    @Test
    fun `嵌套 对象套数组套对象 深度解包`() {
        val json = """{"book":{"chapters":[{"title":"第一章","extra":{"words":1000}}]}}"""
        val book = AnalyzeByJSonPath(json).getObject("$.book")
        book as Map<*, *>
        val chapters = book["chapters"]
        assertTrue(chapters is List<*>)
        val chapter = (chapters as List<*>)[0] as Map<*, *>
        assertEquals("第一章", chapter["title"])
        val extra = chapter["extra"] as Map<*, *>
        assertEquals(1000L, extra["words"])
    }

    @Test
    fun `getList 保序 LinkedHashMap 键序与 JSON 一致`() {
        val json = """{"list":[{"z":1,"a":2,"m":3}]}"""
        val obj = AnalyzeByJSonPath(json).getList("$.list")[0] as Map<*, *>
        assertEquals(listOf("z", "a", "m"), obj.keys.toList())
    }

    @Test
    fun `回流 Map 经 parse 重建后可再次查询`() {
        //模拟 @js→$. 链: getObject 出的 Map 作为下一段规则的 content
        val map = AnalyzeByJSonPath(tagsJson).getObject("$.tags[0]")!!
        val name = AnalyzeByJSonPath(map).getString("$.name")
        assertEquals("玄幻", name)
    }

    @Test
    fun `回流 List 经 parse 重建后可再次查询`() {
        val list = AnalyzeByJSonPath(tagsJson).getObject("$.tags")!!
        assertTrue(list is List<*>)
        val names = AnalyzeByJSonPath(list).getStringList("$[*].name")
        assertEquals(listOf("玄幻", "修真"), names)
    }

    @Test
    fun `回流 五型值经重建保持类型`() {
        val obj = AnalyzeByJSonPath("""{"a":{"s":"文本","i":42,"f":3.14,"b":true,"n":null}}""")
            .getObject("$.a")!!
        val back = AnalyzeByJSonPath(obj)
        assertEquals("文本", back.getString("$.s"))
        assertEquals("42", back.getString("$.i"))
        assertEquals("3.14", back.getString("$.f"))
        assertEquals("true", back.getString("$.b"))
        val n = back.getObject("$.n")
        assertNull(n)
    }

    @Test
    fun `getList 数组直取展开 基本类型解包`() {
        val json = """{"nums":[1,2.5,"x",true,null]}"""
        val list = AnalyzeByJSonPath(json).getList("$.nums")
        assertEquals(listOf<Any?>(1L, 2.5, "x", true, null), list)
    }
}
