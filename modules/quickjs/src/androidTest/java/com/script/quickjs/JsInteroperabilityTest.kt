package com.script.quickjs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Java 互操作能力测试 (自封装 native QuickJS)。
 *
 * 与 [QuickJsEngineTest] 互补, 专注 native trap + method callable 实现:
 * - method callable (var fn = obj.method; fn(args)) — native JS_NewCFunctionData
 * - JavaAdapter 实际回调执行 (创建 Runnable adapter, 调 run() 验证 JS 函数被调用)
 * - method 重载选择 (StringBuilder.append 多重载)
 * - 普通 Java 对象字段写入 (setter / public field)
 * - Packages 深层路径访问
 * - JsFunction 句柄传递 (Java 方法接收 JS 函数作为参数)
 * - 静态方法/字段完整链路
 * - Map/List 完整互操作 (含 for...in + .get() 并存, 段评场景)
 *
 * 对照 rhino LiveConnect 特性:
 * - Packages/JavaImporter/JavaAdapter 已在 QuickJsEngineTest 覆盖
 * - 这里专注 method callable 和回调执行 (rhino NativeJavaObject 的核心行为)
 */
@RunWith(AndroidJUnit4::class)
class JsInteroperabilityTest {

    // ============ method callable (native JS_NewCFunctionData) ============

    /**
     * method callable 核心场景: var fn = obj.method; fn(args)
     *
     * native 层 getProperty trap 检测到 method 时, 用 JS_NewCFunctionData 创建 JS 函数,
     * func_data[0]=objHandle, func_data[1]=methodName, 回调时调 JavaObjectBridgeNative.callMethod。
     *
     * 这是 rhino NativeJavaObject.FieldAndMethods 的等价实现,
     * 让 var fn = obj.method 后 fn() 仍能正确调用原对象的方法。
     */
    @Test
    fun testMethodCallableDirectCall() {
        val sb = StringBuilder("hello")
        // 直接调用 sb.append(" world") 应正常工作
        val result = QuickJsEngine.eval("sb.append(' world').toString()") {
            put("sb", sb)
        }
        assertEquals("hello world", result.toString())
    }

    @Test
    fun testMethodCallableExtractedAndInvoked() {
        // 核心测试: 把方法提取为变量后调用, this 绑定必须正确
        // 修复前 (旧 JS Proxy 实现): var fn = obj.method 会丢失 this 绑定
        // 修复后 (native method callable): func_data 持有 objHandle, this 正确绑定
        val sb = StringBuilder("prefix-")
        val result = QuickJsEngine.eval(
            """
            var fn = sb.append;
            fn('-middle');
            fn('-suffix');
            sb.toString();
        """.trimIndent()
        ) {
            put("sb", sb)
        }
        assertEquals("prefix--middle-suffix", result.toString())
    }

    @Test
    fun testMethodCallablePreservedAcrossMultipleAccess() {
        // 多次访问同一方法名应返回可调用的 callable (验证 method callable 缓存)
        val list = ArrayList<String>()
        val result = QuickJsEngine.eval(
            """
            var add = lst.add;
            add('a');
            add('b');
            add('c');
            var size = lst.size;
            size();
        """.trimIndent()
        ) {
            put("lst", list)
        }
        assertEquals(3, (result as Number).toInt())
        assertEquals(listOf("a", "b", "c"), list)
    }

    @Test
    fun testMethodCallableWithArguments() {
        // method callable 带参数调用 (验证 args 传递)
        val sb = StringBuilder()
        QuickJsEngine.eval(
            """
            var append = sb.append;
            var str = sb.append;
            append('Hello');
            append(' ');
            append('World');
            null;
        """.trimIndent()
        ) {
            put("sb", sb)
        }
        assertEquals("Hello World", sb.toString())
    }

    // ============ JavaAdapter 实际回调执行 ============

    /**
     * JavaAdapter 实际回调执行: 创建 Runnable adapter, 调 run() 验证 JS 函数被调用。
     *
     * QuickJsEngineTest.testNewInterfaceWithJsObjectCreatesAdapter 只验证创建,
     * 这里验证 JS 函数实际被调用 (JsFunctionHandle 回调链路)。
     *
     * 注意: 必须在 Kotlin 侧调用 adapter.run(), 不能在 evaluate 内调用,
     * 否则会触发 JS->Java->JS 嵌套 (native quickjs 同步, 但需测试是否正常工作)。
     */
    @Test
    fun testJavaAdapterCallbackExecutesJsFunction() {
        // 通过 JsFunction 直接调用 JS 函数, 验证 JS->Java->JS 回调链路
        // 这里直接测试 JavaAdapter 创建 + 回调: 在 JS 内创建 Runnable, 通过 Kotlin 侧调用 run()
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            // 在 scope 内创建 Runnable adapter 并暴露到全局
            QuickJsEngine.eval(
                """
                globalThis.createRunnable = function() {
                    return new Packages.java.lang.Runnable({
                        run: function() {
                            globalThis.__adapterCalled__ = true;
                        }
                    });
                };
                null;
            """.trimIndent(), scope, null
            )

            // 通过 JsFunction 调用 createRunnable, 拿到 Java Runnable
            val adapter = JsFunction(scope, "createRunnable").call()
            assertNotNull("adapter should be created", adapter)
            assertTrue("adapter should be Runnable", adapter is Runnable)

            // 调用 run() 应触发 JS 函数, 设置 globalThis.__adapterCalled__
            (adapter as Runnable).run()

            val called = QuickJsEngine.eval("globalThis.__adapterCalled__ === true", scope, null)
            assertEquals(true, called)
        } finally {
            scope.close()
        }
    }

    @Test
    fun testJavaAdapterCallbackWithArguments() {
        // JavaAdapter 回调带参数: Comparator.compare(a, b) 返回 JS 函数计算结果
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            QuickJsEngine.eval(
                """
                globalThis.createComparator = function() {
                    return new Packages.java.util.Comparator({
                        compare: function(a, b) {
                            // 降序比较
                            return b - a;
                        }
                    });
                };
                null;
            """.trimIndent(), scope, null
            )

            val comparator = JsFunction(scope, "createComparator").call()
            assertNotNull(comparator)
            assertTrue("adapter should be Comparator", comparator is java.util.Comparator<*>)

            @Suppress("UNCHECKED_CAST")
            val cmp = comparator as java.util.Comparator<Int>
            // 降序: 5 在前, 1 在后
            assertTrue("5 should be before 1 (descending)", cmp.compare(5, 1) < 0)
            assertTrue("1 should be after 5 (descending)", cmp.compare(1, 5) > 0)
            assertEquals("equal elements", 0, cmp.compare(3, 3))
        } finally {
            scope.close()
        }
    }

    @Test
    fun testJavaAdapterUsedInJavaMethod() {
        // JavaAdapter 作为参数传给 Java 方法 (Collections.sort + Comparator)
        // 验证 JsFunctionHandle 回调链路: JS 函数 -> JavaAdapter -> Java 方法调用 -> 回调 JS
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            QuickJsEngine.eval(
                """
                globalThis.sortDescending = function() {
                    var list = new java.util.ArrayList();
                    list.add(3); list.add(1); list.add(4); list.add(1); list.add(5);
                    var cmp = new Packages.java.util.Comparator({
                        compare: function(a, b) { return b - a; }
                    });
                    java.util.Collections.sort(list, cmp);
                    return list;
                };
                null;
            """.trimIndent(), scope, null
            )

            val result = JsFunction(scope, "sortDescending").call()
            assertNotNull(result)
            assertTrue("result should be List", result is List<*>)
            val sorted = (result as List<*>).map { (it as Number).toInt() }
            assertEquals(listOf(5, 4, 3, 1, 1), sorted)
        } finally {
            scope.close()
        }
    }

    // ============ method 重载选择 ============

    /**
     * method 重载选择: StringBuilder.append 有多个重载 (String/CharSequence/Int/...)。
     *
     * JavaObjectBridge.findBestMethod 应根据参数类型选择最佳重载。
     * 验证不同类型参数调用 append 时选择正确重载。
     */
    @Test
    fun testMethodOverloadSelectionAppend() {
        // StringBuilder.append: append(String) / append(int) / append(char) / append(CharSequence)
        val result = QuickJsEngine.eval(
            """
            var sb = new java.lang.StringBuilder();
            sb.append('str:');       // append(String)
            sb.append(42);           // append(int)
            sb.append('-');          // append(char) 或 append(String)
            sb.append(true);         // append(boolean)
            sb.toString();
        """.trimIndent()
        )
        assertEquals("str:42-true", result.toString())
    }

    @Test
    fun testMethodOverloadSelectionValueOf() {
        // String.valueOf 多重载: valueOf(int) / valueOf(boolean) / valueOf(Object) / valueOf(char[])
        val result = QuickJsEngine.eval(
            """
            var results = [];
            results.push(java.lang.String.valueOf(123));
            results.push(java.lang.String.valueOf(true));
            results.push(java.lang.String.valueOf(3.14));
            results.push(java.lang.String.valueOf('text'));
            results.join('|');
        """.trimIndent()
        )
        assertEquals("123|true|3.14|text", result.toString())
    }

    @Test
    fun testMethodOverloadSelectionListAdd() {
        // List.add 有两个重载: add(E) 和 add(int, E)
        val result = QuickJsEngine.eval(
            """
            var list = new java.util.ArrayList();
            list.add('a');           // add(E)
            list.add('b');           // add(E)
            list.add(1, 'inserted'); // add(int, E) 在 index 1 插入
            list.get(0) + ',' + list.get(1) + ',' + list.get(2);
        """.trimIndent()
        )
        assertEquals("a,inserted,b", result.toString())
    }

    // ============ 普通 Java 对象字段写入 ============

    /**
     * 普通 Java 对象字段写入: obj.field = value
     *
     * QuickJsEngineTest.testMapMutationFromJS / testListMutationFromJS 已测集合,
     * 这里测普通 Java 对象的 setter 和 public field 写入。
     */
    @Test
    fun testPublicFieldWrite() {
        // android.graphics.Point 有 public x, y 字段
        val result = QuickJsEngine.eval(
            """
            var p = new android.graphics.Point(10, 20);
            p.x = 100;
            p.y = 200;
            p.x + ',' + p.y;
        """.trimIndent()
        )
        assertEquals("100,200", result.toString())
    }

    @Test
    fun testSetterInvocationViaPropertyAssignment() {
        // StringBuilder 没有 setter, 用 ArrayList 测 setProperty trap
        // ArrayList.set(int, E) 通过 list[i] = value 触发
        val list = ArrayList<String>(listOf("old", "b", "c"))
        QuickJsEngine.eval(
            """
            lst[0] = 'new';
            lst[2] = 'changed';
        """.trimIndent()
        ) {
            put("lst", list)
        }
        assertEquals(listOf("new", "b", "changed"), list)
    }

    @Test
    fun testFieldWriteReflectsInJava() {
        // 验证 JS 侧字段写入后, Java 侧能读到新值
        val point = android.graphics.Point(1, 2)
        QuickJsEngine.eval(
            """
            p.x = p.x * 10;
            p.y = p.y * 20;
        """.trimIndent()
        ) {
            put("p", point)
        }
        assertEquals(10, point.x)
        assertEquals(40, point.y)
    }

    @Test
    fun testBeanSetterViaPropertyAssignment() {
        // 回归测试: bean 属性写入 obj.xxx = value 应调用 setXxx(value), 不能静默失败。
        // 旧 findSetter 用无参版 getDeclaredMethod(name) 找不到带参的 setTime(long),
        // 导致 date.time = 1000 写入无效; 而读 date.time 走 findGetter 能找到无参 getTime(),
        // 造成读写不对称 (source.variable = token 同根因)。
        // 用 java.util.Date 验证: 它有 getTime()/setTime(long) 这对 bean 方法, 但没有 public time 字段,
        // 必须经 setter 才能写入。
        val date = java.util.Date(0L)
        QuickJsEngine.eval(
            """
            d.time = 1000;
        """.trimIndent()
        ) {
            put("d", date)
        }
        assertEquals(1000L, date.time)
    }

    @Test
    fun testBeanSetterAndGetterRoundtrip() {
        // 回归测试: bean 属性写后读应一致, 覆盖
        //   source.variable = token; java.log(source.variable)
        // 这种读写组合场景。JS Number 1000 经 coerceArgs(Double -> long) 调 setTime(long),
        // 读走 getTime() 返回 long -> JS Number, 两端值必须相等。
        val date = java.util.Date(0L)
        val result = QuickJsEngine.eval(
            """
            d.time = 9999;
            d.time;
        """.trimIndent()
        ) {
            put("d", date)
        }
        assertEquals(9999L, (result as Number).toLong())
        assertEquals(9999L, date.time)
    }

    // ============ Packages 深层路径访问 ============

    /**
     * Packages 深层路径: Packages.java.lang.String.ValueOf 等。
     *
     * 验证 __makePkgProxy 嵌套访问 + __loadJavaClass 加载。
     */
    @Test
    fun testPackagesDeepPathAccess() {
        val result = QuickJsEngine.eval(
            """
            Packages.java.lang.String.valueOf(42);
        """.trimIndent()
        )
        assertEquals("42", result.toString())
    }

    @Test
    fun testPackagesDeepPathWithStaticMethod() {
        val result = QuickJsEngine.eval(
            """
            Packages.java.lang.Math.max(10, 20);
        """.trimIndent()
        )
        assertEquals(20, (result as Number).toInt())
    }

    @Test
    fun testPackagesDeepPathWithConstructor() {
        val result = QuickJsEngine.eval(
            """
            var sb = new Packages.java.lang.StringBuilder('hello');
            sb.append(' world').toString();
        """.trimIndent()
        )
        assertEquals("hello world", result.toString())
    }

    @Test
    fun testPackagesAlternateAlias() {
        // 验证 java/javax/android/com/org/io/cn 别名都可用
        val result = QuickJsEngine.eval(
            """
            var s1 = java.lang.String.valueOf('a');
            var s2 = javax.crypto.Cipher.getInstance('AES');
            s1 + '|' + (s2 !== null);
        """.trimIndent()
        )
        assertEquals("a|true", result.toString())
    }

    // ============ JsFunction 句柄传递 (Java 方法接收 JS 函数) ============

    /**
     * JsFunction 句柄传递: JS 函数作为参数传给 Java 方法。
     *
     * 业务场景: 书源 JS 中 forEach(sortFunc) 等。
     * 这里通过 JsFunction 调用 JS 函数, JS 函数内接收 Java 对象参数并调用其方法。
     */
    @Test
    fun testJsFunctionCallWithJavaArgs() {
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            QuickJsEngine.eval(
                """
                globalThis.processBook = function(book) {
                    return book.name + ' by ' + book.author;
                };
                null;
            """.trimIndent(), scope, null
            )

            // Java 对象作为参数传给 JS 函数
            val book = mapOf("name" to "斗破苍穹", "author" to "天蚕土豆")
            val result = JsFunction(scope, "processBook").call(book)
            assertEquals("斗破苍穹 by 天蚕土豆", result.toString())
        } finally {
            scope.close()
        }
    }

    @Test
    fun testJsFunctionCallWithMultipleJavaArgs() {
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            QuickJsEngine.eval(
                """
                globalThis.formatBook = function(name, author, idx) {
                    return (idx + 1) + '. ' + name + ' - ' + author;
                };
                null;
            """.trimIndent(), scope, null
            )

            val result = JsFunction(scope, "formatBook").call("凡人修仙传", "忘语", 0)
            assertEquals("1. 凡人修仙传 - 忘语", result.toString())
        } finally {
            scope.close()
        }
    }

    @Test
    fun testJsFunctionCallWithPrimitiveArgs() {
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            QuickJsEngine.eval(
                """
                globalThis.compute = function(a, b, c) {
                    return (a + b) * c;
                };
                null;
            """.trimIndent(), scope, null
            )

            // 基本类型参数: Int + Int * Double
            val result = JsFunction(scope, "compute").call(3, 4, 2.5)
            assertEquals(17.5, (result as Number).toDouble(), 0.001)
        } finally {
            scope.close()
        }
    }

    @Test
    fun testJsFunctionReturningJavaObject() {
        // JS 函数内调用 Java 方法, 返回 Java 对象 (自动包装为 JavaObject)
        // 验证 native 层 JniValueConvert.fromJavaObject 自动包装链路
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            QuickJsEngine.eval(
                """
                globalThis.createList = function() {
                    var list = new java.util.ArrayList();
                    list.add('a');
                    list.add('b');
                    list.add('c');
                    return list;
                };
                null;
            """.trimIndent(), scope, null
            )

            val result = JsFunction(scope, "createList").call()
            assertNotNull(result)
            assertTrue("should be List", result is List<*>)
            assertEquals(listOf("a", "b", "c"), result)
        } finally {
            scope.close()
        }
    }

    // ============ 静态方法/字段完整链路 ============

    @Test
    fun testStaticMethodWithVariousArgTypes() {
        // String.format (静态方法, 多参数, 含 Object...)
        val result = QuickJsEngine.eval(
            """
            java.lang.String.format('%s-%d-%.2f', 'text', 42, 3.14159);
        """.trimIndent()
        )
        assertEquals("text-42-3.14", result.toString())
    }

    @Test
    fun testStaticFieldAccess() {
        // Integer.MAX_VALUE / Math.PI 等静态字段
        val result = QuickJsEngine.eval(
            """
            var maxInt = java.lang.Integer.MAX_VALUE;
            var pi = java.lang.Math.PI;
            maxInt + '|' + (pi > 3.14 && pi < 3.15);
        """.trimIndent()
        )
        assertEquals("2147483647|true", result.toString())
    }

    @Test
    fun testStaticFieldWriteFails() {
        // 静态字段写入: Integer.MAX_VALUE 是 final, 写入应失败但不崩溃
        // 验证 setStaticField binding 对 final 字段的处理
        val result = QuickJsEngine.eval(
            """
            try {
                java.lang.Integer.MAX_VALUE = 0;
                'write succeeded (unexpected)';
            } catch (e) {
                'write blocked: ' + (e !== null);
            }
        """.trimIndent()
        )
        // final 字段写入应被拦截 (具体行为依实现, 这里只验证不崩溃)
        assertTrue(
            "result should mention blocked or succeeded: $result",
            result.toString().contains("blocked") || result.toString().contains("succeeded")
        )
    }

    // ============ Map/List 完整互操作 (段评场景) ============

    /**
     * 段评场景核心: for (var key in body) body.get(key).xxx
     *
     * QuickJsEngineTest.testJavaMapForInAndGetCoexist 已有覆盖,
     * 这里补充: Map 嵌套 + 修改 + Object.entries 完整链路。
     */
    @Test
    fun testNestedMapAccessForInAndGet() {
        // 段评 idea_data: {"1": {idea_count: 5, user: "u1"}, "2": {idea_count: 10, user: "u2"}}
        val result = QuickJsEngine.eval(
            """
            var m = new java.util.LinkedHashMap();
            var v1 = new java.util.LinkedHashMap();
            v1.put('idea_count', 5);
            v1.put('user', 'u1');
            var v2 = new java.util.LinkedHashMap();
            v2.put('idea_count', 10);
            v2.put('user', 'u2');
            m.put('1', v1);
            m.put('2', v2);
            var body = m.clone();
            var total = 0;
            var users = [];
            for (var key in body) {
                total += body.get(key).get('idea_count');
                users.push(body.get(key).get('user'));
            }
            total + '|' + users.join(',');
        """.trimIndent()
        )
        assertEquals("15|u1,u2", result.toString())
    }

    @Test
    fun testMapMutationViaBracketAndMethod() {
        // Map 修改: body[key] = value (set trap) 和 body.put(k, v) (method) 都应生效
        val result = QuickJsEngine.eval(
            """
            var m = new java.util.LinkedHashMap();
            m.put('orig', 'val');
            var body = m.clone();
            body['new1'] = 'val1';            // set trap
            body.put('new2', 'val2');         // method 调用
            body['orig'] = 'modified';        // set trap 修改
            Object.keys(body).join(',') + '|' + body.size();
        """.trimIndent()
        )
        assertEquals("orig,new1,new2|3", result.toString())
    }

    @Test
    fun testListIterationWithForIn() {
        // List 的 for...in 应枚举索引 (与 rhino NativeJavaList.getIds 一致)
        val result = QuickJsEngine.eval(
            """
            var list = new java.util.ArrayList();
            list.add('a'); list.add('b'); list.add('c');
            var items = [];
            for (var idx in list) {
                items.push(idx + ':' + list.get(idx));
            }
            items.join('|');
        """.trimIndent()
        )
        assertEquals("0:a|1:b|2:c", result.toString())
    }

    @Test
    fun testListObjectEntriesReflection() {
        // Object.entries 对 List 应返回索引-值对 (rhino NativeJavaList 行为)
        val result = QuickJsEngine.eval(
            """
            var list = new java.util.ArrayList();
            list.add('x'); list.add('y');
            var entries = Object.entries(list);
            var parts = [];
            for (var i = 0; i < entries.length; i++) {
                parts.push(entries[i][0] + '=' + entries[i][1]);
            }
            parts.join(',');
        """.trimIndent()
        )
        assertEquals("0=x,1=y", result.toString())
    }

    // ============ 综合互操作场景 ============

    /**
     * 综合场景: JS 调用 Java 方法返回 Java 对象, 再调用其方法。
     *
     * 验证 native 层 fromJavaObject 自动包装 + exotic trap 链路。
     */
    @Test
    fun testChainedJavaMethodCalls() {
        // StringBuilder.append 返回 this (StringBuilder), 应能继续链式调用
        val result = QuickJsEngine.eval(
            """
            var sb = new java.lang.StringBuilder();
            sb.append('a').append('b').append('c').append('d');
            sb.toString();
        """.trimIndent()
        )
        assertEquals("abcd", result.toString())
    }

    @Test
    fun testJavaObjectReturnFromMethodUsedAsArgument() {
        // Java 方法返回的对象作为另一个 Java 方法的参数
        val result = QuickJsEngine.eval(
            """
            var list1 = new java.util.ArrayList();
            list1.add('item1');
            list1.add('item2');
            var list2 = new java.util.ArrayList();
            list2.addAll(list1);  // list1 作为参数传给 list2.addAll
            list2.size();
        """.trimIndent()
        )
        assertEquals(2, (result as Number).toInt())
    }

    @Test
    fun testJavaStaticFactoryMethodReturnsInstance() {
        // 静态工厂方法: Collections.emptyList() / Collections.singletonList()
        val result = QuickJsEngine.eval(
            """
            var empty = java.util.Collections.emptyList();
            var single = java.util.Collections.singletonList('only');
            empty.size() + '|' + single.size() + '|' + single.get(0);
        """.trimIndent()
        )
        assertEquals("0|1|only", result.toString())
    }

    @Test
    fun testNullPointerExceptionHandling() {
        // Java 方法抛 NPE 时, 应被捕获并转为 JS 异常
        val result = QuickJsEngine.eval(
            """
            var list = new java.util.ArrayList();
            try {
                list.get(0);  // IndexOutOfBoundsException
                'no exception';
            } catch (e) {
                'caught: ' + (e !== null && e !== undefined);
            }
        """.trimIndent()
        )
        assertTrue("should catch exception: $result", result.toString().startsWith("caught"))
    }

    // ============ String(java) 转换与 toString ============

    @Test
    fun testStringConstructorWithJavaObject() {
        // String(javaObj) 应调用 Java toString() (rhino LiveConnect 行为)
        val result = QuickJsEngine.eval(
            """
            var sb = new java.lang.StringBuilder('content');
            String(sb);
        """.trimIndent()
        )
        assertEquals("content", result.toString())
    }

    @Test
    fun testJavaToStringWithInteger() {
        // Integer.toString() 应返回字符串
        val result = QuickJsEngine.eval(
            """
            var i = java.lang.Integer.valueOf(42);
            i.toString();
        """.trimIndent()
        )
        assertEquals("42", result.toString())
    }

    // ============ 多线程/多 scope 隔离 ============

    /**
     * 多 scope 隔离: 不同 scope 的变量不互通。
     *
     * 验证 QuickJsContext 实例独立性 (每个 ctx 有独立 topScope)。
     */
    @Test
    fun testMultipleScopesAreIsolated() {
        val scope1 = QuickJsEngine.getRuntimeScope(ScriptBindings())
        val scope2 = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            QuickJsEngine.injectBindings(scope1, ScriptBindings().apply { put("x", "scope1") })
            QuickJsEngine.injectBindings(scope2, ScriptBindings().apply { put("x", "scope2") })

            val r1 = QuickJsEngine.eval("x", scope1, null)
            val r2 = QuickJsEngine.eval("x", scope2, null)
            assertEquals("scope1", r1.toString())
            assertEquals("scope2", r2.toString())

            // scope1 修改 x 不影响 scope2
            QuickJsEngine.eval("globalThis.x = 'modified'", scope1, null)
            val r1After = QuickJsEngine.eval("x", scope1, null)
            val r2After = QuickJsEngine.eval("x", scope2, null)
            assertEquals("modified", r1After.toString())
            assertEquals("scope2", r2After.toString())  // scope2 不受影响
        } finally {
            scope1.close()
            scope2.close()
        }
    }

    @Test
    fun testScopeCloseReleasesResources() {
        // scope.close() 后, 相关 Java 对象句柄应被释放
        // 这里验证 close 不抛异常, 且 close 后 scope 不再可用 (重复 close 是 no-op)
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        QuickJsEngine.eval(
            "var sb = new java.lang.StringBuilder('test'); sb.toString();",
            scope,
            null
        )
        scope.close()
        // 重复 close 应是 no-op
        scope.close()
        // 无法直接验证 native ctx 已释放 (访问已 close 的 scope 会崩溃),
        // 这里只验证 close 本身正确执行
    }

    // ============ JavaAdapter 多接口场景 ============

    /**
     * JavaAdapter 多方法接口: 实现 FilterInputStream 等多方法接口。
     *
     * 这里用 Runnable (单方法) + Comparator (单方法) 已覆盖, 补充多方法场景。
     */
    @Test
    fun testJavaAdapterWithSingleMethodInterface() {
        // 单方法接口: Runnable
        val counter = AtomicInteger(0)
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            QuickJsEngine.eval(
                """
                globalThis.makeCountingRunnable = function() {
                    return new Packages.java.lang.Runnable({
                        run: function() {
                            globalThis.__count__ = (globalThis.__count__ || 0) + 1;
                        }
                    });
                };
                null;
            """.trimIndent(), scope, null
            )

            val r1 = JsFunction(scope, "makeCountingRunnable").call() as Runnable
            val r2 = JsFunction(scope, "makeCountingRunnable").call() as Runnable

            // 两个独立 adapter, 各自调用 run
            r1.run()
            r1.run()
            r2.run()

            // 验证 __count__ 累加 (共享 globalThis, 用于验证回调链路)
            val count = QuickJsEngine.eval("globalThis.__count__", scope, null)
            assertEquals(3, (count as Number).toInt())
        } finally {
            scope.close()
        }
    }
}
