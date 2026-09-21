package io.legado.app.model.analyzeRule

import io.legado.app.utils.KS_JSON
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [UrlOptionSerializer] 行为锁: 守护 [AnalyzeUrlCore.UrlOption] 的手写 KSerializer,
 * 复刻原 Gson jsonDeserializer/jsonSerializer + flexString/flexNumber/flexBool 语义。
 *
 * 守护要点:
 * - setter 冲突: 字符串字段空串归 null (field = value?.ifBlank { null })
 * - 宽松解析: flexNumber 接受 number/string, flexBool 接受 bool/string/对象
 * - body 还原: 可解析为 JSON 容器时还原为嵌套结构
 * - headers 双形态: JsonObject 或字符串内嵌 JsonObject
 * - 字段数不变: 12 个字段, 加字段不更新 serializer 会失败
 */
class UrlOptionSerializerTest {

    @Test
    fun `字段数不变保障`() {
        // 守护 UrlOption 字段数, 防止加字段不更新 UrlOptionSerializer
        // 当前 12 字段: method/charset/origin/type/webJs/js/retry/serverID/webViewDelayTime/body/headers/useWebView
        val expectedFieldCount = 12
        val actualFieldCount = AnalyzeUrlCore.UrlOption::class.memberProperties.size
        assertEquals(
            expectedFieldCount, actualFieldCount,
            "UrlOption 字段数变了 (expected=$expectedFieldCount, actual=$actualFieldCount), " +
                "请检查 UrlOptionSerializer 是否需要更新 (新增字段的 serialize/deserialize 分支)"
        )
    }

    @Test
    fun `全字段往返 保持所有字段值`() {
        val original = AnalyzeUrlCore.UrlOption().apply {
            method = "POST"
            charset = "UTF-8"
            origin = "https://example.com"
            type = "UTF-8"
            webJs = "console.log('webJs')"
            js = "result.url"
            retry = 3
            serverID = 123456789L
            webViewDelayTime = 5000L
            useWebView = true
            body = """{"key":"value"}"""
            headers = linkedMapOf("User-Agent" to "Test", "X-Int" to 100L)
        }
        val json = KS_JSON.encodeToString(UrlOptionSerializer, original)
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)

        assertEquals("POST", restored.method)
        assertEquals("UTF-8", restored.charset)
        assertEquals("https://example.com", restored.origin)
        assertEquals("UTF-8", restored.type)
        assertEquals("console.log('webJs')", restored.webJs)
        assertEquals("result.url", restored.js)
        assertEquals(3, restored.retry)
        assertEquals(123456789L, restored.serverID)
        assertEquals(5000L, restored.webViewDelayTime)
        assertTrue(restored.useWebView)
        // body 是 JSON 对象字符串, 序列化时还原为嵌套对象, 反序列化时 element.toString()
        assertEquals("""{"key":"value"}""", restored.body)
        // headers 经 AnyMapSerializer 往返, 数字策略 Long
        @Suppress("UNCHECKED_CAST")
        val restoredHeaders = restored.headers as Map<String, Any?>
        assertEquals("Test", restoredHeaders["User-Agent"])
        assertEquals(100L, restoredHeaders["X-Int"])
    }

    @Test
    fun `空 UrlOption 序列化输出空 JsonObject`() {
        val original = AnalyzeUrlCore.UrlOption()
        val json = KS_JSON.encodeToString(UrlOptionSerializer, original)
        // 所有字段为 null 或默认 false, 序列化时空 JsonObject
        assertEquals("{}", json)
    }

    @Test
    fun `空 UrlOption 反序列化回默认值`() {
        val json = "{}"
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        assertNull(restored.method)
        assertNull(restored.charset)
        assertNull(restored.origin)
        assertNull(restored.type)
        assertNull(restored.webJs)
        assertNull(restored.js)
        assertNull(restored.retry)
        assertNull(restored.serverID)
        assertNull(restored.webViewDelayTime)
        assertFalse(restored.useWebView)
        assertNull(restored.body)
        assertNull(restored.headers)
    }

    @Test
    fun `setter 冲突 空字符串归 null`() {
        // UrlOption 字符串字段 setter: field = value?.ifBlank { null }
        // 反序列化时 flexString 空串也归 null, 双重保障
        val json = """{"method":"","charset":"","origin":"","type":"","webJs":"","js":"","body":""}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        assertNull(restored.method, "method 空串应归 null")
        assertNull(restored.charset, "charset 空串应归 null")
        assertNull(restored.origin, "origin 空串应归 null")
        assertNull(restored.type, "type 空串应归 null")
        assertNull(restored.webJs, "webJs 空串应归 null")
        assertNull(restored.js, "js 空串应归 null")
        assertNull(restored.body, "body 空串应归 null")
    }

    @Test
    fun `flexNumber 数字字符串兼容`() {
        // retry/serverID/webViewDelayTime 接受 number 或 string(数字字符串)
        val json = """{"retry":"3","serverID":"123","webViewDelayTime":"5000"}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        assertEquals(3, restored.retry)
        assertEquals(123L, restored.serverID)
        assertEquals(5000L, restored.webViewDelayTime)
    }

    @Test
    fun `flexNumber 数字类型兼容`() {
        val json = """{"retry":3,"serverID":123,"webViewDelayTime":5000}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        assertEquals(3, restored.retry)
        assertEquals(123L, restored.serverID)
        assertEquals(5000L, restored.webViewDelayTime)
    }

    @Test
    fun `flexBool 字符串形态 空与 false 视为 false`() {
        // webView 字段: "" / "false" → false, 其他字符串 → true
        val jsonFalse1 = """{"webView":""}"""
        val restored1 = KS_JSON.decodeFromString(UrlOptionSerializer, jsonFalse1)
        assertFalse(restored1.useWebView, "webView='' 应为 false")

        val jsonFalse2 = """{"webView":"false"}"""
        val restored2 = KS_JSON.decodeFromString(UrlOptionSerializer, jsonFalse2)
        assertFalse(restored2.useWebView, "webView='false' 应为 false")
    }

    @Test
    fun `flexBool 字符串形态 非空非 false 视为 true`() {
        val json = """{"webView":"true"}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        assertTrue(restored.useWebView, "webView='true' 应为 true")

        val json2 = """{"webView":"anyString"}"""
        val restored2 = KS_JSON.decodeFromString(UrlOptionSerializer, json2)
        assertTrue(restored2.useWebView, "webView='anyString' 应为 true (对齐原 Gson else -> true)")
    }

    @Test
    fun `flexBool boolean 形态`() {
        val json = """{"webView":true}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        assertTrue(restored.useWebView)

        val json2 = """{"webView":false}"""
        val restored2 = KS_JSON.decodeFromString(UrlOptionSerializer, json2)
        assertFalse(restored2.useWebView)
    }

    @Test
    fun `useWebView 仅 true 时输出`() {
        // 对齐原 Gson jsonSerializer: useWebView 仅 true 时输出 (默认 false 不输出)
        val trueOpt = AnalyzeUrlCore.UrlOption().apply { useWebView = true }
        val trueJson = KS_JSON.encodeToString(UrlOptionSerializer, trueOpt)
        assertTrue(trueJson.contains("\"webView\":true"), "useWebView=true 应输出 webView 字段")

        val falseOpt = AnalyzeUrlCore.UrlOption().apply { useWebView = false }
        val falseJson = KS_JSON.encodeToString(UrlOptionSerializer, falseOpt)
        assertFalse(falseJson.contains("webView"), "useWebView=false 不应输出 webView 字段")
    }

    @Test
    fun `body 还原为 JSON 容器 对象`() {
        // body 可解析为 JsonObject 时, 序列化还原为嵌套对象 (不是字符串)
        val opt = AnalyzeUrlCore.UrlOption().apply {
            body = """{"k":"v","n":1}"""
        }
        val json = KS_JSON.encodeToString(UrlOptionSerializer, opt)
        // body 应为嵌套 JsonObject, 不是字符串
        assertTrue(json.contains("\"body\":{\"k\":\"v\",\"n\":1}"), "body 应还原为嵌套对象: $json")
        assertFalse(json.contains("\"body\":\"{\\\""), "body 不应是转义字符串: $json")
    }

    @Test
    fun `body 还原为 JSON 容器 数组`() {
        val opt = AnalyzeUrlCore.UrlOption().apply {
            body = """[1,2,3]"""
        }
        val json = KS_JSON.encodeToString(UrlOptionSerializer, opt)
        assertTrue(json.contains("\"body\":[1,2,3]"), "body 应还原为嵌套数组: $json")
    }

    @Test
    fun `body 非合法 JSON 保持字符串`() {
        val opt = AnalyzeUrlCore.UrlOption().apply {
            body = "plain text body"
        }
        val json = KS_JSON.encodeToString(UrlOptionSerializer, opt)
        // 非合法 JSON → 保持字符串
        assertTrue(json.contains("\"body\":\"plain text body\""), "body 非合法 JSON 应保持字符串: $json")
    }

    @Test
    fun `body 反序列化 JsonNull 归 null`() {
        val json = """{"body":null}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        assertNull(restored.body)
    }

    @Test
    fun `body 反序列化 JsonObject 返回 toString`() {
        val json = """{"body":{"k":"v"}}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        // JsonObject → element.toString() (对应原 Gson el.toString())
        assertEquals("""{"k":"v"}""", restored.body)
    }

    @Test
    fun `body 反序列化 JsonArray 返回 toString`() {
        val json = """{"body":[1,2,3]}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        assertEquals("[1,2,3]", restored.body)
    }

    @Test
    fun `headers JsonObject 形态 解析为 Map`() {
        val json = """{"headers":{"User-Agent":"Test","X-Int":100}}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        @Suppress("UNCHECKED_CAST")
        val headers = restored.headers as Map<String, Any?>
        assertEquals("Test", headers["User-Agent"])
        // AnyMapSerializer 数字策略: 100 → Long
        assertEquals(100L, headers["X-Int"])
    }

    @Test
    fun `headers 字符串内嵌 JsonObject 形态`() {
        // JsonPrimitive(string) → parse 字符串, 若为 JsonObject 则解析
        val json = """{"headers":"{\"User-Agent\":\"Test\"}"}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        @Suppress("UNCHECKED_CAST")
        val headers = restored.headers as Map<String, Any?>
        assertEquals("Test", headers["User-Agent"])
    }

    @Test
    fun `headers 字符串非 JsonObject 形态 返回 null`() {
        // 字符串 parse 后非 JsonObject (如 JsonArray) → null
        val json = """{"headers":"[1,2,3]"}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        assertNull(restored.headers, "headers 字符串非 JsonObject 应返回 null")
    }

    @Test
    fun `headers JsonNull 归 null`() {
        val json = """{"headers":null}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        assertNull(restored.headers)
    }

    @Test
    fun `headers JsonArray 形态 返回 null`() {
        // JsonArray 等不匹配 headers 形态, 返回 null (对应原 Gson else 分支)
        val json = """{"headers":[1,2,3]}"""
        val restored = KS_JSON.decodeFromString(UrlOptionSerializer, json)
        assertNull(restored.headers, "headers JsonArray 应返回 null")
    }

    @Test
    fun `字段名集合不变`() {
        // 守护序列化输出的字段名集合 (对应原 Gson jsonSerializer 字段名)
        val opt = AnalyzeUrlCore.UrlOption().apply {
            method = "GET"
            charset = "UTF-8"
            origin = "https://example.com"
            type = "UTF-8"
            webJs = "js"
            js = "js"
            retry = 1
            serverID = 1L
            webViewDelayTime = 1L
            useWebView = true
            body = "{}"
            headers = mapOf("k" to "v")
        }
        val json = KS_JSON.encodeToString(UrlOptionSerializer, opt)
        // 守护字段名: method/charset/origin/type/webJs/js/retry/serverID/webViewDelayTime/webView/body/headers
        listOf(
            "method", "charset", "origin", "type", "webJs", "js",
            "retry", "serverID", "webViewDelayTime", "webView", "body", "headers"
        ).forEach { field ->
            assertTrue(json.contains("\"$field\":"), "缺少字段 $field in JSON: $json")
        }
    }
}
