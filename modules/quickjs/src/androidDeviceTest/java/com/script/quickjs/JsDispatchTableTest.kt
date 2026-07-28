package com.script.quickjs

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.script.jsdispatch.JsApi
import com.script.jsdispatch.JsDispatchRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * @JsApi 静态分派表语义锁定测试。
 *
 * [DispatchFixture] 经 kspAndroidDeviceTest 生成分派表; 每个场景用同一段 JS 分别在
 * 表开 (静态分派) / 表关 (纯反射, [JsDispatchRegistry.disableForTest]) 两条路径执行,
 * 断言结果与副作用逐位一致 —— 把"coercion/重载决策以现安卓反射行为为规范"锁进测试。
 *
 * 覆盖: 元数重载 / 同元数类型重载 / specificity 评分 (int vs Object, 整数值优先) /
 * JvmOverloads 缺省变体 / 属性读写 (getter-setter + 属性 trap) / Unit 方法 /
 * 方法体异常透传 / JSON 字符串→Map / JS 数组→ByteArray / vararg 保守退出落反射 /
 * SAM (JS function → 接口参数) 落反射。
 */
@RunWith(AndroidJUnit4::class)
class JsDispatchTableTest {

    /** JS 面 fixture, KSP 为其生成静态分派表。 */
    @JsApi
    @Suppress("unused")
    class DispatchFixture {

        var name: String = "init"
        var count: Int = 0
        val readOnly: String = "ro"
        var nullableStr: String? = null

        fun greet(): String = "hello"

        fun echo(s: String?): String = "s:$s"

        // 元数重载
        fun join(a: String): String = "1:$a"
        fun join(a: String, b: String): String = "2:$a$b"

        // 同元数类型重载
        fun fmt(i: Int): String = "int:$i"
        fun fmt(s: String): String = "str:$s"

        // specificity: int vs Object (对齐 String.valueOf(123) -> "123" 非 "123.0")
        fun pick(v: Int): String = "int:$v"
        fun pick(v: Any?): String = "any:$v"

        // ByteArray coercion (JS Array -> byte[])
        fun sumBytes(b: ByteArray): Int = b.sumOf { it.toInt() }

        // JSON 字符串 -> Map (书源 header 惯例)
        fun header(h: Map<String, String>): String =
            h.entries.joinToString(",") { "${it.key}=${it.value}" }

        fun listSize(l: List<Any?>): Int = l.size

        @JvmOverloads
        fun opt(a: String, b: Int = 7): String = "$a-$b"

        fun bump() {
            count++
        }

        fun boom(): String = throw IllegalStateException("boom!")

        // vararg: 表保守退出, 恒落反射
        fun sum(vararg xs: Int): Int = xs.sum()

        // SAM: JS function -> Runnable, 表 NO_MATCH 落反射包装
        fun runIt(r: Runnable): String {
            r.run()
            return "ran"
        }
    }

    @After
    fun restoreRegistry() {
        JsDispatchRegistry.enableForTest()
    }

    private fun evalWith(js: String, fixture: DispatchFixture): Result<Any?> =
        runCatching {
            QuickJsEngine.eval(js) {
                put("f", fixture)
                dangerousApi = true
            }
        }

    /** 同一 JS 表开/表关各跑一遍, 断言结果 (或异常) 与 fixture 副作用一致, 返回表开结果。 */
    private fun assertBothPathsEqual(js: String): Any? {
        JsDispatchRegistry.enableForTest()
        val fixtureOn = DispatchFixture()
        val on = evalWith(js, fixtureOn)

        JsDispatchRegistry.disableForTest()
        val fixtureOff = DispatchFixture()
        val off = evalWith(js, fixtureOff)
        JsDispatchRegistry.enableForTest()

        assertEquals("成功/失败分野需一致: $js", off.isSuccess, on.isSuccess)
        if (on.isSuccess) {
            assertEquals("返回值需一致: $js", off.getOrNull(), on.getOrNull())
        } else {
            assertEquals(
                "异常类型需一致: $js",
                off.exceptionOrNull()!!.javaClass,
                on.exceptionOrNull()!!.javaClass
            )
        }
        assertEquals("count 副作用需一致: $js", fixtureOff.count, fixtureOn.count)
        assertEquals("name 副作用需一致: $js", fixtureOff.name, fixtureOn.name)
        return on.getOrNull()
    }

    // ============ 表确实生效 (非全程反射) ============

    @Test
    fun testDispatcherRegisteredForFixture() {
        JsDispatchRegistry.enableForTest()
        val d = JsDispatchRegistry.forClass(DispatchFixture::class.java)
        assertNotNull("kspAndroidDeviceTest 应已生成并注册 DispatchFixture 的分派表", d)
        assertTrue(d!!.hasMethod("greet"))
        assertTrue(d.hasMethod("getName"))   // 属性 getter JVM 名
        assertTrue(d.hasMethod("sum"))       // vararg 在 hasMethod 名单 (call 表外)
        // call 表直连 (不经 JS)
        assertEquals("hello", d.call(DispatchFixture(), "greet", emptyArray()))
        assertEquals(
            JsDispatchRegistry.NO_MATCH,
            d.call(DispatchFixture(), "sum", arrayOf(1, 2))  // vararg 保守退出
        )
    }

    // ============ 语义锁定: 表开/表关行为一致 ============

    @Test
    fun testSimpleCall() {
        assertEquals("hello", assertBothPathsEqual("f.greet();"))
    }

    @Test
    fun testNullableStringParam() {
        assertEquals("s:x", assertBothPathsEqual("f.echo('x');"))
        assertEquals("s:null", assertBothPathsEqual("f.echo(null);"))
        // 反射 coerceValue 兜底 toString: 数字参数 -> 字符串
        assertBothPathsEqual("f.echo(12);")
    }

    @Test
    fun testArityOverloads() {
        assertEquals("1:a", assertBothPathsEqual("f.join('a');"))
        assertEquals("2:ab", assertBothPathsEqual("f.join('a','b');"))
    }

    @Test
    fun testTypeOverloadsSameArity() {
        // JS 数字经引擎是整数值 → fmt(int) 胜 (specificity 2 < ...)
        assertEquals("int:3", assertBothPathsEqual("f.fmt(3);"))
        assertEquals("str:x", assertBothPathsEqual("f.fmt('x');"))
    }

    @Test
    fun testSpecificityIntBeatsObject() {
        // 对齐 rhino: 整数值 Number 优先 int (2) 而非 Object (10)
        assertEquals("int:3", assertBothPathsEqual("f.pick(3);"))
        // 小数值: int 4 仍 < Object 10 → int (截断), 与反射一致
        assertEquals("int:3", assertBothPathsEqual("f.pick(3.5);"))
        // null: int 不兼容 → Object
        assertEquals("any:null", assertBothPathsEqual("f.pick(null);"))
        assertEquals("any:x", assertBothPathsEqual("f.pick('x');"))
    }

    @Test
    fun testJsArrayToByteArray() {
        assertEquals(6, (assertBothPathsEqual("f.sumBytes([1,2,3]);") as Number).toInt())
    }

    @Test
    fun testJsonStringToMapParam() {
        assertEquals("a=1,b=2", assertBothPathsEqual("""f.header('{"a":"1","b":"2"}');"""))
        assertEquals("a=1", assertBothPathsEqual("f.header({a:'1'});"))
        // 非 JSON 字符串: 两条路径都应抛 (argument type mismatch)
        assertBothPathsEqual("f.header('not-json');")
    }

    @Test
    fun testListParam() {
        assertEquals(3, (assertBothPathsEqual("f.listSize([1,'x',null]);") as Number).toInt())
    }

    @Test
    fun testJvmOverloadsDefaultVariant() {
        assertEquals("a-7", assertBothPathsEqual("f.opt('a');"))
        assertEquals("a-3", assertBothPathsEqual("f.opt('a',3);"))
    }

    @Test
    fun testUnitMethodSideEffect() {
        assertBothPathsEqual("f.bump(); f.bump(); f.count;")
    }

    @Test
    fun testPropertyReadWrite() {
        assertEquals("init", assertBothPathsEqual("f.name;"))
        assertEquals("changed", assertBothPathsEqual("f.name = 'changed'; f.name;"))
        assertEquals("ro", assertBothPathsEqual("f.readOnly;"))
        assertBothPathsEqual("f.count = 5; f.count;")
        // getter JVM 名直调
        assertEquals("init", assertBothPathsEqual("f.getName();"))
        assertBothPathsEqual("f.setName('via-setter'); f.name;")
    }

    @Test
    fun testMethodBodyExceptionPropagates() {
        // 方法体异常两条路径都应传到 JS catch, 消息一致
        val js =
            "try { f.boom(); 'no-throw' } catch (e) { 'caught:' + String(e).indexOf('boom') >= 0 }"
        assertBothPathsEqual(js)
        assertBothPathsEqual("f.boom();")  // 未 catch: 两路径都抛
    }

    @Test
    fun testVarargFallsBackToReflection() {
        // vararg 整名保守退出 → 反射路径, 结果仍正确且两路径一致
        assertEquals(6, (assertBothPathsEqual("f.sum(1,2,3);") as Number).toInt())
    }

    @Test
    fun testSamInterfaceFallsBackToReflection() {
        // JS function → Runnable: 表 precheck NO_MATCH → 反射 SAM 包装, 回调同步执行
        assertBothPathsEqual("var hit = 0; f.runIt(function(){ hit++; }); hit;")
        assertEquals("ran", assertBothPathsEqual("f.runIt(function(){});"))
    }

    @Test
    fun testMethodMarkerViaPropertyTrap() {
        // f.greet 属性访问 → hasMethod → METHOD_MARKER → callable
        assertEquals("hello", assertBothPathsEqual("var g = f.greet; f.greet();"))
    }

    @Test
    fun testUnknownMemberBehaviorUnchanged() {
        // 未知方法/属性: miss 落反射后的既有语义不变
        assertBothPathsEqual("f.noSuchMethod && f.noSuchMethod(); f.noSuchProp;")
        assertBothPathsEqual("f.noSuchMethod();")
    }
}
