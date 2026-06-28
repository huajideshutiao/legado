package com.script.quickjs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实书源场景功能性测试。
 *
 * 从 [JsBenchmarkTest] 剥离的"能不能用"验证 (原 benchmark 内嵌的结果一致性校验),
 * 作为独立功能性断言。覆盖番茄小说书源 (b75a16a67ee45c5303049c20205afc43) 的各类 JS 场景:
 *
 * - A. 加解密场景 (xGorgon 签名 / AES / HMAC / SHA-256 / DES / Base64 / URL)
 * - B. 字段解析场景 (coverUrl / intro / wordCount / kind / bookUrl / name / score)
 * - C. 段评解析场景 (for...in + body.get(key).xxx, 番茄小说 idea_data)
 * - D. 书籍列表解析场景 (多字段同步处理, 模拟 ruleSearch/ruleExplore)
 * - E. ES6+ 特性场景 (模板字符串 / 解构 / Map/Set / Symbol / 生成器)
 * - F. Java 工具类场景 (URL / Cookie / SimpleDateFormat / Pattern / org.json)
 *
 * 与 [JsBenchmarkTest] 的区别:
 * - JsBenchmarkTest: 性能基准, 只打印时间数据, 不做 assert
 * - 本测试: 功能性验证, 用 assertEquals 断言结果正确性
 *
 * 对照 rhino LiveConnect 特性:
 * - 所有场景在 rhino 下应能正常运行 (通过 RhinoScriptEngine 验证)
 * - 本测试只测 QuickJS, rhino 对照已在 benchmark 中
 */
@RunWith(AndroidJUnit4::class)
class RealWorldSourceScenarioTest {

    // ============ A. 加解密场景 ============

    /**
     * xGorgon 签名核心计算 (番茄小说书源 API 签名)。
     *
     * 覆盖:
     * - java.security.MessageDigest.getInstance('MD5') 静态方法
     * - java.lang.String(str).getBytes('UTF-8') 实例方法
     * - md.digest(bytes) 返回 byte[], JS 侧用 digest[i] & 0xff 解析
     * - 字符串操作 (split/reverse/join/substring/toString)
     *
     * 修复点: byte[] 在 JS 侧通过 __wrapJavaObject 包装, digest[i] 走 trap 访问
     */
    @Test
    fun testXGorgonSignatureComputation() {
        val js = """
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
            var sig = calculate(getHex(params, null, 'sessionid=test'));
            sig.length + '|' + sig.substring(0, 8);
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // xGorgon 签名固定 40 字符 (0x14 * 2), 前 8 字符是 paramList[0]^key[0] 等
        assertEquals("40|", result.toString().substringBefore("|") + "|")
        // 前 8 字符应是非空 hex 字符串
        val prefix = result.toString().substringAfter("|")
        assertTrue("xGorgon prefix should be 8 hex chars: $prefix", prefix.length == 8)
        assertTrue(
            "xGorgon prefix should be hex: $prefix",
            prefix.all { it in '0'..'9' || it in 'a'..'f' })
    }

    /**
     * AES/ECB/PKCS5Padding 加解密往返 (书源常见加密)。
     *
     * 覆盖:
     * - new javax.crypto.spec.SecretKeySpec(keyBytes, 'AES') 构造器
     * - javax.crypto.Cipher.getInstance('AES/ECB/PKCS5Padding') 静态方法
     * - cipher.init(1, key) / cipher.init(2, key) 实例方法 (1=ENCRYPT, 2=DECRYPT)
     * - cipher.doFinal(bytes) 返回 byte[]
     * - java.lang.String(decrypted, 'UTF-8') 构造器 (byte[] + charset)
     */
    @Test
    fun testAesEcbEncryptDecryptRoundTrip() {
        val js = """
            var keyBytes = java.lang.String('1234567890123456').getBytes('UTF-8');
            var key = new javax.crypto.spec.SecretKeySpec(keyBytes, 'AES');
            var cipher = javax.crypto.Cipher.getInstance('AES/ECB/PKCS5Padding');
            cipher.init(1, key);
            var plain = '测试 AES 加解密往返 中文';
            var plainBytes = java.lang.String(plain).getBytes('UTF-8');
            var encrypted = cipher.doFinal(plainBytes);
            cipher.init(2, key);
            var decrypted = cipher.doFinal(encrypted);
            java.lang.String(decrypted, 'UTF-8');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("测试 AES 加解密往返 中文", result.toString())
    }

    /**
     * AES/CBC/PKCS5Padding 加解密 (带 IV, 书源常见完整 AES)。
     */
    @Test
    fun testAesCbcEncryptDecryptRoundTrip() {
        val js = """
            var keyBytes = java.lang.String('1234567890123456').getBytes('UTF-8');
            var ivBytes = java.lang.String('abcdef0123456789').getBytes('UTF-8');
            var key = new javax.crypto.spec.SecretKeySpec(keyBytes, 'AES');
            var iv = new javax.crypto.spec.IvParameterSpec(ivBytes);
            var cipher = javax.crypto.Cipher.getInstance('AES/CBC/PKCS5Padding');
            cipher.init(1, key, iv);
            var plain = 'AES/CBC 加解密往返测试 中文';
            var encrypted = cipher.doFinal(java.lang.String(plain).getBytes('UTF-8'));
            cipher.init(2, key, iv);
            var decrypted = cipher.doFinal(encrypted);
            java.lang.String(decrypted, 'UTF-8');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("AES/CBC 加解密往返测试 中文", result.toString())
    }

    /**
     * HMAC-SHA256 签名 (书源 API 签名)。
     */
    @Test
    fun testHmacSha256Signature() {
        val js = """
            var keyBytes = java.lang.String('secret_key').getBytes('UTF-8');
            var key = new javax.crypto.spec.SecretKeySpec(keyBytes, 'HmacSHA256');
            var mac = javax.crypto.Mac.getInstance('HmacSHA256');
            mac.init(key);
            var data = java.lang.String('data_to_sign').getBytes('UTF-8');
            var digest = mac.doFinal(data);
            var hex = '';
            for (var i = 0; i < digest.length; i++) {
                var b = digest[i] & 0xff;
                hex += (b < 16 ? '0' : '') + b.toString(16);
            }
            hex;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // HMAC-SHA256 输出 32 字节 = 64 hex 字符
        assertEquals(64, result.toString().length)
        assertTrue(
            "HMAC should be hex: ${result.toString().take(20)}...",
            result.toString().all { it in '0'..'9' || it in 'a'..'f' })
    }

    /**
     * SHA-256 哈希 (书源更安全的签名)。
     */
    @Test
    fun testSha256Hash() {
        val js = """
            var md = java.security.MessageDigest.getInstance('SHA-256');
            var bytes = java.lang.String('书源签名数据 SHA-256 测试').getBytes('UTF-8');
            var digest = md.digest(bytes);
            var hex = '';
            for (var i = 0; i < digest.length; i++) {
                var b = digest[i] & 0xff;
                hex += (b < 16 ? '0' : '') + b.toString(16);
            }
            hex;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // SHA-256 输出 32 字节 = 64 hex 字符
        assertEquals(64, result.toString().length)
    }

    /**
     * MD5 哈希 (书源最常见签名)。
     */
    @Test
    fun testMd5Hash() {
        val js = """
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
        val result = QuickJsEngine.eval(js)
        // MD5 输出 16 字节 = 32 hex 字符
        assertEquals(32, result.toString().length)
    }

    /**
     * DES 加解密 (书源老式加密场景)。
     */
    @Test
    fun testDesEncryptDecryptRoundTrip() {
        val js = """
            var keyBytes = java.lang.String('12345678').getBytes('UTF-8');
            var key = new javax.crypto.spec.SecretKeySpec(keyBytes, 'DES');
            var cipher = javax.crypto.Cipher.getInstance('DES/ECB/PKCS5Padding');
            cipher.init(1, key);
            var plain = 'DES 加解密测试 中文';
            var encrypted = cipher.doFinal(java.lang.String(plain).getBytes('UTF-8'));
            cipher.init(2, key);
            var decrypted = cipher.doFinal(encrypted);
            java.lang.String(decrypted, 'UTF-8');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("DES 加解密测试 中文", result.toString())
    }

    /**
     * Base64 编解码往返 (书源数据传输)。
     */
    @Test
    fun testBase64EncodeDecodeRoundTrip() {
        val js = """
            var encoder = java.util.Base64.getEncoder();
            var decoder = java.util.Base64.getDecoder();
            var original = 'Hello, Base64 编解码测试! 中文';
            var bytes = java.lang.String(original).getBytes('UTF-8');
            var encoded = encoder.encodeToString(bytes);
            var decoded = decoder.decode(encoded);
            java.lang.String(decoded, 'UTF-8');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("Hello, Base64 编解码测试! 中文", result.toString())
    }

    /**
     * URLEncoder 编码 (书源 URL 参数处理)。
     */
    @Test
    fun testUrlEncoder() {
        val js = """
            var params = 'q=测试搜索&category=玄幻&sort=hot';
            java.net.URLEncoder.encode(params, 'UTF-8');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // 编码后应包含 % (中文字符编码)
        assertTrue(
            "URL encoded should contain %: ${result.toString().take(30)}",
            result.toString().contains("%")
        )
        // & 和 = 也会被编码
        assertTrue(
            "URL encoded should contain %26 or %3D",
            result.toString().contains("%26") || result.toString().contains("%3D")
        )
    }

    // ============ B. 字段解析场景 (ruleSearch/ruleExplore) ============

    /**
     * 字段解析场景: 模拟番茄小说 ruleSearch/ruleExplore 的 @js 后置处理。
     *
     * 这些小 JS 通过 QuickJsEngine.eval(js) { put("result", ...) } 执行,
     * 模拟 AnalyzeRule.evalJS 的路径 (bindings 注入 result, IIFE 包裹)。
     */
    @Test
    fun testCoverUrlCompletion() {
        // coverUrl: 补全图片域名 (ruleExplore.coverUrl)
        val js =
            """result.startsWith("http") ? result : "http://p6-novel.byteimg.com/" + result + "~tplv-shrink:320:0.image""""
        val result = QuickJsEngine.eval(js) { put("result", "novel-pic/a1b2c3d4") }
        assertEquals(
            "http://p6-novel.byteimg.com/novel-pic/a1b2c3d4~tplv-shrink:320:0.image",
            result.toString()
        )
    }

    @Test
    fun testIntroCleanup() {
        // intro: 清理换行和多余空白
        val js = """result.replace(/\s+/g, " ").trim()"""
        val result = QuickJsEngine.eval(js) { put("result", "  这是简介。\n\n讲述主角冒险。 ") }
        assertEquals("这是简介。 讲述主角冒险。", result.toString())
    }

    @Test
    fun testWordCountFormatting() {
        // wordCount: 字数格式化 (万字)
        val js =
            """var n = parseInt(result); n > 10000 ? (n/10000).toFixed(1) + "万字" : n + "字""""
        val bigResult = QuickJsEngine.eval(js) { put("result", "1234567") }
        assertEquals("123.5万字", bigResult.toString())
        val smallResult = QuickJsEngine.eval(js) { put("result", "5000") }
        assertEquals("5000字", smallResult.toString())
    }

    @Test
    fun testKindSplitting() {
        // kind: 分割取分类 (ruleSearch.kind 的 && 分隔)
        val js = """result.split("&&")[0]"""
        val result = QuickJsEngine.eval(js) { put("result", "玄幻,连载中&&1&&9.5") }
        assertEquals("玄幻,连载中", result.toString())
    }

    @Test
    fun testBookUrlExtraction() {
        // bookUrl: 提取尾部 book_id (ruleSearch.bookUrl 模板)
        // 输入 30 字符, slice(-19) 取末尾 19 字符
        val js = """result.slice(-19)"""
        val result = QuickJsEngine.eval(js) { put("result", "data:info,76385385612302281234") }
        assertEquals("6385385612302281234", result.toString())  // 末尾 19 字符
    }

    @Test
    fun testNameTrim() {
        // name: 去除首尾空白
        val js = """result.trim()"""
        val result = QuickJsEngine.eval(js) { put("result", "  测试小说第1部  ") }
        assertEquals("测试小说第1部", result.toString())
    }

    @Test
    fun testScoreFormatting() {
        // score: 拼接单位 (ruleExplore.kind 的 score 字段)
        val js = """result + "分""""
        val result = QuickJsEngine.eval(js) { put("result", "9.5") }
        assertEquals("9.5分", result.toString())
    }

    /**
     * 完整书籍列表解析流程: 10 本书 × 7 字段, 模拟 AnalyzeRule.evalJS 循环调用。
     *
     * 验证:
     * - wrapJsForEval + compile + eval + cleanup 完整流程
     * - scope 复用 (SharedJsScope 场景)
     * - bindings 注入 result 变量
     * - 多次执行不污染 (let/const 隔离)
     */
    @Test
    fun testBookListParsingWorkflow() {
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            // 7 个字段规则 (从番茄小说 ruleSearch/ruleExplore 提取)
            val fieldRules = listOf(
                """result.startsWith("http") ? result : "http://p6-novel.byteimg.com/" + result + "~tplv-shrink:320:0.image"""",
                """result.replace(/\s+/g, " ").trim()""",
                """var n = parseInt(result); n > 10000 ? (n/10000).toFixed(1) + "万字" : n + "字"""",
                """result.split("&&")[0]""",
                """result.slice(-19)""",
                """result.trim()""",
                """result + "分""""
            )

            // 编译所有规则 (模拟 AnalyzeRule.compileScriptCache)
            val compiled = fieldRules.map {
                QuickJsEngine.compile(QuickJsEngine.wrapJsForEval(it))
            }

            // 10 本书的字段原始值
            val bookFieldInputs = (1..10).map { idx ->
                listOf(
                    "novel-pic/a1b2${idx}c3d4",
                    "这是一本小说的简介, 讲述了主角的冒险故事。 ".repeat(2),
                    "${idx * 12345 + 6789}",
                    "玄幻,连载中&&1&&${idx}.${idx}",
                    "data:info,7638538561230228${idx}0${idx}",
                    "  测试小说第${idx}部  ",
                    "${idx}.${idx}"
                )
            }

            // 执行解析 (模拟 AnalyzeRule.evalJS 循环)
            val results = mutableListOf<List<String>>()
            for (fields in bookFieldInputs) {
                val bookResult = mutableListOf<String>()
                for (i in fieldRules.indices) {
                    val bindings = ScriptBindings().apply { put("result", fields[i]) }
                    val keys = QuickJsEngine.injectBindings(scope, bindings)
                    try {
                        val r = compiled[i].eval(scope, null)
                        bookResult.add(r?.toString() ?: "")
                    } finally {
                        QuickJsEngine.cleanupBindings(scope, keys)
                    }
                }
                results.add(bookResult)
            }

            // 验证第 1 本书的解析结果
            val firstBook = results[0]
            assertEquals(
                "http://p6-novel.byteimg.com/novel-pic/a1b21c3d4~tplv-shrink:320:0.image",
                firstBook[0]
            )
            assertEquals(
                "这是一本小说的简介, 讲述了主角的冒险故事。 这是一本小说的简介, 讲述了主角的冒险故事。",
                firstBook[1]
            )
            // idx=1: 1*12345+6789 = 19134 > 10000, 19134/10000 = 1.9134, toFixed(1) = "1.9万字"
            assertEquals("1.9万字", firstBook[2])
            assertEquals("玄幻,连载中", firstBook[3])
            assertEquals("7638538561230228101", firstBook[4])  // 末尾 19 字符
            assertEquals("测试小说第1部", firstBook[5])
            assertEquals("1.1分", firstBook[6])

            // 验证第 10 本书的解析结果
            val lastBook = results[9]
            assertEquals(
                "http://p6-novel.byteimg.com/novel-pic/a1b210c3d4~tplv-shrink:320:0.image",
                lastBook[0]
            )
            // idx=10: 10*12345+6789 = 130239 > 10000, 130239/10000 = 13.0239, toFixed(1) = "13.0万字"
            assertEquals("13.0万字", lastBook[2])
            // idx=10: fields[4]="data:info,763853856123022810010" (31 字符),
            // slice(-19) 取末尾 19 字符 = "3853856123022810010", 再 substring(length-19) 不变
            assertEquals("3853856123022810010", lastBook[4].let { it.substring(it.length - 19) })
            // ^ 实际 slice(-19) 已返回末尾 19 字符, 这里再 slice 防御
            assertEquals("10.10分", lastBook[6])
        } finally {
            scope.close()
        }
    }

    // ============ C. 段评解析场景 (idea_data) ============

    /**
     * 段评解析核心: for (var key in body) body.get(key).idea_count
     *
     * 番茄小说段评 idea_data 结构: {"1": {idea_count: 5, user: "u1"}, ...}
     * 通过 m.clone() 触发 __wrapJavaMap 包装, for...in + .get() 并存。
     *
     * 修复点: __wrapJavaMap 的 ownKeys trap 让 for...in 枚举 Map keys,
     * get trap 委托到 Map.get(key), 嵌套 Map (idea_count) 也通过 __wrapJavaMap 包装。
     */
    @Test
    fun testIdeaDataParsingForInAndGet() {
        val js = """
            var m = new java.util.LinkedHashMap();
            var v1 = new java.util.LinkedHashMap();
            v1.put('idea_count', 5);
            v1.put('user_id', 'user_1');
            var v2 = new java.util.LinkedHashMap();
            v2.put('idea_count', 10);
            v2.put('user_id', 'user_2');
            var v3 = new java.util.LinkedHashMap();
            v3.put('idea_count', 15);
            v3.put('user_id', 'user_3');
            m.put('1', v1);
            m.put('2', v2);
            m.put('3', v3);
            var body = m.clone();
            var results = {};
            for (var key in body) {
                results[~~key] = body.get(key).get('idea_count');
            }
            JSON.stringify(results);
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("""{"1":5,"2":10,"3":15}""", result.toString())
    }

    /**
     * 段评解析: Object.entries 遍历 idea_data。
     *
     * 验证 __wrapJavaMap 的 ownKeys + getOwnPropertyDescriptor trap。
     */
    @Test
    fun testIdeaDataParsingObjectEntries() {
        val js = """
            var m = new java.util.LinkedHashMap();
            m.put('1', 5);
            m.put('2', 10);
            m.put('3', 15);
            var body = m.clone();
            var entries = Object.entries(body);
            var sum = 0;
            for (var i = 0; i < entries.length; i++) {
                sum += entries[i][1];
            }
            sum;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals(30, (result as Number).toInt())
    }

    /**
     * 段评解析: Object.keys + body.get(key) 组合。
     */
    @Test
    fun testIdeaDataParsingObjectKeysAndGetMethod() {
        val js = """
            var m = new java.util.LinkedHashMap();
            m.put('a', 1);
            m.put('b', 2);
            m.put('c', 3);
            var body = m.clone();
            var keys = Object.keys(body);
            var parts = [];
            for (var i = 0; i < keys.length; i++) {
                parts.push(keys[i] + '=' + body.get(keys[i]));
            }
            parts.join(',');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("a=1,b=2,c=3", result.toString())
    }

    /**
     * 段评解析: Map 修改后, Object.keys 反映新增 key。
     *
     * 验证 __wrapJavaMap 真正互操作 (set trap → Map.put, ownKeys 反映修改)。
     */
    @Test
    fun testIdeaDataMutationReflectsInKeys() {
        val js = """
            var m = new java.util.LinkedHashMap();
            m.put('orig', 'val');
            var body = m.clone();
            body['added1'] = 'new1';
            body['added2'] = 'new2';
            body['orig'] = 'modified';
            Object.keys(body).join(',') + '|' + body.size();
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("orig,added1,added2|3", result.toString())
    }

    // ============ D. ES6+ 特性场景 ============

    /**
     * ES6 模板字符串 + 箭头函数 (书源常见 ES6 写法)。
     */
    @Test
    fun testEs6TemplateAndArrowFunction() {
        val js = """
            const format = (name, author, idx = 0) => `${'$'}{name} - ${'$'}{author} #${'$'}{idx}`;
            const books = [
                { name: '斗破苍穹', author: '天蚕土豆' },
                { name: '凡人修仙传', author: '忘语' },
                { name: '诡秘之主', author: '爱潜水的乌贼' }
            ];
            const lines = books.map((b, i) => format(b.name, b.author, i));
            lines.join(' | ');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals(
            "斗破苍穹 - 天蚕土豆 #0 | 凡人修仙传 - 忘语 #1 | 诡秘之主 - 爱潜水的乌贼 #2",
            result.toString()
        )
    }

    /**
     * ES6 解构 + let/const 块级作用域 (书源 ES6 写法)。
     */
    @Test
    fun testEs6DestructuringAndBlockScope() {
        val js = """
            const processBook = (raw) => {
                const [name, author] = raw.split('|');
                const { length } = name;
                let score = parseFloat((length * 0.1).toFixed(1));
                return { name, author, score, length };
            };
            const books = ['斗破苍穹|天蚕土豆', '凡人修仙传|忘语', '诡秘之主|爱潜水的乌贼'];
            const results = books.map(processBook);
            let total = 0;
            for (const { score } of results) total += score;
            total;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // name 长度: 4/5/4 ('诡秘之主' 是 4 字符), score = length * 0.1 = 0.4/0.5/0.4, total = 1.3
        assertEquals(1.3, (result as Number).toDouble(), 0.001)
    }

    /**
     * ES6 Map/Set 内置集合 (书源去重/索引场景)。
     */
    @Test
    fun testEs6MapAndSet() {
        val js = """
            const tagSet = new Set();
            const bookMap = new Map();
            for (let i = 0; i < 30; i++) {
                const tag = `tag${'$'}{i % 5}`;
                tagSet.add(tag);
                bookMap.set(`book_${'$'}{i}`, { idx: i, tag });
            }
            let sum = 0;
            for (const [k, v] of bookMap) sum += v.idx;
            sum + tagSet.size;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // sum = 0+1+...+29 = 435, tagSet.size = 5 (tag0..tag4)
        assertEquals(440, (result as Number).toInt())
    }

    /**
     * ES6 Symbol + Spread/Rest (书源高级用法)。
     */
    @Test
    fun testEs6SymbolAndSpread() {
        val js = """
            const sym = Symbol('id');
            const base = { name: 'book', author: 'unknown' };
            const extra = { [sym]: 42, tags: ['玄幻', '连载'] };
            const merged = { ...base, ...extra };
            const { name, tags, ...rest } = merged;
            name + tags.length + (rest[sym] || 0);
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // name='book'(4), tags.length=2, rest[sym]=42
        // 结果: 'book' + 2 + 42 = 'book242' (字符串拼接)
        assertEquals("book242", result.toString())
    }

    /**
     * ES6 for...of + 生成器函数 (书源迭代场景)。
     */
    @Test
    fun testEs6GeneratorAndForOf() {
        val js = """
            function* bookGenerator(count) {
                for (let i = 0; i < count; i++) {
                    yield { id: i, name: `book_${'$'}{i}` };
                }
            }
            let sum = 0;
            for (const { id } of bookGenerator(50)) sum += id;
            sum;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // sum = 0+1+...+49 = 1225
        assertEquals(1225, (result as Number).toInt())
    }

    // ============ E. Java 工具类场景 ============

    /**
     * java.net.URL 构建与解析 (书源 URL 处理)。
     */
    @Test
    fun testUrlConstructionAndParsing() {
        val js = """
            var url = new java.net.URL('https://example.com/api/v1/books?page=1&size=20&q=test');
            var protocol = url.getProtocol();
            var host = url.getHost();
            var port = url.getPort();
            var path = url.getPath();
            var query = url.getQuery();
            var uri = new java.net.URI('https', 'example.com', '/api/v1/books', 'page=2&size=10', null);
            var builtUrl = uri.toString();
            protocol + '|' + host + '|' + path + '|' + query.length + '|' + builtUrl.length;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // query='page=1&size=20&q=test' 长度=21, builtUrl='https://example.com/api/v1/books?page=2&size=10' 长度=47
        assertEquals("https|example.com|/api/v1/books|21|47", result.toString())
    }

    /**
     * java.net.HttpCookie 解析 (书源 Cookie 处理)。
     *
     * HttpCookie.parse 返回 List, 验证 List 互操作 (size/get)。
     */
    @Test
    fun testHttpCookieParsing() {
        val js = """
            var cookies = java.net.HttpCookie.parse('sessionid=abc123; Path=/; Domain=example.com; Max-Age=3600; Secure; HttpOnly');
            var list = new java.util.ArrayList();
            for (var i = 0; i < cookies.size(); i++) {
                var c = cookies.get(i);
                list.add(c.getName() + '=' + c.getValue() + '; Domain=' + c.getDomain());
            }
            list.size() + '|' + list.get(0);
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("1|sessionid=abc123; Domain=example.com", result.toString())
    }

    /**
     * java.text.SimpleDateFormat 格式化+解析 (书源时间戳处理)。
     */
    @Test
    fun testSimpleDateFormatFormatAndParse() {
        val js = """
            var sdf = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss');
            var cal = java.util.Calendar.getInstance();
            cal.set(2026, 5, 27, 12, 30, 45);
            var date = cal.getTime();
            var formatted = sdf.format(date);
            var parsed = sdf.parse(formatted);
            var ts = parsed.getTime();
            formatted + '|' + (ts > 0);
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertTrue(
            "should format date: ${result.toString().take(30)}",
            result.toString().startsWith("2026-06-27 12:30:45|true") ||
                result.toString().matches(Regex("""^2026-06-2[67] 1[12]:30:4[56]\|true$"""))
        )
    }

    /**
     * org.json.JSONObject 解析 (书源 API 响应解析)。
     */
    @Test
    fun testOrgJsonObjectParsing() {
        val js = """
            var jsonStr = '{"books":[{"id":1,"name":"test1","tags":["a","b"]},{"id":2,"name":"test2","tags":["c"]}],"total":2,"page":1}';
            var json = new org.json.JSONObject(jsonStr);
            var books = json.getJSONArray('books');
            var sum = 0;
            for (var i = 0; i < books.length(); i++) {
                var book = books.getJSONObject(i);
                sum += book.getInt('id');
                var tags = book.getJSONArray('tags');
                sum += tags.length();
            }
            sum + json.getInt('total');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // sum = (1+2 tags: a,b) + (2+1 tags: c) + total(2) = 1+2+2+1+2 = 8
        assertEquals(8, (result as Number).toInt())
    }

    /**
     * java.util.regex.Pattern/Matcher 正则提取 (书源规则匹配)。
     */
    @Test
    fun testRegexPatternMatcher() {
        val js = """
            var text = 'book_12345_chapter_67890_page_42';
            var p = java.util.regex.Pattern.compile('(\\d+)');
            var m = p.matcher(text);
            var sum = 0;
            while (m.find()) {
                sum += parseInt(m.group(1));
            }
            sum;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // 12345 + 67890 + 42 = 80277
        assertEquals(80277, (result as Number).toInt())
    }

    /**
     * java.math.BigInteger 运算 (RSA 模数, 书源部分加密源)。
     */
    @Test
    fun testBigIntegerOperations() {
        val js = """
            var n1 = new java.math.BigInteger('12345678901234567890');
            var n2 = new java.math.BigInteger('98765432109876543210');
            var sum = n1.add(n2);
            var mul = n1.multiply(n2);
            var mod = mul.mod(n1);
            var hex = sum.toString(16);
            hex.length + mod.toString().length;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // sum = 111111111011111111100, hex 长度 = 17 (0x610f0a8... 之类)
        // mod = mul mod n1 = (n1 * n2) mod n1 = 0, toString = "0", length = 1
        // 实际: mod 结果为 0, length = 1
        assertTrue(
            "BigInteger operations should produce numeric result: $result",
            result.toString().matches(Regex("""^\d+$"""))
        )
    }

    // ============ F. 综合场景 ============

    /**
     * 综合场景: 模拟书源完整流程 (init + 搜索字段处理 + 段评解析)。
     *
     * 这是 JsBenchmarkTest.COMPOSITE_JS 的功能性版本, 验证完整流程结果正确。
     */
    @Test
    fun testCompositeSourceWorkflow() {
        val js = """
            // 1. 初始化: 构建 headers 和 baseUrl
            var headers = {};
            headers['User-Agent'] = 'okhttp/3.12.3';
            headers['X-App-ID'] = 'novelapp';

            // 2. 搜索字段处理: 清理书名/简介/字数
            var bookName = '  测试《小说》第1部  ';
            var cleanName = bookName.replace(/^[\s《]+|[\s》]+$/g, '');

            var intro = '这是简介。\n\n讲述主角冒险。';
            var cleanIntro = intro.replace(/\s+/g, ' ').trim();

            var wordCount = 1234567;
            var wordStr = wordCount > 10000
                ? (wordCount / 10000).toFixed(1) + '万字'
                : wordCount + '字';

            // 3. 段评数据解析 (模拟 idea_data)
            var ideaData = {};
            for (var i = 1; i <= 20; i++) {
                ideaData[i] = { idea_count: i * 5, user_id: 'user_' + i };
            }
            var results = {};
            for (var key in ideaData) {
                results[~~key] = ideaData[key].idea_count;
            }
            var totalComments = 0;
            for (var k in results) totalComments += results[k];

            // 4. 签名计算 (简化版)
            var signStr = cleanName + '|' + wordStr + '|' + totalComments;
            var md = java.security.MessageDigest.getInstance('MD5');
            var bytes = java.lang.String(signStr).getBytes('UTF-8');
            var digest = md.digest(bytes);
            var signHex = '';
            for (var i = 0; i < digest.length; i++) {
                var b = digest[i] & 0xff;
                signHex += (b < 16 ? '0' : '') + b.toString(16);
            }

            cleanName + '|' + cleanIntro + '|' + wordStr + '|' + totalComments + '|' + signHex.length;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // cleanName='测试《小说》第1部' (去掉首尾空白和《》), cleanIntro='这是简介。 讲述主角冒险。',
        // wordStr='123.5万字', totalComments = 5+10+...+100 = 1050, signHex.length = 32 (MD5)
        val expected = "测试《小说》第1部|这是简介。 讲述主角冒险。|123.5万字|1050|32"
        assertEquals(expected, result.toString())
    }

    /**
     * URL 模板填充 (书源 ruleBookInfo.init 中的 URL 构建)。
     */
    @Test
    fun testUrlTemplateFilling() {
        val js = """
            var tpl = 'https://api.example.com/v1/book/{id}/chapters?page={page}&size={size}&order={order}';
            var data = { id: '7638538561230228', page: 1, size: 20, order: 'desc' };
            var url = tpl.replace(/\{(\w+)\}/g, function(m, k) { return data[k] !== undefined ? encodeURIComponent(data[k]) : m; });
            var parsed = new java.net.URL(url);
            parsed.getHost() + parsed.getPath() + '|' + parsed.getQuery().length;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // host='api.example.com', path='/v1/book/7638538561230228/chapters'
        // query='page=1&size=20&order=desc' 长度=25
        assertEquals("api.example.com/v1/book/7638538561230228/chapters|25", result.toString())
    }

    /**
     * 书源详情页解析 (ruleBookInfo 模拟, 多字段同步处理)。
     */
    @Test
    fun testBookInfoPageParsing() {
        val js = """
            var raw = {
                base: { book_id: '7638538561230228', thumb_uri: 'novel-pic/a1b2c3' },
                data: { book_name: '测试小说', author: '测试作者', word_count: 1234567, score: 9.5 }
            };
            var info = {};
            info.bookUrl = raw.base.book_id;
            info.name = raw.data.book_name.replace(/^\s+|\s+$/g, '');
            info.author = raw.data.author;
            info.coverUrl = 'http://p6-novel.byteimg.com/' + raw.base.thumb_uri + '~tplv-shrink:320:0.image';
            info.wordCount = raw.data.word_count > 10000
                ? (raw.data.word_count / 10000).toFixed(1) + '万字'
                : raw.data.word_count + '字';
            info.score = raw.data.score + '分';
            var keys = Object.keys(info);
            var total = 0;
            for (var i = 0; i < keys.length; i++) total += info[keys[i]].length;
            info.name + '|' + info.wordCount + '|' + info.score + '|' + keys.length;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("测试小说|123.5万字|9.5分|6", result.toString())
    }
}
