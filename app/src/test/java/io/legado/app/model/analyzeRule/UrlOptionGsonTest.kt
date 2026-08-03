package io.legado.app.model.analyzeRule

import io.legado.app.utils.KS_JSON
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UrlOption 自定义 KSerializer (UrlOptionSerializer) 行为锁:
 * 复刻原 Gson jsonDeserializer/jsonSerializer 语义, 覆盖 序列化(空值省略/webView 仅 true 输出/body JSON 还原)
 * 与反序列化(flex 取值)往返。
 *
 * 原 UrlOptionGsonTest 用 GSON.toJson/fromJson + registerAppGsonAdapters,
 * 现迁移到 KS_JSON + UrlOptionSerializer (kotlinx-serialization)。
 */
class UrlOptionGsonTest {

    private val json: Json = KS_JSON

    @Test
    fun `序列化 省略空值 webView 仅 true 输出`() {
        val opt = AnalyzeUrlCore.UrlOption().apply {
            method = "POST"
            retry = 3
            useWebView = false
        }
        val jsonStr = json.encodeToString(UrlOptionSerializer, opt)
        assertTrue(jsonStr.contains("\"method\""))
        assertTrue(jsonStr.contains("\"retry\""))
        // useWebView=false 不应输出 webView 键
        assertTrue("false 不输出 webView", !jsonStr.contains("webView"))
        // 空字段不输出
        assertTrue("空 charset 不输出", !jsonStr.contains("charset"))
    }

    @Test
    fun `反序列化 flex 取值与 blank 归 null`() {
        val jsonStr = """{"method":"GET","charset":"","retry":"5","webView":"true"}"""
        val opt = json.decodeFromString(UrlOptionSerializer, jsonStr)
        assertEquals("GET", opt.method)
        assertNull("空串 charset 归 null", opt.charset)
        assertEquals(5, opt.retry) // 字符串数字被 flexNumber 解析
        assertTrue("webView 字符串 true 解析为 true", opt.useWebView)
    }

    @Test
    fun `body JSON 内容序列化还原为容器 往返一致`() {
        val opt = AnalyzeUrlCore.UrlOption().apply {
            body = """{"k":"v"}"""
        }
        val jsonStr = json.encodeToString(UrlOptionSerializer, opt)
        // body 可解析为 JSON 对象时还原为嵌套结构(非字符串): 序列化后不应含转义引号包裹的字符串体
        assertTrue("body 还原为嵌套对象, 非字符串", !jsonStr.contains("\"body\":\"") && !jsonStr.contains("\"body\": \""))
        val back = json.decodeFromString(UrlOptionSerializer, jsonStr)
        assertEquals("""{"k":"v"}""", back.body?.replace(" ", "")?.replace("\n", ""))
    }

    @Test
    fun `headers Map 往返保留键值`() {
        val opt = AnalyzeUrlCore.UrlOption().apply {
            headers = mapOf("User-Agent" to "legado")
        }
        val jsonStr = json.encodeToString(UrlOptionSerializer, opt)
        val back = json.decodeFromString(UrlOptionSerializer, jsonStr)
        assertEquals("legado", back.headers?.get("User-Agent"))
    }
}
