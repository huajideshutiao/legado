package com.script.quickjs

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.script.rhino.RhinoScriptEngine
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Java 互操作专项基准测试（只测之前的弱项）。
 *
 * 运行：
 * .\gradlew :modules:quickjs:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.script.quickjs.JavaInteropBenchTest" --console=plain
 */
@RunWith(AndroidJUnit4::class)
class JavaInteropBenchTest {
    companion object {
        private const val WARMUP = 5
        private const val ITERATIONS = 50
    }

    @Test
    fun benchmarkJavaInterop() {
        println("\n========== Java 互操作专项基准 ==========\n")

        // StringBuilder 链式调用 (20 次 append)
        val sbJs = """
            var sb = new java.lang.StringBuilder();
            for (var i = 0; i < 20; i++) {
                sb.append('item').append(i).append(',');
            }
            sb.toString();
        """.trimIndent()

        // ArrayList 操作 (50 个元素)
        val listJs = """
            var list = new java.util.ArrayList();
            for (var i = 0; i < 50; i++) list.add('book_' + i);
            var sb = new java.lang.StringBuilder();
            for (var i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(list.get(i));
            }
            sb.toString();
        """.trimIndent()

        // LinkedHashMap clone + 遍历 (30 个键)
        val mapJs = """
            var m = new java.util.LinkedHashMap();
            for (var i = 0; i < 30; i++) {
                var inner = new java.util.LinkedHashMap();
                inner.put('count', i * 10);
                inner.put('name', 'item_' + i);
                m.put(String(i), inner);
            }
            var body = m.clone();
            var sum = 0;
            for (var key in body) {
                sum += body.get(key).get('count');
            }
            sum;
        """.trimIndent()

        runBench("StringBuilder 链式 (20×append)", sbJs)
        runBench("ArrayList 操作 (50 元素)", listJs)
        runBench("LinkedHashMap clone+遍历 (30 键)", mapJs)

        println("\n========================================")
    }

    private fun runBench(name: String, js: String) {
        // warmup
        repeat(WARMUP) { QuickJsEngine.eval(js) }
        repeat(WARMUP) { RhinoScriptEngine.eval(js) }

        // measure
        val qjTime = measureTimeMillis {
            repeat(ITERATIONS) { QuickJsEngine.eval(js) }
        }
        val rhTime = measureTimeMillis {
            repeat(ITERATIONS) { RhinoScriptEngine.eval(js) }
        }

        val qjAvg = qjTime.toDouble() / ITERATIONS
        val rhAvg = rhTime.toDouble() / ITERATIONS
        val ratio = qjAvg / rhAvg

        println(
            String.format(
                "%-35s | QJ: %7.2f ms/it | Rh: %7.2f ms/it | QJ/Rh: %5.2fx",
                name, qjAvg, rhAvg, ratio
            )
        )
    }
}
