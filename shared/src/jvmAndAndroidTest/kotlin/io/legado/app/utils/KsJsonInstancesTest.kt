package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KS_JSON / AnyMapSerializer 行为锁: 复刻原 GsonInstancesTest 的语义验证。
 *
 * Phase D: 原 GsonInstancesTest 用 GSON.fromJson 验证 MapDeserializerDoubleAsIntFix
 * (整值 Double→Long) + INITIAL_GSON 数字策略 + GSONStrict 继承。
 * 现改为验证 [AnyMapSerializer] (复刻 MapDeserializerDoubleAsIntFix) 与
 * [KS_JSON]/[KS_JSON_STRICT] 的宽松/严格策略差异, 行为等价。
 */
class KsJsonInstancesTest {

    @Test
    fun `AnyMapSerializer 数字策略 整值 Double 修正为 Long`() {
        val json = """{"a":3.0,"b":3.5,"c":10,"d":"x","e":true}"""
        val map = decodeAnyMapOrNull(json)!!
        // 3.0 应被 AnyMapSerializer 归一为 Long, 非 Double (对应原 MapDeserializerDoubleAsIntFix)
        assertEquals(3L, map["a"])
        assertTrue("3.5 保留 Double", map["b"] is Double)
        assertEquals(3.5, map["b"])
        assertEquals(10L, map["c"])
        assertEquals("x", map["d"])
        assertEquals(true, map["e"])
    }

    @Test
    fun `AnyMapSerializer 嵌套结构 往返保持 Long Double 策略`() {
        val json = """{"outer":{"inner":1.0,"list":[1.0,2.5]}}"""
        val map = decodeAnyMapOrNull(json)!!
        @Suppress("UNCHECKED_CAST")
        val outer = map["outer"] as Map<String, Any?>
        assertEquals(1L, outer["inner"])
        @Suppress("UNCHECKED_CAST")
        val list = outer["list"] as List<Any?>
        // 对应原 GsonInstancesTest: listOf(1L, 2.5)
        assertEquals(listOf(1L, 2.5), list)
    }

    @Test
    fun `KS_JSON 宽松与 KS_JSON_STRICT 严格 差异`() {
        // 复刻原 GSONStrict 拒绝非法 JSON, GSON 宽松接受
        val lenient = "{a:1}" // 无引号 key
        // KS_JSON 宽松 (isLenient=true) 接受, KS_JSON_STRICT 严格 (isLenient=false) 拒绝
        // 用 String 类型解码验证 (Map<String, String> 在 KS_JSON 下能容错)
        val lenientOk = decodeStringMapOrNull(lenient)
        assertTrue("KS_JSON 宽松解析成功", lenientOk != null)
        val strictOk = try {
            KS_JSON_STRICT.decodeFromString<Map<String, String>>(lenient)
        } catch (_: Exception) {
            null
        }
        assertTrue("KS_JSON_STRICT 拒绝非法 JSON", strictOk == null)
    }

    @Test
    fun `decodeAnyMapOrNull 容错降级 空字符串返回 null`() {
        assertEquals(null, decodeAnyMapOrNull(null))
        assertEquals(null, decodeAnyMapOrNull(""))
        assertEquals(null, decodeAnyMapOrNull("not a json"))
    }
}
