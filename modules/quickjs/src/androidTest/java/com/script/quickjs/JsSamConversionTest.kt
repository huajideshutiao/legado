package com.script.quickjs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SAM (Single Abstract Method) 自动转换测试。
 *
 * 验证 JS function 传给期望 SAM 接口 (Consumer/Function/Predicate 等) 的 Java 方法时,
 * 自动包装为该接口的 Proxy, 接口方法调用回调 JS function。
 * 对齐 rhino NativeJavaObject.coerceTypeImpl 的 FunctionAdapter 行为。
 *
 * 覆盖场景:
 * 1. ArrayList.forEach(Consumer) 基本场景 (复现用户报错)
 * 2. 用户完整报错代码复现 (result.tags.forEach + book.type 修改)
 * 3. Function 接口转换
 * 4. 异常传播 (JS function throw 不静默吞掉)
 */
@RunWith(AndroidJUnit4::class)
class JsSamConversionTest {

    // ============ 1. ArrayList.forEach(Consumer) 基本场景 ============

    /**
     * 复现用户报错: `ArrayList.forEach argument 1 has type Consumer, got java.lang.Long`。
     *
     * 修复前: JS 箭头函数经 toJavaObject 转成 Long 句柄, 传给 forEach(Consumer) 时
     *         isArgsCompatible 不匹配 → matched.first() 兜底 → method.invoke 抛
     *         IllegalArgumentException。
     * 修复后: coerceValue 检测到 targetType 是 SAM 接口 + value 是 Long 句柄,
     *         包装为 Consumer Proxy, forEach 调用 accept 时回调 JS function。
     */
    @Test
    fun testArrayListForEachWithJsFunction() {
        val list = arrayListOf("a", "b", "c")
        val sb = StringBuilder()
        QuickJsEngine.eval(
            """
            lst.forEach(e => { sb.append(e) })
            """
        ) {
            put("lst", list)
            put("sb", sb)
        }
        // JS 箭头函数被回调 3 次, sb 追加 "abc"
        assertEquals("abc", sb.toString())
    }

    /**
     * 验证 forEach 传入的参数是 List 元素 (Java 对象), JS 可访问其属性。
     */
    @Test
    fun testForEachWithJavaObjectElements() {
        val tags = arrayListOf(
            TagBean(1, "小说"),
            TagBean(2, "散文")
        )
        val collected = StringBuilder()
        QuickJsEngine.eval(
            """
            tags.forEach(t => {
                sb.append(t.id).append(':').append(t.name).append(';')
            })
            """
        ) {
            put("tags", tags)
            put("sb", collected)
        }
        assertEquals("1:小说;2:散文;", collected.toString())
    }

    // ============ 2. 用户完整报错代码复现 ============

    /**
     * 复现用户完整报错场景: result.tags.forEach + 字符串拼接 + book.type 修改。
     *
     * 用户 JS (简化版, 保留核心逻辑):
     *   let tmp = result.tags
     *   result = [result.newsType || result.mediaType]
     *   if (result[result.length-1] == "text")
     *       book.type = (book.type & ~2048) | 8
     *   tmp.forEach(i => { result.push(i.name + '::' + i.id + '::' + page) })
     *   result
     */
    @Test
    fun testUserReproFullScenario() {
        val tags = arrayListOf(
            TagBean(1, "小说"),
            TagBean(2, "散文")
        )
        val result = ResultBean(tags, "text", null)
        val book = BookBean(2048)
        val page = 1
        val ret = QuickJsEngine.eval(
            """
            var tmp = result.tags
            var r = [result.newsType || result.mediaType]
            if (r[r.length - 1] == "text")
                book.type = (book.type & ~2048) | 8
            if (r[r.length - 1] == "COVER")
                book.type = (book.type & ~2048) | 64
            tmp.forEach(i => {
                r.push(i.name + '::' + i.id + '::' + page)
            })
            r.join('|')
            """
        ) {
            put("result", result)
            put("book", book)
            put("page", page)
        }
        // book.type: 2048 & ~2048 = 0, 0 | 8 = 8
        assertEquals(8, book.type)
        // forEach 执行后 r = ["text", "小说::1::1", "散文::2::1"]
        assertEquals("text|小说::1::1|散文::2::1", ret.toString())
    }

    // ============ 3. Function 接口转换 ============

    /**
     * 验证 JS function 自动包装为 java.util.function.Function。
     *
     * SamTestHelper.applyFunction 接收 Function<String,String>, JS 传箭头函数,
     * apply 调用时回调 JS function (input.toUpperCase())。
     */
    @Test
    fun testFunctionConversion() {
        val helper = SamTestHelper()
        QuickJsEngine.eval(
            """
            helper.applyFunction(s => s.toUpperCase(), 'hello')
            """
        ) {
            put("helper", helper)
        }
        assertEquals("HELLO", helper.functionResult)
    }

    /**
     * 验证 Function 返回值回传 JS (链式调用场景)。
     */
    @Test
    fun testFunctionReturnValueChain() {
        val helper = SamTestHelper()
        val ret = QuickJsEngine.eval(
            """
            helper.applyFunction(s => '[' + s + ']', 'world')
            """
        ) {
            put("helper", helper)
        }
        assertEquals("[world]", helper.functionResult)
        assertEquals("[world]", ret.toString())
    }

    // ============ 4. 异常传播 ============

    /**
     * 验证 JS function 内 throw 不被静默吞掉。
     *
     * 修复前: forEach 抛 IllegalArgumentException "got java.lang.Long" (类型不匹配),
     *         或 coerceValue 静默返回 Long 导致 method.invoke 抛 IllegalArgumentException。
     * 修复后: JS throw 经 JsSamAdapter → InvocationTargetException → wrap 为 JS 异常,
     *         最终传播到 Java 侧 (JsNativeException / ScriptException)。
     *
     * 关键验证点:
     * - 异常被抛出 (非 null), 不静默返回 undefined/null
     * - 异常链中不含 IllegalArgumentException "got java.lang.Long" (SAM 修复前症状)
     */
    @Test
    fun testExceptionPropagation() {
        val list = arrayListOf("a")
        var caught: Throwable? = null
        try {
            QuickJsEngine.eval(
                """
                lst.forEach(e => { throw new Error('test error from JS') })
                """
            ) {
                put("lst", list)
            }
            fail("Expected exception to be thrown")
        } catch (e: Throwable) {
            caught = e
        }
        // 异常应传播到 Java 侧, 不静默吞掉
        assertNotNull("JS throw 应传播到 Java 侧, 不应静默吞掉", caught)
        // 不应是 IllegalArgumentException (SAM 修复前的症状: "got java.lang.Long")
        val illArgEx = findInChain(caught) { it is IllegalArgumentException }
        assertNull(
            "不应是 IllegalArgumentException (SAM 修复前症状): ${illArgEx?.message}",
            illArgEx
        )
    }

    // ============ 辅助方法 ============

    /**
     * 遍历异常链查找满足 predicate 的 Throwable (防止循环引用)。
     */
    private fun findInChain(t: Throwable?, predicate: (Throwable) -> Boolean): Throwable? {
        var current = t
        val visited = mutableSetOf<Throwable>()
        while (current != null && current !in visited) {
            visited.add(current)
            if (predicate(current)) return current
            current = current.cause
        }
        return null
    }
}

// ============ 测试辅助类 (需 public 供反射访问) ============

/**
 * 标签 bean (模拟书源 tags 列表元素)。
 */
class TagBean(val id: Int, val name: String)

/**
 * 结果 bean (模拟 result 对象, 含 tags/newsType/mediaType)。
 */
class ResultBean(
    val tags: ArrayList<TagBean>,
    val newsType: String?,
    val mediaType: String?
)

/**
 * Book bean (模拟 book 对象, 含可写的 type 字段)。
 * type 字段用 Int (位标志), 验证 JS 位运算修改。
 */
class BookBean(var type: Int)

/**
 * SAM 测试辅助类, 提供 [java.util.function.Function] 参数方法。
 *
 * applyFunction 接收 Function<String,String>, JS 传箭头函数时自动包装为 Proxy,
 * fn.apply(input) 回调 JS function。
 */
class SamTestHelper {
    var functionResult: String? = null

    fun applyFunction(
        fn: java.util.function.Function<String, String>,
        input: String
    ): String? {
        functionResult = fn.apply(input)
        return functionResult
    }
}
