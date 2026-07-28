package com.script.quickjs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AnalyzeRule.evalJS 路径集成测试。
 *
 * 测试 AnalyzeRule.evalJS (app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt:799)
 * 的关键调用链路, 不依赖业务层 (BookSource/Book/CookieStore 等), 用简单 Java 对象模拟 bindings。
 *
 * 覆盖 AnalyzeRule 适配 quickjs 的关键修改点:
 * - QuickJsEngine.wrapJsForEval (IIFE + eval 包裹, 隔离 let/const)
 * - QuickJsEngine.getRuntimeScope (创建带 bootstrap 的 scope)
 * - QuickJsEngine.injectBindings / cleanupBindings (子 scope 隔离)
 * - QuickJsEngine.compile (bytecode 编译, 跨 scope 复用限制)
 * - CompiledScript.eval (bytecode 执行)
 * - scriptCache (编译缓存, getOrPutLimit 模拟)
 * - buildScriptBindings 构建 (java/cookie/cache/source/book/result/baseUrl/chapter/title/src/nextChapterUrl)
 *
 * 对照 rhino 行为:
 * - rhino: topLevelScope 复用, 顶层 return 扩展, NativeObject bindings
 * - quickjs: topScope 复用, IIFE + eval 模拟顶层 return, ScriptBindings (Map)
 *
 * 重点验证: 适配 quickjs 修改的调用处不回归 (用户提到之前出过类似问题)。
 */
@RunWith(AndroidJUnit4::class)
class AnalyzeRulePathTest {

    /**
     * 模拟 AnalyzeRule.buildScriptBindings 构建的 bindings。
     *
     * 真实 AnalyzeRule.evalJS (AnalyzeRule.kt:802-816) 构建的 bindings:
     * - variables (Map<JsVarName, Any>): 自定义变量
     * - java = this (AnalyzeRule 实例, 提供 ajax/getVar/putVar 方法)
     * - cookie = CookieStore
     * - cache = CacheManager
     * - source = source (BaseSource)
     * - book = book (Book)
     * - result = result (前一步解析结果)
     * - baseUrl = baseUrl
     * - chapter = chapter (BookChapter)
     * - title = chapter?.title
     * - src = content
     * - nextChapterUrl = nextChapterUrl
     *
     * 这里用简单 Java 对象 (HashMap/String/StringBuilder) 代替业务对象,
     * 验证 bindings 注入 + JS 访问 + Java 互操作的完整链路。
     */
    private fun buildMockBindings(
        result: Any? = null,
        baseUrl: String = "https://api.example.com",
        source: Any? = mapOf(
            "bookSourceUrl" to "https://example.com",
            "bookSourceName" to "测试源"
        ),
        book: Any? = mapOf("name" to "测试小说", "author" to "测试作者"),
        chapter: Any? = mapOf("title" to "第1章", "url" to "https://example.com/ch1"),
        content: String = "章节内容...",
        nextChapterUrl: String = "https://example.com/ch2",
        dangerousApi: Boolean = true,
        customVars: Map<String, Any?> = emptyMap(),
        mockJava: MockAnalyzeRule? = null
    ): ScriptBindings {
        return buildScriptBindings { bindings ->
            // 自定义变量 (模拟 variables: Map<JsVarName, Any>)
            customVars.forEach { (k, v) -> bindings[k] = v }
            // 核心 bindings (与 AnalyzeRule.evalJS 一致)
            // mockJava 为 null 时新建实例, 否则复用外部传入的实例 (跨多次 eval 保持状态)
            bindings["java"] = mockJava ?: MockAnalyzeRule()
            bindings["cookie"] = mapOf("sessionid" to "test-session", "token" to "abc123")
            bindings["cache"] = mapOf("lastSearch" to "玄幻")
            bindings["source"] = source
            bindings["book"] = book
            bindings["result"] = result
            bindings["baseUrl"] = baseUrl
            bindings["chapter"] = chapter
            bindings["title"] = (chapter as? Map<*, *>)?.get("title")
            bindings["src"] = content
            bindings["nextChapterUrl"] = nextChapterUrl
            // dangerousApi=true: 测试 mock 类 (MockAnalyzeRule) 在 com.script 包下,
            // 被 JsSecurityPolicy 的 "com.script" 前缀保护名单拦截, 需绕过安全名单。
            // 真实 AnalyzeRule 在 io.legado.app.model 包下, 不受此限制, dangerousApi 由书源控制。
            bindings.dangerousApi = dangerousApi
        }
    }

    /**
     * 模拟 AnalyzeRule 的核心方法 (ajax/getVar/putVar)。
     *
     * 真实 AnalyzeRule 实现:
     * - ajax(url): 同步 HTTP 请求 (runBlocking + AnalyzeUrl.getStrResponse)
     * - putVariable(key, value): 存到 chapter.variables
     * - getVariable(key): 从 chapter.variables 读
     *
     * 这里用简单实现, 不发网络请求, 只记录调用。
     */
    private class MockAnalyzeRule {
        private val variables = HashMap<String, Any?>()

        /** 模拟 ajax (实际不发请求, 返回 mock 数据) */
        fun ajax(url: Any): String {
            return "mock-response-for: $url"
        }

        /** 模拟 AnalyzeRule.putVar */
        fun putVar(key: String, value: Any?) {
            variables[key] = value
        }

        /** 模拟 AnalyzeRule.getVar */
        fun getVar(key: String): Any? {
            return variables[key]
        }

        override fun toString(): String = "MockAnalyzeRule"
    }

    /**
     * 模拟 AnalyzeRule.evalJS 完整路径 (无 source, 走 topScopeRef 路径)。
     *
     * 真实代码 (AnalyzeRule.kt:799-833):
     * ```kotlin
     * fun evalJS(jsStr: String, result: Any? = null): Any? {
     *     if (jsStr.isBlank()) return null
     *     val bindings = buildScriptBindings { ... }
     *     val topScope = source?.getShareScope(coroutineContext) ?: topScopeRef
     *     val wrappedJs = QuickJsEngine.wrapJsForEval(jsStr)
     *     return if (topScope == null) {
     *         val scope = QuickJsEngine.getRuntimeScope(bindings)
     *         topScopeRef = scope
     *         compileScriptCache(wrappedJs).eval(scope, coroutineContext)
     *     } else {
     *         val injectedKeys = QuickJsEngine.injectBindings(topScope, bindings)
     *         try {
     *             compileScriptCache(wrappedJs).eval(topScope, coroutineContext)
     *         } finally {
     *             QuickJsEngine.cleanupBindings(topScope, injectedKeys)
     *         }
     *     }
     * }
     * ```
     */
    private fun evalJSLikeAnalyzeRule(
        jsStr: String,
        result: Any? = null,
        topScopeRef: QuickJsContext? = null,
        scriptCache: HashMap<String, CompiledScript> = hashMapOf(),
        mockJava: MockAnalyzeRule? = null
    ): Pair<Any?, QuickJsContext> {
        if (jsStr.isBlank()) return null to (topScopeRef ?: error("no scope"))
        val bindings = buildMockBindings(result = result, mockJava = mockJava)
        val topScope = topScopeRef
        val wrappedJs = QuickJsEngine.wrapJsForEval(jsStr)
        return if (topScope == null) {
            // 路径 1: 无 source, 创建新 scope 并缓存
            val scope = QuickJsEngine.getRuntimeScope(bindings)
            val r = compileScriptCacheLike(wrappedJs, scriptCache, scope).eval(scope, null)
            r to scope
        } else {
            // 路径 2: 有 sharedScope, inject + eval + cleanup
            val injectedKeys = QuickJsEngine.injectBindings(topScope, bindings)
            try {
                val r =
                    compileScriptCacheLike(wrappedJs, scriptCache, topScope).eval(topScope, null)
                r to topScope
            } finally {
                QuickJsEngine.cleanupBindings(topScope, injectedKeys)
            }
        }
    }

    /**
     * 模拟 AnalyzeRule.compileScriptCache (AnalyzeRule.kt:835-839)。
     *
     * 真实代码:
     * ```kotlin
     * private fun compileScriptCache(jsStr: String): CompiledScript {
     *     return scriptCache.getOrPutLimit(jsStr, 16) {
     *         QuickJsEngine.compile(jsStr)
     *     }
     * }
     * ```
     *
     * 注意: 真实代码用 getOrPutLimit (限制 16 条), 这里用 getOrPut 简化。
     * 关键点: compile(jsStr) 不传 scope, 用全局 compiler ctx 编译,
     * 但 bytecode 必须在执行 scope 上 eval (跨 scope 兼容性限制)。
     */
    private fun compileScriptCacheLike(
        jsStr: String,
        cache: HashMap<String, CompiledScript>,
        scope: QuickJsContext
    ): CompiledScript {
        return cache.getOrPut(jsStr) {
            // AnalyzeRule 用 compile(jsStr) 不传 scope, 但需要在执行 scope 上 eval
            // 这里用 compile(jsStr, scope) 复用目标 scope 编译, 避免跨 scope 问题
            QuickJsEngine.compile(jsStr, scope)
        }
    }

    // ============ 基础路径验证 ============

    /**
     * 验证 AnalyzeRule.evalJS 路径 1: 无 source, 创建新 scope。
     *
     * 修复点: topScopeRef 缓存, 首次 eval 创建 scope, 后续复用。
     */
    @Test
    fun testEvalJsPath1CreatesNewScope() {
        val (result, scope) = evalJSLikeAnalyzeRule("result + ' processed';", result = "raw-data")
        try {
            assertEquals("raw-data processed", result.toString())
        } finally {
            scope.close()
        }
    }

    /**
     * 验证 AnalyzeRule.evalJS 路径 2: 有 sharedScope, inject + eval + cleanup。
     */
    @Test
    fun testEvalJsPath2ReusesSharedScope() {
        val sharedScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val (result, _) = evalJSLikeAnalyzeRule(
                jsStr = "result + '|' + baseUrl;",
                result = "test",
                topScopeRef = sharedScope
            )
            assertEquals("test|https://api.example.com", result.toString())

            // 验证 cleanup 后变量不泄漏
            val leaked = QuickJsEngine.eval("typeof result", sharedScope, null)
            assertEquals("undefined", leaked.toString())
        } finally {
            sharedScope.close()
        }
    }

    /**
     * 验证 bindings 注入的所有变量都可访问 (与 AnalyzeRule.buildScriptBindings 一致)。
     */
    @Test
    fun testAllBindingsAccessibleInJs() {
        val (result, scope) = evalJSLikeAnalyzeRule(
            """
            var parts = [];
            parts.push(typeof java);
            parts.push(typeof cookie);
            parts.push(typeof cache);
            parts.push(typeof source);
            parts.push(typeof book);
            parts.push(typeof result);
            parts.push(typeof baseUrl);
            parts.push(typeof chapter);
            parts.push(typeof title);
            parts.push(typeof src);
            parts.push(typeof nextChapterUrl);
            parts.join(',');
        """.trimIndent(), result = "test-result"
        )
        try {
            assertEquals(
                "object,object,object,object,object,string,string,object,string,string,string",
                result.toString()
            )
        } finally {
            scope.close()
        }
    }

    // ============ wrapJsForEval 隔离验证 ============

    /**
     * 验证 wrapJsForEval 的 IIFE 包裹让 let/const 不污染 topScope。
     *
     * 修复点 (AnalyzeRule.kt:790-792):
     * - let/const 留在 eval 词法环境 (IIFE 函数作用域), 不污染 topScope
     * - 避免重复执行报 "redeclaration of 'xxx'"
     */
    @Test
    fun testWrapJsForEvalIsolatesLetConst() {
        val sharedScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val scriptCache = hashMapOf<String, CompiledScript>()

            // 两次执行含 let url 的 JS, 不应报 redeclaration
            val (r1, _) = evalJSLikeAnalyzeRule(
                jsStr = "let url = baseUrl + '/path'; url;",
                topScopeRef = sharedScope,
                scriptCache = scriptCache
            )
            val (r2, _) = evalJSLikeAnalyzeRule(
                jsStr = "let url = baseUrl + '/other'; url;",
                topScopeRef = sharedScope,
                scriptCache = scriptCache
            )

            assertEquals("https://api.example.com/path", r1.toString())
            assertEquals("https://api.example.com/other", r2.toString())

            // 全局 url 应未定义 (IIFE 内 let 不污染全局)
            val global = QuickJsEngine.eval("typeof url", sharedScope, null)
            assertEquals("undefined", global.toString())
        } finally {
            sharedScope.close()
        }
    }

    /**
     * 验证 wrapJsForEval 保留返回值 (最后一个表达式)。
     *
     * 修复点 (AnalyzeRule.kt:793): eval 返回末尾表达式值 (模拟 rhino script.exec)
     */
    @Test
    fun testWrapJsForEvalPreservesReturnValue() {
        val (result, scope) = evalJSLikeAnalyzeRule(
            """
            var x = 1;
            var y = 2;
            x + y;
        """.trimIndent()
        )
        try {
            assertEquals(3, (result as Number).toInt())
        } finally {
            scope.close()
        }
    }

    /**
     * 验证 wrapJsForEval 内 eval 可访问注入的 bindings 变量。
     *
     * 修复点 (AnalyzeRule.kt:794): bindings 变量通过 globalThis 注入, IIFE 内可访问
     */
    @Test
    fun testWrapJsForEvalAccessesBindings() {
        val (result, scope) = evalJSLikeAnalyzeRule(
            """
            result.toUpperCase() + ' @ ' + baseUrl;
        """.trimIndent(), result = "hello"
        )
        try {
            assertEquals("HELLO @ https://api.example.com", result.toString())
        } finally {
            scope.close()
        }
    }

    // ============ scriptCache 编译缓存验证 ============

    /**
     * 验证 scriptCache 缓存复用 (AnalyzeRule.compileScriptCache)。
     *
     * 真实代码 (AnalyzeRule.kt:836-838):
     * ```kotlin
     * return scriptCache.getOrPutLimit(jsStr, 16) {
     *     QuickJsEngine.compile(jsStr)
     * }
     * ```
     *
     * 修复点: bytecode 在同一 scope 上可重复执行 (模拟 AnalyzeRule.formatJs 循环)。
     */
    @Test
    fun testScriptCacheReusesCompiledBytecode() {
        val sharedScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val scriptCache = hashMapOf<String, CompiledScript>()
            val jsStr = QuickJsEngine.wrapJsForEval("result + ' ' + baseUrl;")

            // 第一次调用: 编译并缓存
            val (r1, _) = evalJSLikeAnalyzeRule(
                jsStr = "result + ' ' + baseUrl;",
                result = "first",
                topScopeRef = sharedScope,
                scriptCache = scriptCache
            )
            assertEquals("scriptCache should have 1 entry after first call", 1, scriptCache.size)

            // 第二次调用相同 JS: 应复用缓存
            val (r2, _) = evalJSLikeAnalyzeRule(
                jsStr = "result + ' ' + baseUrl;",
                result = "second",
                topScopeRef = sharedScope,
                scriptCache = scriptCache
            )
            assertEquals("scriptCache should still have 1 entry (reused)", 1, scriptCache.size)

            assertEquals("first https://api.example.com", r1.toString())
            assertEquals("second https://api.example.com", r2.toString())
        } finally {
            sharedScope.close()
        }
    }

    /**
     * 验证 scriptCache 不同 JS 编译不同 bytecode。
     */
    @Test
    fun testScriptCacheCompilesDifferentJsSeparately() {
        val sharedScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val scriptCache = hashMapOf<String, CompiledScript>()

            evalJSLikeAnalyzeRule(
                jsStr = "result + '_a';",
                result = "x",
                topScopeRef = sharedScope,
                scriptCache = scriptCache
            )
            evalJSLikeAnalyzeRule(
                jsStr = "result + '_b';",
                result = "x",
                topScopeRef = sharedScope,
                scriptCache = scriptCache
            )

            assertEquals("scriptCache should have 2 entries for different JS", 2, scriptCache.size)
        } finally {
            sharedScope.close()
        }
    }

    // ============ bindings 互操作验证 ============

    /**
     * 验证 java binding (AnalyzeRule this) 可调用 ajax 方法。
     *
     * 真实场景: 书源 JS 调用 java.ajax(url) 发同步请求。
     * 这里用 MockAnalyzeRule, 验证 JS->Java 方法调用链路。
     */
    @Test
    fun testJavaBindingAjaxCall() {
        val (result, scope) = evalJSLikeAnalyzeRule(
            """
            java.ajax('https://api.example.com/data');
        """.trimIndent()
        )
        try {
            assertEquals("mock-response-for: https://api.example.com/data", result.toString())
        } finally {
            scope.close()
        }
    }

    /**
     * 验证 java binding 可调用 putVar/getVar (变量持久化)。
     *
     * 注意: 真实 AnalyzeRule 的 java binding 是 this (AnalyzeRule 实例),
     * 跨多次 evalJS 调用保持同一实例, putVar 存的变量 getVar 能读到。
     * 这里通过 mockJava 参数传入同一 MockAnalyzeRule 实例模拟此行为。
     */
    @Test
    fun testJavaBindingPutVarAndGetVar() {
        val sharedScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val scriptCache = hashMapOf<String, CompiledScript>()
            // 同一 MockAnalyzeRule 实例, 跨多次 eval 保持状态 (模拟真实 AnalyzeRule this)
            val mockJava = MockAnalyzeRule()

            // 第一次: putVar 存变量
            evalJSLikeAnalyzeRule(
                jsStr = "java.putVar('myKey', 'myValue'); null;",
                topScopeRef = sharedScope,
                scriptCache = scriptCache,
                mockJava = mockJava
            )

            // 第二次: getVar 读变量 (验证 java binding 状态保持)
            val (r2, _) = evalJSLikeAnalyzeRule(
                jsStr = "java.getVar('myKey');",
                topScopeRef = sharedScope,
                scriptCache = scriptCache,
                mockJava = mockJava
            )
            assertEquals("myValue", r2.toString())
        } finally {
            sharedScope.close()
        }
    }

    /**
     * 验证 cookie/cache bindings 可访问 (Map 互操作)。
     */
    @Test
    fun testCookieAndCacheBindingsAccess() {
        val (result, scope) = evalJSLikeAnalyzeRule(
            """
            var sid = cookie.get('sessionid');
            var token = cookie.get('token');
            var lastSearch = cache.get('lastSearch');
            sid + '|' + token + '|' + lastSearch;
        """.trimIndent()
        )
        try {
            assertEquals("test-session|abc123|玄幻", result.toString())
        } finally {
            scope.close()
        }
    }

    /**
     * 验证 source/book bindings 可访问字段 (Map 互操作)。
     */
    @Test
    fun testSourceAndBookBindingsFieldAccess() {
        val (result, scope) = evalJSLikeAnalyzeRule(
            """
            var sourceUrl = source.get('bookSourceUrl');
            var sourceName = source.get('bookSourceName');
            var bookName = book.get('name');
            var bookAuthor = book.get('author');
            sourceName + ':' + sourceUrl + '|' + bookName + ' by ' + bookAuthor;
        """.trimIndent()
        )
        try {
            assertEquals("测试源:https://example.com|测试小说 by 测试作者", result.toString())
        } finally {
            scope.close()
        }
    }

    /**
     * 验证 chapter/title bindings 可访问。
     */
    @Test
    fun testChapterAndTitleBindingsAccess() {
        val (result, scope) = evalJSLikeAnalyzeRule(
            """
            var ch = chapter;
            var t = title;
            var chTitle = ch.get('title');
            var chUrl = ch.get('url');
            t + '|' + chTitle + '|' + chUrl;
        """.trimIndent()
        )
        try {
            assertEquals("第1章|第1章|https://example.com/ch1", result.toString())
        } finally {
            scope.close()
        }
    }

    /**
     * 验证 src/nextChapterUrl bindings 可访问。
     */
    @Test
    fun testSrcAndNextChapterUrlBindings() {
        val (result, scope) = evalJSLikeAnalyzeRule(
            """
            src.length + '|' + nextChapterUrl;
        """.trimIndent()
        )
        try {
            assertEquals("7|https://example.com/ch2", result.toString())
        } finally {
            scope.close()
        }
    }

    // ============ 综合场景验证 ============

    /**
     * 综合场景: 模拟书源 ruleBookInfo.init 的完整 JS。
     *
     * 真实书源 init JS 示例:
     * ```js
     * var url = baseUrl + '/api/book/' + book.get('id');
     * var headers = { 'User-Agent': 'okhttp/3.12.3' };
     * var response = java.ajax(url);
     * var data = JSON.parse(response);
     * java.putVar('bookId', data.id);
     * data.name + '|' + data.author;
     * ```
     *
     * 这里用 mock 数据验证完整流程。
     */
    @Test
    fun testCompositeRuleBookInfoInit() {
        val (result, scope) = evalJSLikeAnalyzeRule(
            """
            var url = baseUrl + '/api/book/' + '12345';
            var response = java.ajax(url);
            // 模拟解析响应 (MockAnalyzeRule.ajax 返回 "mock-response-for: <url>")
            var responseLen = response.length;
            java.putVar('lastUrl', url);
            var savedUrl = java.getVar('lastUrl');
            responseLen + '|' + (savedUrl === url);
        """.trimIndent()
        )
        try {
            // response = "mock-response-for: https://api.example.com/api/book/12345"
            // "mock-response-for: " (19) + "https://api.example.com/api/book/12345" (38) = 57
            // savedUrl === url = true
            val expected = "57|true"
            assertEquals(expected, result.toString())
        } finally {
            scope.close()
        }
    }

    /**
     * 综合场景: 模拟多本书籍列表解析 (循环 evalJS)。
     *
     * 验证 sharedScope 复用 + bindings 隔离 + scriptCache 缓存。
     */
    @Test
    fun testBookListParsingLoop() {
        val sharedScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val scriptCache = hashMapOf<String, CompiledScript>()
            val books = listOf(
                mapOf("name" to "斗破苍穹", "author" to "天蚕土豆"),
                mapOf("name" to "凡人修仙传", "author" to "忘语"),
                mapOf("name" to "诡秘之主", "author" to "爱潜水的乌贼")
            )

            val results = books.map { book ->
                val bindings = buildMockBindings(result = book, book = book)
                val wrappedJs = QuickJsEngine.wrapJsForEval(
                    """
                    var name = result.get('name');
                    var author = result.get('author');
                    name + ' - ' + author;
                """.trimIndent()
                )
                val compiled = scriptCache.getOrPut(wrappedJs) {
                    QuickJsEngine.compile(wrappedJs, sharedScope)
                }
                val keys = QuickJsEngine.injectBindings(sharedScope, bindings)
                try {
                    compiled.eval(sharedScope, null)?.toString()
                } finally {
                    QuickJsEngine.cleanupBindings(sharedScope, keys)
                }
            }

            assertEquals(
                listOf(
                    "斗破苍穹 - 天蚕土豆",
                    "凡人修仙传 - 忘语",
                    "诡秘之主 - 爱潜水的乌贼"
                ), results
            )

            // 验证 scriptCache 只缓存 1 条 (相同 JS 复用)
            assertEquals(1, scriptCache.size)
        } finally {
            sharedScope.close()
        }
    }

    /**
     * 综合场景: 模拟 chapter.toc JS (含 cookie + cache + dangerousApi)。
     */
    @Test
    fun testChapterTocWithDangerousApi() {
        val sharedScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val bindings = buildScriptBindings { b ->
                b["java"] = MockAnalyzeRule()
                b["cookie"] = mapOf("sessionid" to "test")
                b["cache"] = mapOf("tocUrl" to "https://example.com/toc")
                b["result"] = "chapter-list-json"
                b["baseUrl"] = "https://example.com"
                b["chapter"] = mapOf("title" to "目录", "url" to "https://example.com/toc")
                b["title"] = "目录"
                b["src"] = "toc-content"
                b["nextChapterUrl"] = "https://example.com/ch1"
                b.dangerousApi = true  // 启用 dangerousApi
            }

            val keys = QuickJsEngine.injectBindings(sharedScope, bindings)
            try {
                val wrappedJs = QuickJsEngine.wrapJsForEval(
                    """
                    var sid = cookie.get('sessionid');
                    var tocUrl = cache.get('tocUrl');
                    var chTitle = title;
                    var chUrl = chapter.get('url');
                    sid + '|' + tocUrl + '|' + chTitle + '|' + chUrl;
                """.trimIndent()
                )
                val compiled = QuickJsEngine.compile(wrappedJs, sharedScope)
                val result = compiled.eval(sharedScope, null)
                assertEquals(
                    "test|https://example.com/toc|目录|https://example.com/toc",
                    result.toString()
                )
            } finally {
                QuickJsEngine.cleanupBindings(sharedScope, keys)
            }
        } finally {
            sharedScope.close()
        }
    }

    // ============ 空值与边界情况 ============

    /**
     * 验证 evalJS 空字符串早返回 (AnalyzeRule.kt:801)。
     *
     * 真实代码: `if (jsStr.isBlank()) return null`
     *
     * 注意: evalJSLikeAnalyzeRule 在 jsStr 为空时返回 null to topScopeRef,
     * 需要传入 sharedScope 避免触发 error("no scope")。
     */
    @Test
    fun testEvalJsBlankStringReturnsNull() {
        val sharedScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val (result, _) = evalJSLikeAnalyzeRule("", topScopeRef = sharedScope)
            assertNull("blank JS should return null", result)
        } finally {
            sharedScope.close()
        }
    }

    /**
     * 验证 result = null 时 JS 侧 result 为 null。
     */
    @Test
    fun testEvalJsWithNullResult() {
        val (result, scope) = evalJSLikeAnalyzeRule(
            """
            result === null ? 'is-null' : 'not-null';
        """.trimIndent(), result = null
        )
        try {
            assertEquals("is-null", result.toString())
        } finally {
            scope.close()
        }
    }

    /**
     * 验证 result 为 Java 对象时 JS 侧可访问其方法。
     */
    @Test
    fun testEvalJsWithJavaObjectResult() {
        val sb = StringBuilder("result-content")
        val sharedScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val bindings = buildMockBindings(result = sb)
            val keys = QuickJsEngine.injectBindings(sharedScope, bindings)
            try {
                val wrappedJs = QuickJsEngine.wrapJsForEval(
                    """
                    result.append('-suffix').toString();
                """.trimIndent()
                )
                val compiled = QuickJsEngine.compile(wrappedJs, sharedScope)
                val r = compiled.eval(sharedScope, null)
                assertEquals("result-content-suffix", r.toString())
            } finally {
                QuickJsEngine.cleanupBindings(sharedScope, keys)
            }
        } finally {
            sharedScope.close()
        }
    }

    /**
     * 验证 result 为 List 时 JS 侧可访问索引和 size。
     */
    @Test
    fun testEvalJsWithListResult() {
        val list = listOf("a", "b", "c")
        val (result, scope) = evalJSLikeAnalyzeRule(
            """
            var size = result.size();
            var first = result.get(0);
            var last = result.get(size - 1);
            size + '|' + first + '|' + last;
        """.trimIndent(), result = list
        )
        try {
            assertEquals("3|a|c", result.toString())
        } finally {
            scope.close()
        }
    }

    /**
     * 验证 result 为 Map 时 JS 侧可访问 key。
     */
    @Test
    fun testEvalJsWithMapResult() {
        val map = mapOf("name" to "测试", "value" to 42)
        val (result, scope) = evalJSLikeAnalyzeRule(
            """
            var name = result.get('name');
            var value = result.get('value');
            name + '|' + value;
        """.trimIndent(), result = map
        )
        try {
            assertEquals("测试|42", result.toString())
        } finally {
            scope.close()
        }
    }

    // ============ 重复执行与隔离 ============

    /**
     * 验证 sharedScope 多次复用不污染 (SharedJsScope 场景)。
     *
     * 修复点: cleanupBindings 应删除注入的全局变量, 实现子 scope 隔离。
     * 模拟两个书源复用同一 sharedScope, 通过 cleanupBindings 实现隔离。
     */
    @Test
    fun testSharedScopeReuseWithDifferentSources() {
        val sharedScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val scriptCache = hashMapOf<String, CompiledScript>()

            // 模拟源 A: 注入 result + 自定义变量
            val bindingsA = buildScriptBindings { b ->
                b["result"] = "from-A"
                b["custom"] = "A-specific"
            }
            val keysA = QuickJsEngine.injectBindings(sharedScope, bindingsA)
            try {
                val wrappedJs = QuickJsEngine.wrapJsForEval("result + '|' + custom;")
                val compiled = scriptCache.getOrPut(wrappedJs) {
                    QuickJsEngine.compile(wrappedJs, sharedScope)
                }
                val rA = compiled.eval(sharedScope, null)
                assertEquals("from-A|A-specific", rA.toString())
            } finally {
                QuickJsEngine.cleanupBindings(sharedScope, keysA)
            }

            // 模拟源 B: 验证 A 的变量已清理, 不会泄漏
            val bindingsB = buildScriptBindings { b ->
                b["result"] = "from-B"
            }
            val keysB = QuickJsEngine.injectBindings(sharedScope, bindingsB)
            try {
                val wrappedJs = QuickJsEngine.wrapJsForEval(
                    """
                    result + '|' + typeof custom;
                """.trimIndent()
                )
                val compiled = scriptCache.getOrPut(wrappedJs) {
                    QuickJsEngine.compile(wrappedJs, sharedScope)
                }
                val rB = compiled.eval(sharedScope, null)
                assertEquals("from-B|undefined", rB.toString())  // A 的 custom 已清理
            } finally {
                QuickJsEngine.cleanupBindings(sharedScope, keysB)
            }
        } finally {
            sharedScope.close()
        }
    }

    /**
     * 验证同一 JS 多次执行结果一致 (编译缓存复用)。
     */
    @Test
    fun testRepeatedExecutionConsistent() {
        val sharedScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val scriptCache = hashMapOf<String, CompiledScript>()
            val jsStr = "result + ' ' + baseUrl;"

            // 执行 5 次, 每次注入不同 result
            val results = (1..5).map { i ->
                val bindings = buildMockBindings(result = "run-$i")
                val keys = QuickJsEngine.injectBindings(sharedScope, bindings)
                try {
                    val wrappedJs = QuickJsEngine.wrapJsForEval(jsStr)
                    val compiled = scriptCache.getOrPut(wrappedJs) {
                        QuickJsEngine.compile(wrappedJs, sharedScope)
                    }
                    compiled.eval(sharedScope, null)?.toString()
                } finally {
                    QuickJsEngine.cleanupBindings(sharedScope, keys)
                }
            }

            assertEquals(
                listOf(
                    "run-1 https://api.example.com",
                    "run-2 https://api.example.com",
                    "run-3 https://api.example.com",
                    "run-4 https://api.example.com",
                    "run-5 https://api.example.com"
                ), results
            )
            assertEquals(1, scriptCache.size)  // 同一 JS 只编译一次
        } finally {
            sharedScope.close()
        }
    }
}
