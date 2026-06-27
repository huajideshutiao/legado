package com.script.quickjs

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.script.rhino.RhinoScriptEngine
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * rhino vs quickjs 性能基准测试。
 *
 * 从番茄小说书源 (https://shuyuan.nyasama.net/shuyuan/b75a16a67ee45c5303049c20205afc43.json)
 * 提取常见 JS 场景,不包含网络请求,对比两个引擎的执行性能。
 *
 * 运行方式:
 * .\gradlew :quickjs:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.script.quickjs.JsBenchmarkTest" --console=plain 2>&1 | Tee-Object -FilePath "quickjs\bench_output.txt"
 */
@RunWith(AndroidJUnit4::class)
class JsBenchmarkTest {

    companion object {
        // 每个场景的迭代次数
        private const val WARMUP = 5
        private const val ITERATIONS = 50

        // 从番茄小说书源提取的 xGorgon 核心计算逻辑 (无网络请求)
        // 原始代码使用 hutool DigestUtil/StrUtil,这里改用 Java 标准库实现 md5Hex/reverse
        // padStart 改用 slice(-2) 方式以兼容 rhino
        private val XGORGON_JS = """
            function md5Hex(str) {
                var md = java.security.MessageDigest.getInstance('MD5');
                var bytes = java.lang.String(str).getBytes('UTF-8');
                var digest = md.digest(bytes);
                var hex = '';
                for (var i = 0; i < digest.length; i++) {
                    var b = digest[i] & 0xff;
                    hex += (b < 16 ? '0' : '') + b.toString(16);
                }
                return hex;
            }
            function rStr(str) { return str.split('').reverse().join(''); }
            function Hex(num) { return ('0' + num.toString(16)).slice(-2); }
            function rHex(num) { return parseInt(rStr(Hex(num)), 16); }
            function rBin(num) {
                var bin = ('0000000' + num.toString(2)).slice(-8);
                return parseInt(rStr(bin), 2);
            }
            function getHex(params, data, ck) {
                var hex = md5Hex(params);
                hex += data ? md5Hex(data) : '0'.repeat(8);
                hex += ck ? md5Hex(ck) : '0'.repeat(8);
                return hex;
            }
            function calculate(hex) {
                var len = 0x14;
                var key = [0xDF,0x77,0xB9,0x40,0xB9,0x9B,0x84,0x83,0xD1,0xB9,0xCB,0xD1,0xF7,0xC2,0xB9,0x85,0xC3,0xD0,0xFB,0xC3];
                var paramList = [];
                for (var i = 0; i < 9; i += 4) {
                    var temp = hex.substring(8*i, 8*(i+1));
                    for (var j = 0; j < 4; j++) {
                        paramList.push(parseInt(temp.substring(j*2, (j+1)*2), 16));
                    }
                }
                paramList.push(0x0, 0x6, 0xB, 0x1C);
                var T = Math.floor(Date.now() / 1000);
                paramList.push((T>>24)&0xFF, (T>>16)&0xFF, (T>>8)&0xFF, T&0xFF);
                var eor = [];
                for (var i = 0; i < paramList.length; i++) eor.push(paramList[i] ^ key[i % len]);
                for (var A,B,C,D,i = 0; i < len; i++) {
                    A = rHex(eor[i]); B = eor[(i+1) % len];
                    C = rBin(A ^ B); D = ((C ^ 0xFFFFFFFF) ^ len) & 0xFF;
                    eor[i] = D;
                }
                var result = '';
                for (var i = 0; i < eor.length; i++) result += Hex(eor[i]);
                return result;
            }
            var params = 'bookmall/tab&iid=983439455837980&aid=1967&channel=0&&os_version=0&app_name=novelapp&version_code=58932&device_platform=android&device_type=unknown';
            calculate(getHex(params, null, 'sessionid=test'));
        """.trimIndent()
    }

    @Test
    fun benchmarkAll() {
        println("\n========== rhino vs quickjs 性能基准测试 ==========")
        println("迭代次数: $ITERATIONS (warmup $WARMUP)\n")

        runBench("1. 基本算术", "1 + 2 * 3 + 4 * 5")
        runBench("2. 字符串拼接", "'hello' + ' ' + 'world' + ' ' + 'test'")
        runBench(
            "3. Object.entries (段评场景)", """
            var obj = {a:1, b:2, c:3, d:4, e:5, f:6, g:7, h:8};
            var r = {};
            for (var [id, info] of Object.entries(obj)) r[Number(id)+1] = info;
            JSON.stringify(r);
        """.trimIndent()
        )
        runBench("4. Java String.valueOf", "java.lang.String.valueOf(123456)")
        runBench(
            "5. Java MD5 哈希", """
            var md = java.security.MessageDigest.getInstance('MD5');
            var bytes = java.lang.String('hello world test').getBytes('UTF-8');
            var digest = md.digest(bytes);
            var hex = '';
            for (var i = 0; i < digest.length; i++) {
                var b = digest[i] & 0xff;
                hex += (b < 16 ? '0' : '') + b.toString(16);
            }
            hex;
        """.trimIndent()
        )
        runBench(
            "6. JavaImporter + with", """
            var importer = new JavaImporter(java.lang.String, java.lang.Math);
            with (importer) {
                String.valueOf(Math.max(100, 200));
            }
        """.trimIndent()
        )
        runBench(
            "7. 循环+数组 (1000 元素)", """
            var arr = [];
            for (var i = 0; i < 1000; i++) arr.push(i * i);
            var sum = 0;
            for (var i = 0; i < arr.length; i++) sum += arr[i];
            sum;
        """.trimIndent()
        )
        runBench("8. xGorgon 核心计算 (3次 MD5)", XGORGON_JS, iterations = 20)

        println("\n========== 测试完成 ==========")
    }

    /**
     * 多个小 JS 多次执行 (模拟解析书籍列表场景)。
     *
     * 模拟 10 本书 × 7 字段 = 70 次小 JS eval/页,共 5 页 = 350 次。
     * 每次注入 `result` 变量 (前一步 jsonpath 解析的字段原始值),
     * 模拟 AnalyzeRule 中 `@js:` 后置处理的执行路径。
     * 字段规则从番茄小说书源 ruleSearch/ruleExplore 提取。
     *
     * 对比三种模式:
     * 1. QuickJS 无缓存: 每次 eval 新建 QuickJs 实例 (走 QuickJsEngine.eval(js, bindingsConfig))
     * 2. QuickJS 有缓存: 复用 scope + 编译缓存 (真实 AnalyzeRule.evalJS 路径, 用 topScopeRef + scriptCache)
     * 3. Rhino 默认: eval(js, bindingsConfig) (rhino 复用 topLevelScope, 本身轻量)
     */
    @Test
    fun benchmarkBookListParse() {
        println("\n========== 多个小 JS 多次执行 (解析书籍列表场景) ==========")
        println("模拟 10 本书 × 7 字段 = 70 次 eval/页, 共 5 页\n")

        // 7 个小 JS 规则 (从番茄小说书源 ruleSearch/ruleExplore 提取典型 @js 后置处理)
        val fieldRules = listOf(
            // 1. coverUrl: 补全图片域名 (ruleExplore.coverUrl)
            """result.startsWith("http") ? result : "http://p6-novel.byteimg.com/" + result + "~tplv-shrink:320:0.image"""",
            // 2. intro: 清理换行和多余空白
            """result.replace(/\s+/g, " ").trim()""",
            // 3. wordCount: 字数格式化 (万字)
            """var n = parseInt(result); n > 10000 ? (n/10000).toFixed(1) + "万字" : n + "字"""",
            // 4. kind: 分割取分类 (ruleSearch.kind 的 && 分隔)
            """result.split("&&")[0]""",
            // 5. bookUrl: 提取尾部 book_id (ruleSearch.bookUrl 模板)
            """result.slice(-19)""",
            // 6. name: 去除首尾空白
            """result.trim()""",
            // 7. score: 拼接单位 (ruleExplore.kind 的 score 字段)
            """result + "分""""
        )

        // 10 本书的字段原始值 (模拟前一步 jsonpath 解析的结果)
        val bookFieldInputs = (1..10).map { idx ->
            listOf(
                "novel-pic/a1b2${idx}c3d4",                              // 1. coverUrl (thumb_uri)
                "这是一本小说的简介, 讲述了主角的冒险故事。 ".repeat(2),          // 2. intro
                "${idx * 12345 + 6789}",                                 // 3. wordCount
                "玄幻,连载中&&1&&${idx}.${idx}",                          // 4. kind
                "data:info,7638538561230228${idx}0${idx}",               // 5. bookUrl
                "  测试小说第${idx}部  ",                                  // 6. name (带空白)
                "${idx}.${idx}"                                           // 7. score
            )
        }

        val bookCount = 10
        val pageCount = 5
        val totalEvals = pageCount * bookCount * fieldRules.size

        // ===== 模式 1: QuickJS 无缓存 (每次新建 QuickJs 实例, 走 QuickJsEngine.eval(js, bindingsConfig)) =====
        // warmup
        repeat(WARMUP) {
            bookFieldInputs.forEach { fields ->
                fieldRules.forEachIndexed { i, rule ->
                    QuickJsEngine.eval(rule) { put("result", fields[i]) }
                }
            }
        }
        val qjNoCacheTime = measureTimeMillis {
            repeat(pageCount) {
                bookFieldInputs.forEach { fields ->
                    fieldRules.forEachIndexed { i, rule ->
                        QuickJsEngine.eval(rule) { put("result", fields[i]) }
                    }
                }
            }
        }

        // ===== 模式 2: QuickJS 有缓存 (复用 scope + 编译缓存, 真实 AnalyzeRule.evalJS 路径) =====
        // 预创建 scope (模拟 AnalyzeRule.topScopeRef, 一次性 bootstrap)
        val qjScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        // 预编译 (模拟 AnalyzeRule.scriptCache, wrapJsForEval + compile)
        val qjCompiled = fieldRules.map { QuickJsEngine.compile(QuickJsEngine.wrapJsForEval(it)) }
        // warmup
        repeat(WARMUP) {
            bookFieldInputs.forEach { fields ->
                fieldRules.forEachIndexed { i, _ ->
                    val b = ScriptBindings().apply { put("result", fields[i]) }
                    QuickJsEngine.injectBindings(qjScope, b)
                    qjCompiled[i].eval(qjScope, null)
                }
            }
        }
        val qjCachedTime = measureTimeMillis {
            repeat(pageCount) {
                bookFieldInputs.forEach { fields ->
                    fieldRules.forEachIndexed { i, _ ->
                        val b = ScriptBindings().apply { put("result", fields[i]) }
                        QuickJsEngine.injectBindings(qjScope, b)
                        qjCompiled[i].eval(qjScope, null)
                    }
                }
            }
        }

        // ===== 模式 3: Rhino 默认 (eval(js, bindingsConfig), rhino 复用 topLevelScope 本身轻量) =====
        // warmup
        repeat(WARMUP) {
            bookFieldInputs.forEach { fields ->
                fieldRules.forEachIndexed { i, rule ->
                    RhinoScriptEngine.eval(rule) { put("result", fields[i]) }
                }
            }
        }
        val rhTime = measureTimeMillis {
            repeat(pageCount) {
                bookFieldInputs.forEach { fields ->
                    fieldRules.forEachIndexed { i, rule ->
                        RhinoScriptEngine.eval(rule) { put("result", fields[i]) }
                    }
                }
            }
        }

        println(
            String.format(
                "总 eval 次数: %d (%d页 × %d本 × %d字段)\n",
                totalEvals,
                pageCount,
                bookCount,
                fieldRules.size
            )
        )
        println(
            String.format(
                "QuickJS 无缓存: %7.2f ms | %6.3f ms/eval",
                qjNoCacheTime.toDouble(),
                qjNoCacheTime.toDouble() / totalEvals
            )
        )
        println(
            String.format(
                "QuickJS 有缓存: %7.2f ms | %6.3f ms/eval",
                qjCachedTime.toDouble(),
                qjCachedTime.toDouble() / totalEvals
            )
        )
        println(
            String.format(
                "Rhino   默认:   %7.2f ms | %6.3f ms/eval",
                rhTime.toDouble(),
                rhTime.toDouble() / totalEvals
            )
        )
        println("─────────────────────────────────────────────")
        println(String.format("缓存提升 (无/有): %5.2fx", qjNoCacheTime.toDouble() / qjCachedTime))
        println(String.format("QJ缓存/Rhino:     %5.2fx", qjCachedTime.toDouble() / rhTime))
        println("==============================================")
    }

    private fun runBench(name: String, js: String, iterations: Int = ITERATIONS) {
        // warmup
        repeat(WARMUP) { QuickJsEngine.eval(js) }
        repeat(WARMUP) { RhinoScriptEngine.eval(js) }

        // quickjs
        val qjResult = QuickJsEngine.eval(js)
        val qjTime = measureTimeMillis {
            repeat(iterations) { QuickJsEngine.eval(js) }
        }

        // rhino
        val rhResult = RhinoScriptEngine.eval(js)
        val rhTime = measureTimeMillis {
            repeat(iterations) { RhinoScriptEngine.eval(js) }
        }

        val qjAvg = qjTime.toDouble() / iterations
        val rhAvg = rhTime.toDouble() / iterations
        val ratio = qjAvg / rhAvg

        println(
            String.format(
                "%-32s | QuickJS: %6.2f ms/iter | Rhino: %6.2f ms/iter | QJ/Rhino: %5.2fx",
                name, qjAvg, rhAvg, ratio
            )
        )
        // 结果一致性校验 (基础类型比较,复杂对象跳过)
        val qjStr = qjResult?.toString()?.take(50)
        val rhStr = rhResult?.toString()?.take(50)
        if (qjStr != rhStr) {
            println("  ! 结果不一致: QJ=[$qjStr] Rh=[$rhStr]")
        }
    }
}
