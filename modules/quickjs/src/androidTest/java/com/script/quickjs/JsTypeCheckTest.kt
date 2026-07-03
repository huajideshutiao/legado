package com.script.quickjs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/**
 * 探测本项目 QuickJS bridge 上各种类型判断方法的实际行为。
 * 不做 assert, 目的是把每种候选写法的返回值/异常打到 logcat, 由人肉眼校对。
 */
@RunWith(AndroidJUnit4::class)
class JsTypeCheckTest {

    private val probeJs = """
        var r = {};
        r['typeof'] = typeof result;
        r['Array_isArray'] = Array.isArray(result);
        r['length_typeof'] = (typeof result.length);
        r['length_value'] = (typeof result.length === 'number') ? result.length : null;

        try { r['isInstance_IS'] = java.io.InputStream.isInstance(result); }
        catch(e) { r['isInstance_IS'] = 'ERR:' + e; }

        try { r['instanceof_IS'] = (result instanceof java.io.InputStream); }
        catch(e) { r['instanceof_IS'] = 'ERR:' + e; }

        try { r['getName'] = result.getClass().getName(); }
        catch(e) { r['getName'] = 'ERR:' + e; }
        try { r['getSimpleName'] = result.getClass().getSimpleName(); }
        catch(e) { r['getSimpleName'] = 'ERR:' + e; }
        try { r['isArrayMethod'] = result.getClass().isArray(); }
        catch(e) { r['isArrayMethod'] = 'ERR:' + e; }

        try { r['name'] = result.getClass().name; }
        catch(e) { r['name'] = 'ERR:' + e; }
        try { r['simpleName'] = result.getClass().simpleName; }
        catch(e) { r['simpleName'] = 'ERR:' + e; }
        try { r['isArrayProp'] = result.getClass().isArray; }
        catch(e) { r['isArrayProp'] = 'ERR:' + e; }

        try { r['assignable'] = java.io.InputStream.isAssignableFrom(result.getClass()); }
        catch(e) { r['assignable'] = 'ERR:' + e; }

        try { r['objToString'] = Object.prototype.toString.call(result); }
        catch(e) { r['objToString'] = 'ERR:' + e; }

        JSON.stringify(r);
    """.trimIndent()

    @Test
    fun probeOnByteArray() {
        val json = QuickJsEngine.eval(probeJs) {
            put("result", byteArrayOf(1, 2, 3, 4, 5))
        }
        println("=== byte[] ===")
        println(json)
    }

    @Test
    fun probeOnInputStream() {
        val json = QuickJsEngine.eval(probeJs) {
            put("result", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }
        println("=== InputStream ===")
        println(json)
    }
}
