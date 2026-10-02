package io.legado.app.ui.compose.component.code

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonPathChainTest {

    private val root: JsonElement = buildJsonObject {
        put("title", "hello")
        put(
            "data",
            buildJsonObject {
                put(
                    "list",
                    buildJsonArray {
                        add(buildJsonObject { put("id", 1) })
                        add(buildJsonObject { put("id", 2) })
                    },
                )
            },
        )
    }

    @Test
    fun `split json path handles indices and quoted keys`() {
        assertEquals(listOf("data", "list[0]"), splitJsonPath("$.data.list[0]"))
        assertEquals(listOf("data", "list[0][1]"), splitJsonPath("$.data.list[0][1]"))
        assertEquals(listOf("['a.b']", "c"), splitJsonPath("$['a.b'].c"))
        assertEquals(listOf("[0]", "x"), splitJsonPath("$[0].x"))
        assertEquals(emptyList<String>(), splitJsonPath("$"))
    }

    @Test
    fun `build chain walks every prefix level`() {
        val chain = buildJsonPathChain(root, listOf("data", "list[1]"))!!

        assertEquals(2, chain.size)
        // 第一级: data 对象
        assertEquals("$.data", chain[0].path)
        assertEquals("data", chain[0].key)
        assertEquals(true, chain[0].subtreeElement is JsonObject)
        // 第二级: list[1] 对象
        assertEquals("$.data.list[1]", chain[1].path)
        assertEquals(true, chain[1].subtreeElement is JsonObject)
    }

    @Test
    fun `primitive leaf follows vendored value-focus semantics`() {
        val chain = buildJsonPathChain(root, listOf("title"))!!

        assertEquals(1, chain.size)
        // 顶层键: 路径即 $.title
        assertEquals("$.title", chain[0].path)
        assertEquals("hello", chain[0].value)
        assertNull(chain[0].subtreeElement)
        assertEquals("\"hello\"", chain[0].quotedValue)
    }

    @Test
    fun `missing path returns null`() {
        assertNull(buildJsonPathChain(root, listOf("data", "nope")))
        assertNull(buildJsonPathChain(root, listOf("data", "list[5]")))
        assertNull(buildJsonPathChain(null, listOf("data")))
    }

    @Test
    fun `join prefix matches vendored toJsonPath format`() {
        assertEquals("$", joinJsonPathPrefix(emptyList()))
        assertEquals("$.data", joinJsonPathPrefix(listOf("data")))
        assertEquals("$.data.list[0]", joinJsonPathPrefix(listOf("data", "list[0]")))
        // 根级下标/引号键段不加点 (对齐 vendored toJsonPath)
        assertEquals("$[0].x", joinJsonPathPrefix(listOf("[0]", "x")))
        assertEquals("$['a.b']", joinJsonPathPrefix(listOf("['a.b']")))
    }

    @Test
    fun `array root indexes resolve`() {
        val arrayRoot: JsonArray = buildJsonArray {
            add(buildJsonObject { put("x", 1) })
        }
        val chain = buildJsonPathChain(arrayRoot, listOf("[0]", "x"))!!

        // 根级下标段不加点 (对齐 vendored toJsonPath: "$[0]" 而非 "$.[0]")
        assertEquals("$[0]", chain[0].path)
        // 下标必须真正下钻: subtreeElement 是数组首元素, 不是整个根数组
        assertEquals(arrayRoot[0], chain[0].subtreeElement)
        assertEquals("$[0].x", chain[1].path)
        assertEquals("1", chain[1].value)
        assertNull(chain[1].subtreeElement)
    }

    @Test
    fun `multi-index segments drill into nested arrays`() {
        val arrayRoot: JsonArray = buildJsonArray {
            add(buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
        }
        val chain = buildJsonPathChain(arrayRoot, listOf("[0]", "[1]"))!!

        assertEquals("$[0][1]", chain[1].path)
        assertEquals("b", chain[1].value)
    }

    @Test
    fun `unparsable segments fail closed`() {
        // 非数字非引号的 [a]: parsePathSegment 不可解析, 返回 null (面包屑置灰), 绝不错焦
        assertNull(buildJsonPathChain(root, listOf("[a]")))
    }

    @Test
    fun `quoted key segment resolves only when the key exists`() {
        // 引号键段本身可解析 (返回键名), 返回 null 只因文档里没有这个键,
        // 与「段不可解析」是两回事, 故不能拿它充当 fail-closed 用例。
        val quoted = buildJsonObject { put("'", "quote-key-value") }
        assertEquals("quote-key-value", buildJsonPathChain(quoted, listOf("[\"'\"]"))!!.last().value)
        assertNull(buildJsonPathChain(root, listOf("[\"'\"]")))
    }
}
