package io.legado.app.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AnyMapSerializer] 行为锁: 复刻原 Gson MapDeserializerDoubleAsIntFix 语义,
 * 守护 Map<String, Any?> 手写解析的字段类型策略与边界情况。
 *
 * 加字段不更新 serializer 的守护经 [decodeAnyMapOrNull] 路径全覆盖:
 * - null / Boolean / Number(Long/Double) / String / Map / List 全类型往返
 * - 数字策略: 整值 Double→Long (复刻 MapDeserializerDoubleAsIntFix: ceil(toDouble)==toLong 即归 Long,
 *   故 "0.0"/"1.0"/"3.0" 等小数格式的整值同样归 Long)
 * - 保序 (LinkedHashMap)
 * - 容错降级: 非法 JSON 返回 null
 */
class KsJsonAnyMapTest {

    @Test
    fun `数字策略 整值归 Long 小数归 Double`() {
        // 对齐原版 MapDeserializerDoubleAsIntFix: 数字先取 double, toLong 截断后
        // ceil(double) == long 视为整值 → Long, 否则 Double;
        // 故整数格式 ("3"/"10") 与小数格式的整值 ("3.0"/"0.0") 都归 Long, 3.5 归 Double
        val json = """{"int":3,"double":3.5,"longNum":10,"zero":0.0}"""
        val map = decodeAnyMapOrNull(json)!!
        assertEquals(3L, map["int"])
        assertTrue(map["double"] is Double, "3.5 保留 Double")
        assertEquals(3.5, map["double"])
        assertEquals(10L, map["longNum"])
        // "0.0" 小数格式但为整值 → 归 Long (对齐原版 MapDeserializerDoubleAsIntFix)
        assertEquals(0L, map["zero"])
    }

    @Test
    fun `全类型往返保持类型语义`() {
        val original = linkedMapOf<String, Any?>(
            "nullVal" to null,
            "bool" to true,
            "falseBool" to false,
            "long" to 42L,
            "double" to 3.14,
            "string" to "hello",
            "nestedMap" to linkedMapOf<String, Any?>("k" to "v", "n" to 1L),
            "list" to listOf<Any?>(1L, "x", null, true)
        )
        val json = KS_JSON.encodeToString(AnyMapSerializer, original)
        val restored = KS_JSON.decodeFromString(AnyMapSerializer, json)

        // 类型断言 (AnyMapSerializer 保证类型语义)
        assertNull(restored["nullVal"])
        assertEquals(true, restored["bool"])
        assertEquals(false, restored["falseBool"])
        assertEquals(42L, restored["long"])
        assertTrue(restored["double"] is Double, "double 保留 Double")
        assertEquals(3.14, restored["double"])
        assertEquals("hello", restored["string"])

        @Suppress("UNCHECKED_CAST")
        val nestedMap = restored["nestedMap"] as Map<String, Any?>
        assertEquals("v", nestedMap["k"])
        assertEquals(1L, nestedMap["n"])

        @Suppress("UNCHECKED_CAST")
        val list = restored["list"] as List<Any?>
        assertEquals(1L, list[0])
        assertEquals("x", list[1])
        assertNull(list[2])
        assertEquals(true, list[3])
    }

    @Test
    fun `Map 保序 LinkedHashMap 语义`() {
        val original = linkedMapOf<String, Any?>(
            "z" to 1, "a" to 2, "m" to 3, "b" to 4, "y" to 5
        )
        val json = KS_JSON.encodeToString(AnyMapSerializer, original)
        val restored = KS_JSON.decodeFromString(AnyMapSerializer, json)
        // 保序: key 顺序与原始一致 (对应原 Gson LinkedTreeMap)
        assertEquals(listOf("z", "a", "m", "b", "y"), restored.keys.toList())
    }

    @Test
    fun `嵌套结构往返保持 Long Double 策略`() {
        val json = """{"outer":{"inner":1.0,"list":[1.0,2.5,10]}}"""
        val map = decodeAnyMapOrNull(json)!!
        @Suppress("UNCHECKED_CAST")
        val outer = map["outer"] as Map<String, Any?>
        // "1.0" 小数格式但为整值 → 归 Long (对齐原版 MapDeserializerDoubleAsIntFix)
        assertEquals(1L, outer["inner"])
        @Suppress("UNCHECKED_CAST")
        val list = outer["list"] as List<Any?>
        // 1.0 → Long, 2.5 → Double, 10 → Long
        assertEquals(listOf(1L, 2.5, 10L), list)
    }

    @Test
    fun `JsonNull 值往返`() {
        val json = """{"a":null,"b":1}"""
        val map = decodeAnyMapOrNull(json)!!
        assertNull(map["a"])
        assertEquals(1L, map["b"])
    }

    @Test
    fun `空 Map 往返`() {
        val original = emptyMap<String, Any?>()
        val json = KS_JSON.encodeToString(AnyMapSerializer, original)
        val restored = KS_JSON.decodeFromString(AnyMapSerializer, json)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `非 JsonObject 输入返回空 Map`() {
        // 非 JsonObject (如 JsonArray) → emptyMap (toObjectMap 的 else 分支)
        val restored = KS_JSON.decodeFromString(AnyMapSerializer, """[1,2,3]""")
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `容错降级 空字符串与非法 JSON 返回 null`() {
        assertNull(decodeAnyMapOrNull(null))
        assertNull(decodeAnyMapOrNull(""))
        assertNull(decodeAnyMapOrNull("not a json"))
    }

    @Test
    fun `字符串值带 boolean 与 number 内容`() {
        val json = """{"t":true,"f":false,"n":123,"s":"text"}"""
        val map = decodeAnyMapOrNull(json)!!
        assertEquals(true, map["t"])
        assertEquals(false, map["f"])
        assertEquals(123L, map["n"])
        assertEquals("text", map["s"])
    }

    @Test
    fun `数字字符串不被解析为数字 保持字符串类型`() {
        // JsonPrimitive("123") isString=true → 返回 String "123" (不是 Long)
        val json = """{"s":"123"}"""
        val map = decodeAnyMapOrNull(json)!!
        assertEquals("123", map["s"])
    }

    @Test
    fun `valueToJsonElement 未知类型 fallback toString`() {
        // 未知对象 (非 Map/List/Number/String/Boolean/Null) → toString
        val original = linkedMapOf<String, Any?>("obj" to object {
            override fun toString() = "customObj"
        })
        val json = KS_JSON.encodeToString(AnyMapSerializer, original)
        val restored = KS_JSON.decodeFromString(AnyMapSerializer, json)
        // 未知对象经 toString → JsonPrimitive("customObj") → String
        assertEquals("customObj", restored["obj"])
    }

    @Test
    fun `AnyMapSerializer descriptor 标识符不变`() {
        // 守护 descriptor 名字 (防止重命名后破坏序列化兼容)
        assertEquals(
            "io.legado.app.utils.AnyMapSerializer",
            AnyMapSerializer.descriptor.serialName
        )
    }

    @Test
    fun `往返 JSON 字符串可被 AnyMapSerializer 双向解析`() {
        val original = linkedMapOf<String, Any?>(
            "k1" to "v1", "k2" to 100L, "k3" to true
        )
        val json = KS_JSON.encodeToString(AnyMapSerializer, original)
        // 再用 decodeAnyMapOrNull 解析 (走 KS_JSON 宽松路径)
        val restored = decodeAnyMapOrNull(json)
        assertNotNull(restored)
        assertEquals("v1", restored["k1"])
        assertEquals(100L, restored["k2"])
        assertEquals(true, restored["k3"])
    }
}
