package io.legado.app.data.entities

import io.legado.app.utils.KS_JSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [RuleStringSerializer] 行为锁: 守护 String? 字段双形态存储的手写 KSerializer。
 *
 * 复刻原 Gson RuleStringAdapter:
 * - serialize: 空值/空串 → encodeNull; 非空 → parse 成 JsonElement 后原样 encode (输出嵌套对象形状)
 * - deserialize: JsonNull → null; JsonPrimitive → 字符串内容 (空串归 null); JsonObject/JsonArray → element.toString()
 *
 * 字段数断言: RuleStringSerializer 序列化 String? 无字段概念, 改用 descriptor.elementsCount 守护 descriptor 结构。
 */
class RuleStringSerializerTest {

    @Test
    fun `JSON 对象字符串 往返 保持嵌套对象形状`() {
        val original = """{"name":"test","value":123}"""
        val json = KS_JSON.encodeToString(RuleStringSerializer, original)
        // 序列化: parse 成 JsonObject 后原样 encode, 输出嵌套对象 (不是转义字符串)
        assertEquals(original, json)
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertEquals(original, restored)
    }

    @Test
    fun `JSON 数组字符串 往返 保持嵌套数组形状`() {
        val original = """[1,2,3]"""
        val json = KS_JSON.encodeToString(RuleStringSerializer, original)
        assertEquals(original, json)
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertEquals(original, restored)
    }

    @Test
    fun `null 往返`() {
        val json = KS_JSON.encodeToString(RuleStringSerializer, null)
        assertEquals("null", json)
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertNull(restored)
    }

    @Test
    fun `空字符串 序列化为 null`() {
        // 对应原 Gson write 的 nullValue 分支: 空串 → encodeNull
        val json = KS_JSON.encodeToString(RuleStringSerializer, "")
        assertEquals("null", json)
    }

    @Test
    fun `JsonNull 反序列化为 null`() {
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, "null")
        assertNull(restored)
    }

    @Test
    fun `JsonPrimitive 字符串 反序列化为字符串内容`() {
        val json = "\"hello world\""
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertEquals("hello world", restored)
    }

    @Test
    fun `JsonPrimitive 空字符串 反序列化为 null`() {
        // 复刻原 Gson read: STRING 分支 + takeIf { it.isNotEmpty() }
        val json = "\"\""
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertNull(restored, "空串应归 null")
    }

    @Test
    fun `JsonPrimitive 数字 反序列化为字符串内容`() {
        // 原 Gson read 的 else 分支对 NUMBER 的 parseReader+toString
        val json = "123"
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertEquals("123", restored)
    }

    @Test
    fun `JsonPrimitive 布尔 反序列化为字符串内容`() {
        // 原 Gson read 的 else 分支对 BOOLEAN 的 parseReader+toString
        val json = "true"
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertEquals("true", restored)
    }

    @Test
    fun `JsonObject 反序列化为 toString`() {
        // 对应原 Gson read 的 else 分支: element.toString()
        val json = """{"key":"value"}"""
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertEquals("""{"key":"value"}""", restored)
    }

    @Test
    fun `JsonArray 反序列化为 toString`() {
        val json = """[1,2,3]"""
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertEquals("[1,2,3]", restored)
    }

    @Test
    fun `嵌套 JSON 对象 反序列化为 toString`() {
        val json = """{"outer":{"inner":"v"}}"""
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertEquals("""{"outer":{"inner":"v"}}""", restored)
    }

    @Test
    fun `JSON 对象 含数字 Long 形态 往返`() {
        // 守护 JSON 字符串中的数字在往返后保持原样 (RuleStringSerializer 不做数字策略)
        val original = """{"count":42,"value":3.14}"""
        val json = KS_JSON.encodeToString(RuleStringSerializer, original)
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertEquals(original, restored)
    }

    @Test
    fun `JSON 对象 含 null 值 往返`() {
        val original = """{"a":null,"b":"x"}"""
        val json = KS_JSON.encodeToString(RuleStringSerializer, original)
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertEquals(original, restored)
    }

    @Test
    fun `JSON 对象 含嵌套数组 往返`() {
        val original = """{"list":[1,2,3],"name":"test"}"""
        val json = KS_JSON.encodeToString(RuleStringSerializer, original)
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertEquals(original, restored)
    }

    @Test
    fun `descriptor 标识符不变`() {
        // 守护 descriptor serialName (防止重命名后破坏序列化兼容)
        assertEquals(
            "io.legado.app.data.entities.RuleStringSerializer",
            RuleStringSerializer.descriptor.serialName
        )
    }

    @Test
    fun `descriptor elementsCount 为零 无字段结构`() {
        // RuleStringSerializer 序列化 String? 无字段概念, descriptor 用 buildClassSerialDescriptor
        // 构造, 不声明任何 element. 守护 descriptor 结构不变 (保持零字段).
        // 若有人误改成有字段的 descriptor, elementsCount 会变化, 测试失败.
        assertEquals(
            0, RuleStringSerializer.descriptor.elementsCount,
            "RuleStringSerializer descriptor.elementsCount 应为 0 (String? 无字段), " +
                "若变化请检查是否误改了 descriptor 构造方式"
        )
    }

    @Test
    fun `复杂 JSON 对象 全字段往返`() {
        // 模拟 BookSource 规则字段的实际形态 (嵌套对象 + 多类型值)
        val original = """{"ruleList":[{"name":"rule1"},{"name":"rule2"}],"count":10,"enabled":true}"""
        val json = KS_JSON.encodeToString(RuleStringSerializer, original)
        val restored = KS_JSON.decodeFromString(RuleStringSerializer, json)
        assertEquals(original, restored)
    }

    @Test
    fun `序列化非合法 JSON 字符串 抛异常`() {
        // serialize 会 parseToJsonElement, 非合法 JSON 会抛异常
        // 这是设计如此: RuleStringSerializer 假设 String 内容是合法 JSON (规则字段存储 JSON)
        var threw = false
        try {
            KS_JSON.encodeToString(RuleStringSerializer, "not a json")
        } catch (_: Exception) {
            threw = true
        }
        assertEquals(true, threw, "非合法 JSON 字符串 serialize 应抛异常")
    }
}
