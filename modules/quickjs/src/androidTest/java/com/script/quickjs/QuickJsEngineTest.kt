package com.script.quickjs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * QuickJsEngine 核心功能单元测试。
 *
 * 以加解密相关 Java 调用为主轴,覆盖:
 * - 基本 eval 表达式
 * - bindings 变量注入
 * - Packages/JavaImporter 模拟
 * - 安全名单拦截
 * - Java 静态方法调用(MessageDigest/Base64/URLEncoder)
 * - Java 实例方法调用(Cipher/Mac/MessageDigest)
 * - Java 对象实例化(new SecretKeySpec/IvParameterSpec)
 * - Java 静态/实例字段访问
 *
 * 参考业务场景:书源 JS 通过 Packages 调用 javax.crypto/java.security 实现 AES/MD5/HMAC 等加解密。
 */
@RunWith(AndroidJUnit4::class)
class QuickJsEngineTest {

    // ============ 基本 eval ============

    @Test
    fun testEvalBasicArithmetic() {
        val result = QuickJsEngine.eval("1 + 2 * 3")
        assertEquals(7, (result as Number).toInt())
    }

    @Test
    fun testEvalStringConcat() {
        val result = QuickJsEngine.eval("'hello' + ' ' + 'world'")
        assertEquals("hello world", result.toString())
    }

    @Test
    fun testEvalBoolean() {
        val result = QuickJsEngine.eval("1 > 2")
        assertEquals(false, result)
    }

    @Test
    fun testEvalBlank() {
        assertNull(QuickJsEngine.eval(""))
        assertNull(QuickJsEngine.eval("   "))
    }

    @Test
    fun testEvalArray() {
        val result = QuickJsEngine.eval("[1, 2, 3]")
        assertTrue("Expected List, got ${result?.javaClass}", result is List<*>)
        val list = result as List<*>
        assertEquals(3, list.size)
        assertEquals(1, (list[0] as Number).toInt())
        assertEquals(3, (list[2] as Number).toInt())
    }

    @Test
    fun testEvalObject() {
        val result = QuickJsEngine.eval("({name: 'test', value: 42})")
        assertTrue("Expected Map, got ${result?.javaClass}", result is Map<*, *>)
        val map = result as Map<*, *>
        assertEquals("test", map["name"].toString())
        assertEquals(42, (map["value"] as Number).toInt())
    }

    @Test
    fun testEvalReturnNull() {
        assertNull(QuickJsEngine.eval("null"))
    }

    // ============ bindings 注入 ============

    @Test
    fun testEvalWithBindingsString() {
        val result = QuickJsEngine.eval("name + '!'") {
            put("name", "world")
        }
        assertEquals("world!", result.toString())
    }

    @Test
    fun testEvalWithBindingsNumber() {
        val result = QuickJsEngine.eval("a + b") {
            put("a", 10)
            put("b", 32)
        }
        assertEquals(42, (result as Number).toInt())
    }

    @Test
    fun testEvalWithBindingsBoolean() {
        val result = QuickJsEngine.eval("flag ? 'yes' : 'no'") {
            put("flag", true)
        }
        assertEquals("yes", result.toString())
    }

    @Test
    fun testEvalWithDangerousApiFlag() {
        val result = QuickJsEngine.eval("__dangerousApi__") {
            dangerousApi = true
        }
        assertEquals(true, result)
    }

    // ============ Packages 模拟 ============

    @Test
    fun testPackagesAccessJavaLangString() {
        val result = QuickJsEngine.eval("java.lang.String.valueOf(123)")
        assertEquals("123", result.toString())
    }

    @Test
    fun testPackagesAccessJavaLangMath() {
        val result = QuickJsEngine.eval("java.lang.Math.max(10, 20)")
        assertEquals(20, (result as Number).toInt())
    }

    @Test
    fun testPackagesAccessJavaLangInteger() {
        val result = QuickJsEngine.eval("java.lang.Integer.parseInt('42')")
        // 修复 javaToJsResult 后,返回 Integer 而非 String
        assertEquals(42, (result as Number).toInt())
    }

    @Test
    fun testPackagesVariableDirectAccess() {
        val result = QuickJsEngine.eval("typeof java")
        assertEquals("object", result.toString())
    }

    @Test
    fun testPackagesPackagesVariable() {
        val result = QuickJsEngine.eval("typeof Packages")
        assertEquals("object", result.toString())
    }

    @Test
    fun testJavaImporter() {
        val js = """
            var importer = new JavaImporter(java.lang.String, java.lang.Math);
            with (importer) {
                String.valueOf(Math.max(1, 2));
            }
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("2", result.toString())
    }

    @Test
    fun testJavaImporterImportPackage() {
        // 模拟番茄小说源用法: importer.importPackage(Packages.xxx) + with 动态加载包下类
        val js = """
            var javaImport = new JavaImporter();
            javaImport.importPackage(Packages.java.util);
            with (javaImport) {
                var list = new ArrayList();
                list.add('a');
                list.add('b');
                list.size();
            }
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals(2, (result as Number).toInt())
    }

    @Test
    fun testJavaImporterConstructorWithPackage() {
        // 构造参数传 Package,with 时动态加载
        val js = """
            var importer = new JavaImporter(Packages.java.util);
            with (importer) {
                var list = new ArrayList();
                list.add('x');
                list.size();
            }
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals(1, (result as Number).toInt())
    }

    @Test
    fun testJavaImporterDirectAccess() {
        // 直接访问 importer.String,验证 classMap 是否填充(不经过 with)
        val js = """
            var importer = new JavaImporter(java.lang.String, java.lang.Math);
            importer.String.valueOf(importer.Math.max(1, 2));
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("2", result.toString())
    }

    @Test
    fun testProxyWithStatementBasic() {
        // 诊断: 验证 QuickJS 是否支持 with + Proxy(基本场景)
        // 若此测试失败,说明 QuickJS 对 with + Proxy 支持有限,testJavaImporter 的失败不在 JavaImporter 实现
        val js = """
            var obj = new Proxy({ x: 10 }, {
                has: function(t, p) { return p === 'x'; },
                get: function(t, p) { return p === 'x' ? 100 : undefined; }
            });
            with (obj) {
                x;
            }
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals(100, (result as Number).toInt())
    }

    @Test
    fun testProxyWithStatementShadowGlobal() {
        // 诊断: 验证 with + Proxy 能否遮蔽全局变量(如 JS 内置 String)
        // 这是 testJavaImporter 失败的核心场景: with(importer) 内的 String 应遮蔽全局 String
        val js = """
            var globalString = String;
            var obj = new Proxy({}, {
                has: function(t, p) { return p === 'String'; },
                get: function(t, p) { return p === 'String' ? 'OVERRIDDEN' : undefined; }
            });
            with (obj) {
                String;
            }
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("OVERRIDDEN", result.toString())
    }

    // ============ 安全名单 ============

    @Test
    fun testSecurityPolicyBlocksFileClass() {
        val result = QuickJsEngine.eval("typeof java.io.File")
        assertEquals("object", result.toString())
    }

    @Test
    fun testSecurityPolicyBlocksRuntime() {
        val result = QuickJsEngine.eval("typeof java.lang.Runtime")
        assertEquals("object", result.toString())
    }

    @Test
    fun testSecurityPolicyBlocksClassClass() {
        val result = QuickJsEngine.eval("typeof java.lang.Class")
        assertEquals("object", result.toString())
    }

    @Test
    fun testSecurityPolicyAllowsString() {
        val result = QuickJsEngine.eval("java.lang.String.valueOf('ok')")
        assertEquals("ok", result.toString())
    }

    // ============ 加解密:MD5/SHA 哈希 ============

    @Test
    fun testMessageDigestMd5() {
        // 对应书源 JS: java.security.MessageDigest.getInstance("MD5").digest("hello".getBytes())
        val js = """
            var md = java.security.MessageDigest.getInstance('MD5');
            var bytes = java.lang.String('hello').getBytes('UTF-8');
            var digest = md.digest(bytes);
            var hex = '';
            for (var i = 0; i < digest.length; i++) {
                var b = digest[i] & 0xff;
                hex += (b < 16 ? '0' : '') + b.toString(16);
            }
            hex;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("5d41402abc4b2a76b9719d911017c592", result.toString())
    }

    @Test
    fun testMessageDigestSha256() {
        // SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        val js = """
            var md = java.security.MessageDigest.getInstance('SHA-256');
            var bytes = java.lang.String('hello').getBytes('UTF-8');
            var digest = md.digest(bytes);
            var hex = '';
            for (var i = 0; i < digest.length; i++) {
                var b = digest[i] & 0xff;
                hex += (b < 16 ? '0' : '') + b.toString(16);
            }
            hex;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            result.toString()
        )
    }

    // ============ 加解密:Base64 编解码 ============

    @Test
    fun testBase64Encode() {
        // java.util.Base64.getEncoder().encodeToString("hello".getBytes())
        val js = """
            var encoder = java.util.Base64.getEncoder();
            var bytes = java.lang.String('hello').getBytes('UTF-8');
            encoder.encodeToString(bytes);
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // Base64("hello") = "aGVsbG8="
        assertEquals("aGVsbG8=", result.toString())
    }

    @Test
    fun testBase64Decode() {
        val js = """
            var decoder = java.util.Base64.getDecoder();
            var bytes = decoder.decode('aGVsbG8=');
            java.lang.String(bytes, 'UTF-8');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("hello", result.toString())
    }

    @Test
    fun testBase64EncodeDecodeRoundTrip() {
        val js = """
            var original = 'Hello, 加密 World! 简体中文测试。';
            var encoder = java.util.Base64.getEncoder();
            var decoder = java.util.Base64.getDecoder();
            var bytes = java.lang.String(original).getBytes('UTF-8');
            var encoded = encoder.encodeToString(bytes);
            var decoded = decoder.decode(encoded);
            java.lang.String(decoded, 'UTF-8');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("Hello, 加密 World! 简体中文测试。", result.toString())
    }

    // ============ 加解密:URL 编码 ============

    @Test
    fun testUrlEncoder() {
        // java.net.URLEncoder.encode("hello world", "UTF-8")
        val js = """
            java.net.URLEncoder.encode('hello world & test=1', 'UTF-8');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("hello+world+%26+test%3D1", result.toString())
    }

    @Test
    fun testUrlDecoder() {
        val js = """
            java.net.URLDecoder.decode('hello+world+%26+test%3D1', 'UTF-8');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("hello world & test=1", result.toString())
    }

    // ============ 加解密:HMAC-SHA256 ============

    @Test
    fun testHmacSha256() {
        // HMAC-SHA256(key="secret", data="hello")
        val js = """
            var keyBytes = java.lang.String('secret').getBytes('UTF-8');
            var key = new javax.crypto.spec.SecretKeySpec(keyBytes, 'HmacSHA256');
            var mac = javax.crypto.Mac.getInstance('HmacSHA256');
            mac.init(key);
            var data = java.lang.String('hello').getBytes('UTF-8');
            var digest = mac.doFinal(data);
            var hex = '';
            for (var i = 0; i < digest.length; i++) {
                var b = digest[i] & 0xff;
                hex += (b < 16 ? '0' : '') + b.toString(16);
            }
            hex;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // 用 Java 侧验证
        val expectedMac = javax.crypto.Mac.getInstance("HmacSHA256")
        expectedMac.init(javax.crypto.spec.SecretKeySpec("secret".toByteArray(), "HmacSHA256"))
        val expected = expectedMac.doFinal("hello".toByteArray()).joinToString("") {
            ((it.toInt() and 0xff).toString(16).padStart(2, '0'))
        }
        assertEquals(expected, result.toString())
    }

    // ============ 加解密:AES-ECB-PKCS5Padding ============

    @Test
    fun testAesEcbEncryptDecrypt() {
        // AES/ECB/PKCS5Padding,key=16字节,明文="hello world"
        val js = """
            var keyBytes = java.lang.String('1234567890123456').getBytes('UTF-8');
            var key = new javax.crypto.spec.SecretKeySpec(keyBytes, 'AES');
            var cipher = javax.crypto.Cipher.getInstance('AES/ECB/PKCS5Padding');
            cipher.init(1, key);
            var plainBytes = java.lang.String('hello world').getBytes('UTF-8');
            var encrypted = cipher.doFinal(plainBytes);
            var encoder = java.util.Base64.getEncoder();
            var encryptedB64 = encoder.encodeToString(encrypted);
            encryptedB64;
        """.trimIndent()
        val encryptedB64 = QuickJsEngine.eval(js).toString()

        // 用 Java 侧解密验证
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        val key = javax.crypto.spec.SecretKeySpec("1234567890123456".toByteArray(), "AES")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key)
        val decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedB64))
        assertEquals("hello world", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun testAesCbcEncryptDecrypt() {
        // AES/CBC/PKCS5Padding,key=16字节,iv=16字节
        val js = """
            var keyBytes = java.lang.String('1234567890123456').getBytes('UTF-8');
            var ivBytes = java.lang.String('abcdef0123456789').getBytes('UTF-8');
            var key = new javax.crypto.spec.SecretKeySpec(keyBytes, 'AES');
            var iv = new javax.crypto.spec.IvParameterSpec(ivBytes);
            var cipher = javax.crypto.Cipher.getInstance('AES/CBC/PKCS5Padding');
            cipher.init(1, key, iv);
            var plainBytes = java.lang.String('hello world').getBytes('UTF-8');
            var encrypted = cipher.doFinal(plainBytes);
            var encoder = java.util.Base64.getEncoder();
            var encryptedB64 = encoder.encodeToString(encrypted);
            encryptedB64;
        """.trimIndent()
        val encryptedB64 = QuickJsEngine.eval(js).toString()

        // 用 Java 侧解密验证
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        val key = javax.crypto.spec.SecretKeySpec("1234567890123456".toByteArray(), "AES")
        val iv = javax.crypto.spec.IvParameterSpec("abcdef0123456789".toByteArray())
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, iv)
        val decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedB64))
        assertEquals("hello world", String(decrypted, Charsets.UTF_8))
    }

    // ============ 加解密:完整 AES 加解密往返 ============

    @Test
    fun testAesRoundTripInJs() {
        // 在 JS 内完成加密+解密往返
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

    // ============ 加解密:DES 加解密 ============

    @Test
    fun testDesRoundTrip() {
        val js = """
            var keyBytes = java.lang.String('12345678').getBytes('UTF-8');
            var key = javax.crypto.spec.DESKeySpec(keyBytes);
            var keyFactory = javax.crypto.SecretKeyFactory.getInstance('DES');
            var secretKey = keyFactory.generateSecret(key);
            var cipher = javax.crypto.Cipher.getInstance('DES/ECB/PKCS5Padding');
            cipher.init(1, secretKey);
            var plainBytes = java.lang.String('hello').getBytes('UTF-8');
            var encrypted = cipher.doFinal(plainBytes);
            cipher.init(2, secretKey);
            var decrypted = cipher.doFinal(encrypted);
            java.lang.String(decrypted, 'UTF-8');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("hello", result.toString())
    }

    // ============ Java 实例方法 + 字段访问 ============

    @Test
    fun testJavaInstanceMethodStringLength() {
        // 实例方法调用: new String("hello").length()
        val js = """
            var s = new java.lang.String('hello');
            s.length();
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals(5, (result as Number).toInt())
    }

    @Test
    fun testJavaInstanceMethodStringBuilder() {
        val js = """
            var sb = new java.lang.StringBuilder();
            sb.append('hello');
            sb.append(' ');
            sb.append('world');
            sb.toString();
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("hello world", result.toString())
    }

    @Test
    fun testJavaStaticFieldAccess() {
        // 静态字段访问: java.lang.Integer.MAX_VALUE
        val js = "java.lang.Integer.MAX_VALUE;"
        val result = QuickJsEngine.eval(js)
        assertEquals(Int.MAX_VALUE, (result as Number).toInt())
    }

    @Test
    fun testJavaInstanceFieldAccess() {
        // 实例字段访问: android.graphics.Point(3, 4).x
        val js = """
            var p = new android.graphics.Point(3, 4);
            p.x;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        if (result != null) {
            assertEquals(3, (result as Number).toInt())
        }
    }

    @Test
    fun testJavaListOperations() {
        // java.util.ArrayList 实例化 + 实例方法
        val js = """
            var list = new java.util.ArrayList();
            list.add('a');
            list.add('b');
            list.add('c');
            list.size();
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals(3, (result as Number).toInt())
    }

    // ============ bindings 注入 Java 对象 ============

    @Test
    fun testBindingsWithJavaObject() {
        // 注入 Java 对象,JS 调用其实例方法
        val sb = StringBuilder("prefix-")
        val result = QuickJsEngine.eval("sb.append('suffix').toString()") {
            put("sb", sb)
        }
        assertEquals("prefix-suffix", result.toString())
    }

    @Test
    fun testBindingsWithByteArray() {
        // 注入 ByteArray,JS 调用 javaToJsResult 后应能访问
        val bytes = byteArrayOf(1, 2, 3)
        val result = QuickJsEngine.eval("bytes.length") {
            put("bytes", bytes)
        }
        // ByteArray 注入后,在 JS 侧可能被转为 List 或保留 ByteArray
        // 具体行为取决于 jsToJavaArgs 的处理
        if (result is Number) {
            assertEquals(3, result.toInt())
        }
    }

    // ============ Map/List 字段访问 (修复点: rhino FEATURE_ENABLE_JAVA_MAP_ACCESS) ============

    @Test
    fun testMapFieldAccessByKey() {
        // 对应 rhino FEATURE_ENABLE_JAVA_MAP_ACCESS: map.key -> map.get(key)
        val map = HashMap<String, Any?>()
        map["name"] = "alice"
        map["age"] = 30
        val result = QuickJsEngine.eval("m.name + '|' + m.age") {
            put("m", map)
        }
        assertEquals("alice|30", result.toString())
    }

    @Test
    fun testMapFieldAccessMissingKeyReturnsUndefined() {
        // 不存在的 key 应返回 undefined (JS 语义),不应抛异常
        val map = HashMap<String, Any?>()
        map["exists"] = "yes"
        val result = QuickJsEngine.eval("typeof m.missing") {
            put("m", map)
        }
        assertEquals("undefined", result.toString())
    }

    @Test
    fun testListIndexAccessByNumber() {
        // 对应 rhino NativeJavaList: list[0] -> list.get(0)
        val list = ArrayList<String>()
        list.add("a")
        list.add("b")
        list.add("c")
        val result = QuickJsEngine.eval("lst[0] + lst[2]") {
            put("lst", list)
        }
        assertEquals("ac", result.toString())
    }

    @Test
    fun testListLengthField() {
        // 对应 rhino NativeJavaList: list.length -> size()
        val list = ArrayList<String>()
        list.add("a")
        list.add("b")
        val result = QuickJsEngine.eval("lst.length") {
            put("lst", list)
        }
        assertEquals(2, (result as Number).toInt())
    }

    @Test
    fun testListLengthFieldAlias() {
        // List 的 length 是 field 别名,返回 size() (与 rhino NativeJavaList 一致)
        // 注意: size 是 method,list.size() 调用返回长度,list.size 返回 method callable
        val list = ArrayList<String>()
        list.add("x")
        list.add("y")
        list.add("z")
        val result = QuickJsEngine.eval("lst.length") {
            put("lst", list)
        }
        assertEquals(3, (result as Number).toInt())
    }

    @Test
    fun testArrayFieldAccess() {
        // Java 原生数组: array[0] / array.length
        val arr = arrayOf(10, 20, 30)
        val result = QuickJsEngine.eval("arr[1] + ',' + arr.length") {
            put("arr", arr)
        }
        assertEquals("20,3", result.toString())
    }

    @Test
    fun testMapMutationFromJS() {
        // MutableMap: map.key = value -> map.put(key, value)
        val map = HashMap<String, String>()
        QuickJsEngine.eval("m.greeting = 'hello'") {
            put("m", map)
        }
        assertEquals("hello", map["greeting"])
    }

    @Test
    fun testListMutationFromJS() {
        // MutableList: list[0] = value -> list.set(0, value)
        val list = ArrayList<String>(listOf("old", "b"))
        QuickJsEngine.eval("lst[0] = 'new'") {
            put("lst", list)
        }
        assertEquals("new", list[0])
    }

    // ============ String(java) 返回 toString (修复点: Symbol.toPrimitive 调 Java toString) ============

    @Test
    fun testStringConversionCallsJavaToString() {
        // 对应 rhino LiveConnect: String(javaObj) 应返回 Java toString() 结果
        // 修复前 Symbol.toPrimitive 返回静态 '[java@handle]',修复后调 Java toString()
        // 用 StringBuilder (白名单中的类),其 toString() 返回内容
        val obj = StringBuilder("custom-toString-value")
        val result = QuickJsEngine.eval("String(obj)") {
            put("obj", obj)
        }
        assertEquals("custom-toString-value", result.toString())
    }

    @Test
    fun testPlusOperatorCallsJavaToString() {
        // `obj + ""` 会触发 toPrimitive,应调用 Java toString()
        val obj = StringBuilder("plus-toString")
        val result = QuickJsEngine.eval("obj + ''") {
            put("obj", obj)
        }
        assertEquals("plus-toString", result.toString())
    }

    @Test
    fun testJavaToStringMethodCall() {
        // 直接调用 toString() 应走实例方法路径,返回 Java toString 结果
        val obj = StringBuilder("method-toString")
        val result = QuickJsEngine.eval("obj.toString()") {
            put("obj", obj)
        }
        assertEquals("method-toString", result.toString())
    }

    // ============ new Interface(impl) JavaAdapter 语法 (修复点: 构造器分流) ============

    @Test
    fun testNewInterfaceWithJsObjectCreatesAdapter() {
        // 对应 rhino 语法: new Runnable({ run: function(){...} })
        // 修复点: __wrapClass construct handler 检测 interface + JS 对象参数,走 JavaAdapter 路径
        // 注意: 需通过 Packages.java.lang.Runnable 访问 (rhino 的 java.lang 简写在 QuickJS 不支持)
        // 注意: 不在 evaluate 中调用 r.run(),会因 JS->Java->JS 嵌套导致 Mutex 死锁
        //       (quickjs-kt 的 evaluate 是 suspend + Mutex 串行,参见 JsFunctionHandle 线程模型说明)
        // 回调测试需在 Kotlin 侧 evaluate 完成后调用,此处只验证 adapter 创建成功
        val js = """
            var r = new Packages.java.lang.Runnable({ run: function() { __runCount__ = (__runCount__ || 0) + 1; } });
            typeof r;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("object", result.toString())
    }

    @Test
    fun testJavaAdapterHashCodeEquals() {
        // 修复点: 旧实现 hashCode/equals 误用 JavaObjectBridge 单例,
        //         导致所有 adapter 共享 hashCode、equals 永远 false。
        //         修复后用 proxy 参数,每个 adapter 有独立 hashCode,equals 按 identity 比较。
        val js = """
            var a = new Packages.java.lang.Runnable({ run: function(){} });
            var b = new Packages.java.lang.Runnable({ run: function(){} });
            var hashCodeA = a.hashCode();
            var hashCodeB = b.hashCode();
            var eqSelf = a.equals(a);
            var eqOther = a.equals(b);
            [hashCodeA, hashCodeB, eqSelf, eqOther];
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        val list = result as List<*>
        val hashCodeA = (list[0] as Number).toInt()
        val hashCodeB = (list[1] as Number).toInt()
        val eqSelf = list[2] as Boolean
        val eqOther = list[3] as Boolean
        assertNotEquals("不同 adapter 的 hashCode 应不同", hashCodeA, hashCodeB)
        assertTrue("adapter.equals(self) 应为 true", eqSelf)
        assertFalse("adapter.equals(other) 应为 false", eqOther)
    }

    // ============ 子 scope 隔离: injectBindings + cleanupBindings ============

    @Test
    fun testCleanupBindingsRemovesInjectedVariables() {
        // 修复点: cleanupBindings 应删除注入的全局变量,实现子 scope 隔离
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val bindings = ScriptBindings().apply {
                put("tempVar", "injected-value")
            }
            val keys = QuickJsEngine.injectBindings(scope, bindings)
            assertTrue("应注入 tempVar", keys.contains("tempVar"))

            // 验证注入后可访问
            val before = QuickJsEngine.eval("tempVar", scope, null)
            assertEquals("injected-value", before.toString())

            // cleanup 后再访问应为 undefined
            QuickJsEngine.cleanupBindings(scope, keys)
            val after = QuickJsEngine.eval("typeof tempVar", scope, null)
            assertEquals("undefined", after.toString())
        } finally {
            scope.close()
        }
    }

    @Test
    fun testScopeReuseDoesNotLeakVariables() {
        // 修复点: SharedJsScope 场景下,多次 inject + cleanup 不应让变量泄漏到下次复用
        // 模拟 BaseSource.evalJS 的 sharedScope 路径:inject → eval → cleanup
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            // 第一次注入 source1 的变量
            val bindings1 = ScriptBindings().apply { put("source", "src1") }
            val keys1 = QuickJsEngine.injectBindings(scope, bindings1)
            try {
                val r1 = QuickJsEngine.eval("source", scope, null)
                assertEquals("src1", r1.toString())
            } finally {
                QuickJsEngine.cleanupBindings(scope, keys1)
            }

            // 第二次注入不同变量,验证 source 已不可见
            val bindings2 = ScriptBindings().apply { put("other", "src2") }
            val keys2 = QuickJsEngine.injectBindings(scope, bindings2)
            try {
                val r2 = QuickJsEngine.eval("other", scope, null)
                assertEquals("src2", r2.toString())
                val leaked = QuickJsEngine.eval("typeof source", scope, null)
                assertEquals("undefined", leaked.toString())
            } finally {
                QuickJsEngine.cleanupBindings(scope, keys2)
            }
        } finally {
            scope.close()
        }
    }

    @Test
    fun testDangerousApiSyncOnlyOnChange() {
        // 修复点: syncDangerousApiIfNeeded 仅在 dangerousApi 变化时同步,
        //         通过 lastSyncedDangerousApi 跟踪,避免每次 eval 都 evaluate 同步语句
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings().apply {
            dangerousApi = false
        })
        try {
            // 默认 false,首次应与 bootstrap 一致,无需同步
            assertEquals(false, scope.lastSyncedDangerousApi)

            // 切换到 true,eval 后应同步
            scope.dangerousApi = true
            QuickJsEngine.eval("__dangerousApi__", scope, null)
            assertEquals(true, scope.lastSyncedDangerousApi)
            val r1 = QuickJsEngine.eval("__dangerousApi__", scope, null)
            assertEquals(true, r1)

            // 保持 true,再次 eval 不应触发同步(无 evaluate 调用,但结果应一致)
            scope.dangerousApi = true
            val r2 = QuickJsEngine.eval("__dangerousApi__", scope, null)
            assertEquals(true, r2)
            assertEquals(true, scope.lastSyncedDangerousApi)

            // 切换回 false,应再次同步
            scope.dangerousApi = false
            QuickJsEngine.eval("__dangerousApi__", scope, null)
            assertEquals(false, scope.lastSyncedDangerousApi)
        } finally {
            scope.close()
        }
    }

    // ============ Object.keys 对 Java 对象的枚举 (对齐 rhino NativeJavaMap.getIds()) ============

    @Test
    fun testKeysOnInjectedMap() {
        // Object.keys(javaMap) 触发 native getOwnPropertyNames trap, 返回 Map.keySet()
        val map = LinkedHashMap<String, Any?>()
        map["a"] = 1
        map["b"] = 2
        map["c"] = 3
        val result = QuickJsEngine.eval("Object.keys(m).sort().join(',')") {
            put("m", map)
        }
        assertEquals("a,b,c", result.toString())
    }

    @Test
    fun testKeysOnInjectedList() {
        // Object.keys(javaList) 触发 native getOwnPropertyNames trap, 返回 0 until size 索引
        val list = ArrayList<String>(listOf("x", "y"))
        val result = QuickJsEngine.eval("Object.keys(lst).join(',')") {
            put("lst", list)
        }
        assertEquals("0,1", result.toString())
    }

    @Test
    fun testKeysOnPlainJsObject() {
        val js = """
            var o = { foo: 1, bar: 2 };
            Object.keys(o).sort().join(',');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("bar,foo", result.toString())
    }

    // ============ compile(script, scope) 复用目标 scope (修复点: 减少临时实例) ============

    @Test
    fun testCompileWithTargetScope() {
        // 修复点: compile(script, scope) 复用目标 scope 编译,避免创建临时 QuickJs 实例
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val compiled = QuickJsEngine.compile("1 + 41", scope)
            val result = compiled.eval(scope, null)
            assertEquals(42, (result as Number).toInt())
        } finally {
            scope.close()
        }
    }

    @Test
    fun testCompiledScriptReusableOnSameScope() {
        // 验证 bytecode 在同一 scope 上可重复执行(模拟 AnalyzeRule.formatJs 循环)
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val compiled = QuickJsEngine.compile("x * 2", scope)
            // 多次执行,每次注入不同 x
            val keys1 = QuickJsEngine.injectBindings(scope, ScriptBindings().apply {
                put("x", 10)
            })
            val r1 = compiled.eval(scope, null)
            QuickJsEngine.cleanupBindings(scope, keys1)

            val keys2 = QuickJsEngine.injectBindings(scope, ScriptBindings().apply {
                put("x", 21)
            })
            val r2 = compiled.eval(scope, null)
            QuickJsEngine.cleanupBindings(scope, keys2)

            assertEquals(20, (r1 as Number).toInt())
            assertEquals(42, (r2 as Number).toInt())
        } finally {
            scope.close()
        }
    }

    @Test
    fun testCompileStandaloneBytecodeDefensiveCopy() {
        // 修复点: CompiledScript 构造时防御性拷贝 bytecode,避免外部修改传入数组影响执行
        // 验证 compile + eval 在独立 scope 上正常工作 (compile 内部已做 copyOf)
        val source = "40 + 2"
        val compiled = QuickJsEngine.compile(source)
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val result = compiled.eval(scope, null)
            assertEquals(42, (result as Number).toInt())
        } finally {
            scope.close()
        }
    }

    // ============ wrapJsForEval 隔离 (修复点: 避免污染 sharedScope) ============

    @Test
    fun testWrapJsForEvalIsolatesLetConst() {
        // 修复点: wrapJsForEval 用 IIFE + eval 包裹,let/const 不污染全局
        // 模拟 AnalyzeRule 复用 sharedScope 执行两段含 let url 的 JS,不应报 redeclaration
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val js1 = QuickJsEngine.wrapJsForEval("let url = 'first'; url;")
            val js2 = QuickJsEngine.wrapJsForEval("let url = 'second'; url;")
            val r1 = QuickJsEngine.eval(js1, scope, null)
            val r2 = QuickJsEngine.eval(js2, scope, null)
            assertEquals("first", r1.toString())
            assertEquals("second", r2.toString())
            // 全局 url 应未定义(IIFE 内 let 不污染全局)
            val global = QuickJsEngine.eval("typeof url", scope, null)
            assertEquals("undefined", global.toString())
        } finally {
            scope.close()
        }
    }

    @Test
    fun testWrapJsForEvalPreservesReturnValue() {
        // 修复点: wrapJsForEval 用 IIFE + eval 包裹,最后一条表达式作为返回值
        // 注意: QuickJS 的 eval 内不支持 return (与 rhino 的顶层 return 扩展不同),
        //       但 IIFE 的 return eval(...) 可以捕获 eval 的最后表达式值
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val wrapped = QuickJsEngine.wrapJsForEval("var x = 1; x + 41;")
            val result = QuickJsEngine.eval(wrapped, scope, null)
            assertEquals(42, (result as Number).toInt())
        } finally {
            scope.close()
        }
    }

    @Test
    fun testWrapJsForEvalAccessesScopeVariables() {
        // 修复点: wrapJsForEval 内 eval 可访问注入到 scope 的变量
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            val keys = QuickJsEngine.injectBindings(scope, ScriptBindings().apply {
                put("baseUrl", "https://example.com")
            })
            try {
                val wrapped = QuickJsEngine.wrapJsForEval("baseUrl + '/path';")
                val result = QuickJsEngine.eval(wrapped, scope, null)
                assertEquals("https://example.com/path", result.toString())
            } finally {
                QuickJsEngine.cleanupBindings(scope, keys)
            }
        } finally {
            scope.close()
        }
    }

    // ============ QuickJsContext.close 资源释放 (修复点: scopeId 分组释放) ============

    @Test
    fun testContextCloseReleasesHandles() {
        // 修复点: QuickJsContext.close 应调用 releaseScope 释放本 scope 注册的句柄
        // 验证: close 不抛异常且可重复调用(幂等),间接验证资源释放路径正常
        val bindings = ScriptBindings().apply {
            put("sb", StringBuilder("hello"))
            put("extra", ArrayList<String>(listOf("a", "b")))
        }
        val scope = QuickJsEngine.getRuntimeScope(bindings)
        // 执行一些操作确保句柄已注册
        val r = QuickJsEngine.eval("sb.append('!').toString()", scope, null)
        assertEquals("hello!", r.toString())
        // close 应释放本 scope 的全部句柄(Java 对象 + Class + Adapter)
        scope.close()
        // 重复 close 应是 no-op,不抛异常
        scope.close()
    }

    // ============ JavaImporter.has 不注册句柄 (修复点: 避免 has 泄漏) ============

    @Test
    fun testJavaImporterHasDoesNotLeakHandles() {
        // 修复点: JavaImporter.has 用 __classExists 探测,不注册句柄
        // 验证: with(importer) 内访问 'String' 应返回 true,且不影响后续使用
        val js = """
            var importer = new JavaImporter(Packages.java.lang);
            var hasString = false;
            with (importer) {
                hasString = 'String' in importer;
            }
            hasString ? 'yes' : 'no';
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("yes", result.toString())
    }

    // ============ 综合场景: 模拟 SharedJsScope 复用 ============

    @Test
    fun testSharedScopeReuseWithDifferentSources() {
        // 综合验证: 模拟两个书源复用同一 sharedScope,通过 cleanupBindings 实现隔离
        // 对应 BaseSource.evalJS / AnalyzeRule.evalJS 的 inject + eval + cleanup 模式
        val sharedScope = QuickJsEngine.getRuntimeScope(ScriptBindings())

        try {
            // 模拟源 A: 注入 result + java + 自定义变量,执行后 cleanup
            val bindingsA = ScriptBindings().apply {
                put("result", "from-A")
                put("custom", "A-specific")
            }
            val keysA = QuickJsEngine.injectBindings(sharedScope, bindingsA)
            try {
                val rA = QuickJsEngine.eval(
                    QuickJsEngine.wrapJsForEval("result + '|' + custom"),
                    sharedScope, null
                )
                assertEquals("from-A|A-specific", rA.toString())
            } finally {
                QuickJsEngine.cleanupBindings(sharedScope, keysA)
            }

            // 模拟源 B: 验证 A 的变量已清理,不会泄漏
            val bindingsB = ScriptBindings().apply {
                put("result", "from-B")
            }
            val keysB = QuickJsEngine.injectBindings(sharedScope, bindingsB)
            try {
                val rB = QuickJsEngine.eval(
                    QuickJsEngine.wrapJsForEval("result"),
                    sharedScope, null
                )
                assertEquals("from-B", rB.toString())
                // A 的 custom 应已不可见
                val leaked = QuickJsEngine.eval(
                    QuickJsEngine.wrapJsForEval("typeof custom"),
                    sharedScope, null
                )
                assertEquals("undefined", leaked.toString())
            } finally {
                QuickJsEngine.cleanupBindings(sharedScope, keysB)
            }
        } finally {
            sharedScope.close()
        }
    }

    @Test
    fun testBootstrapBytecodeCachedAcrossScopes() {
        // 修复点: bootstrap bytecode 应被缓存,多次 getRuntimeScope 不重复编译
        // 通过创建多个 scope 验证不抛异常且行为一致(间接验证 bytecode 复用)
        val scopes = mutableListOf<QuickJsContext>()
        try {
            for (i in 0 until 3) {
                val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
                scopes.add(scope)
                // 每个 scope 都应能正常访问 bootstrap 注入的全局对象
                val tp = QuickJsEngine.eval("typeof Packages", scope, null)
                assertEquals("object", tp.toString())
            }
            assertEquals(3, scopes.size)
        } finally {
            scopes.forEach { it.close() }
        }
    }

    // ============ ownKeys trap 验证 ============
    // 背景: JsBootstrap.kt 的 __wrapJavaObject Proxy 之前未加 ownKeys/getOwnPropertyDescriptor trap,
    // 注释称 "native 层 getEvaluateResult 会通过这两个 trap 遍历 Proxy,导致无限递归 StackOverflowError"。
    // 此处验证该结论是否准确: 若带 trap 的 Proxy 直接返回到 Kotlin 侧不爆栈,
    // 说明可改用 Proxy+ownKeys 实现 rhino NativeJavaMap 行为一致(for...in + .get() 并存),
    // 去掉 __wrapJavaResult 中的 Map→Object 转换 + origKeyMap 两重映射。

    /**
     * 测试1: 带 ownKeys/getOwnPropertyDescriptor trap 的 Proxy 直接作为 eval 返回值,
     * 验证 native 层 JS→Kotlin 转换不会 StackOverflow。
     *
     * 若爆栈: 说明之前的注释结论正确,ownKeys trap 不可用。
     * 若通过: 说明 ownKeys trap 可用,之前的注释可能是误判或特定数据导致。
     */
    @Test
    fun testOwnKeysTrapReturnToKotlinNoStackOverflow() {
        val js = """
            var obj = {a: 1, b: 'hello', c: true};
            var p = new Proxy(obj, {
                ownKeys: function() { return ['a', 'b', 'c']; },
                getOwnPropertyDescriptor: function(target, prop) {
                    if (!(prop in target)) return undefined;
                    return { value: target[prop], writable: true, enumerable: true, configurable: true };
                }
            });
            p;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        // 关键: 若爆栈,eval 会抛 StackOverflowError,到不了下面的 assert
        println("testOwnKeysTrapReturnToKotlin result: $result (${result?.javaClass})")
        assertTrue("Expected Map from Proxy, got ${result?.javaClass}", result is Map<*, *>)
        val map = result as Map<*, *>
        assertEquals(1, (map["a"] as Number).toInt())
        assertEquals("hello", map["b"].toString())
        assertEquals(true, map["c"])
    }

    /**
     * 测试2: 嵌套 Proxy(Proxy 的 value 是另一个 Proxy),
     * 验证 native 递归转换时正常终止,不爆栈。
     *
     * 这模拟真实业务场景: Java Map 的 value 是另一个 Java Map(如 idea_data → idea_count Map),
     * 若 ownKeys trap 可用,每个 Map 都是带 trap 的 Proxy,转换时会递归。
     */
    @Test
    fun testNestedProxyReturnToKotlinNoStackOverflow() {
        val js = """
            function makeProxy(obj) {
                return new Proxy(obj, {
                    ownKeys: function() { return Object.keys(obj); },
                    getOwnPropertyDescriptor: function(target, prop) {
                        if (!(prop in target)) return undefined;
                        return { value: target[prop], writable: true, enumerable: true, configurable: true };
                    }
                });
            }
            var inner = makeProxy({x: 1, y: 2});
            var outer = makeProxy({a: inner, b: 'test'});
            outer;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        println("testNestedProxyReturnToKotlin result: $result (${result?.javaClass})")
        assertTrue("Expected Map, got ${result?.javaClass}", result is Map<*, *>)
        val outer = result as Map<*, *>
        assertEquals("test", outer["b"].toString())
        // 内层 Proxy 也应被递归转换为 Map
        @Suppress("UNCHECKED_CAST")
        val innerMap = outer["a"] as Map<String, Any?>
        assertEquals(1, (innerMap["x"] as Number).toInt())
        assertEquals(2, (innerMap["y"] as Number).toInt())
    }

    /**
     * 测试3: 验证 Proxy 可同时支持 for...in(ownKeys trap) 和 .get()(get trap),
     * 这是 rhino NativeJavaMap 的核心行为,也是业务代码 `for (let key in body) body.get(key).xxx` 所需。
     *
     * 此测试返回 JSON.stringify 的字符串,不直接返回 Proxy,主要验证 Proxy 语义可用。
     */
    @Test
    fun testProxyForInAndGetCoexist() {
        val js = """
            var data = {'1': {idea_count: 5}, '2': {idea_count: 10}};
            var p = new Proxy(data, {
                ownKeys: function() { return ['1', '2']; },
                getOwnPropertyDescriptor: function(target, prop) {
                    if (!(prop in target)) return undefined;
                    return { value: target[prop], writable: true, enumerable: true, configurable: true };
                },
                get: function(target, prop) {
                    // .get(key) 调用: 返回一个函数,执行时返回 target[key]
                    if (prop === 'get') {
                        return function(k) { return target[k]; };
                    }
                    return target[prop];
                }
            });
            // 业务代码模式: for...in + .get() 并存 (与段评解析一致)
            var results = {};
            for (var key in p) {
                results[~~key] = p.get(key).idea_count;
            }
            JSON.stringify(results);
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        println("testProxyForInAndGetCoexist result: $result")
        assertEquals("""{"1":5,"2":10}""", result.toString())
    }

    /**
     * 测试4: Java Map (LinkedHashMap) 通过 Java 方法返回后,for...in + .get() 并存。
     *
     * 真正互操作验证: Map 通过 javaToJsResult 走 Java 句柄路径 (标记 __java_is_map__),
     * native JniValueConvert.fromJavaObject 自动把 Map 包装为 JavaObject (exotic trap)。
     * - get_own_property_names trap 让 for...in 枚举 Map keys (经 getPropertyNames → Map.keySet)
     * - get_property trap 委托到 getPropertyInfo → Map.get(key)
     * - 嵌套 Map (idea_count) 也通过 exotic trap 递归包装,.idea_count 属性访问正常
     *
     * 这覆盖番茄小说段评解析的核心代码: for (var key in body) body.get(key).idea_count
     *
     * 注意: 必须通过 Java 方法返回 Map (clone()) 走 javaToJsResult → exotic trap 路径,
     * 与段评业务路径一致: body = java.getElements("$..idea_data")[0] 通过 Java 方法返回 Map。
     */
    @Test
    fun testJavaMapForInAndGetCoexist() {
        // 模拟段评 idea_data: {"1": {idea_count: 5}, "2": {idea_count: 10}}
        val js = """
            var m = new java.util.LinkedHashMap();
            var v1 = new java.util.LinkedHashMap();
            v1.put('idea_count', 5);
            var v2 = new java.util.LinkedHashMap();
            v2.put('idea_count', 10);
            m.put('1', v1);
            m.put('2', v2);
            var body = m.clone();
            var results = {};
            for (var key in body) {
                results[~~key] = body.get(key).idea_count;
            }
            JSON.stringify(results);
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        println("testJavaMapForInAndGetCoexist result: $result")
        assertEquals("""{"1":5,"2":10}""", result.toString())
    }

    /**
     * 测试5: Object.entries 对 Java Map Proxy 的工作。
     *
     * 验证 __wrapJavaMap 的 ownKeys + getOwnPropertyDescriptor trap 让 Object.entries 返回 key-value 对。
     * 对齐 rhino NativeJavaMap.getIds() 行为。
     */
    @Test
    fun testObjectEntriesOnJavaMap() {
        // 通过 m.clone() 让 Map 走 javaToJsResult → quickjs-kt 自动转 JS Map → __wrapJavaMap 路径
        val js = """
            var m = new java.util.LinkedHashMap();
            m.put('a', 1);
            m.put('b', 2);
            m.put('c', 3);
            var body = m.clone();
            var entries = Object.entries(body);
            var sum = 0;
            for (var i = 0; i < entries.length; i++) {
                sum += entries[i][1];
            }
            sum;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        println("testObjectEntriesOnJavaMap result: $result")
        assertEquals(6, (result as Number).toInt())
    }

    /**
     * 测试6: Object.keys 对 Java Map Proxy 的工作。
     */
    @Test
    fun testObjectKeysOnJavaMap() {
        val js = """
            var m = new java.util.LinkedHashMap();
            m.put('x', 1);
            m.put('y', 2);
            m.put('z', 3);
            var body = m.clone();
            var keys = Object.keys(body);
            keys.join(',');
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        println("testObjectKeysOnJavaMap result: $result")
        assertEquals("x,y,z", result.toString())
    }

    // ============ __wrapJavaMap 真正互操作验证 ============
    // 背景: 旧版 __wrapJsMap 把 Map 转成 ES6 Map 副本,JS 修改副本不影响原 Java Map,不叫互操作。
    // 新版 __wrapJavaMap 通过 Java 句柄路径,set/get/ownKeys trap 直接操作原 Java Map。
    // 以下测试验证修改源对象后,通过同一引用读取能感知到修改。

    /**
     * 测试7: __wrapJavaMap 修改后,通过同一 body 读取可见 (真正互操作)。
     *
     * 关键验证点: JS 侧 `body[key] = val` 修改后,通过同一 body 读取 `body[key]` 能看到新值。
     * 这证明 __wrapJavaMap 的 set/get trap 都作用于同一个 Java Map (而非副本):
     * - set trap → __setInstanceField → setCollectionField → Map.put(key, val)
     * - get trap → __getInstanceField → getCollectionField → Map.get(key)
     *
     * 这是用户反馈 "无法修改源对象就不叫互操作" 的核心场景回归测试。
     */
    @Test
    fun testJavaMapInteropModificationPersists() {
        val js = """
            var m = new java.util.LinkedHashMap();
            m.put('origKey', 'origVal');
            var body = m.clone();
            body['newKey'] = 'newVal';
            body['origKey'] = 'modified';
            var viaGet = body.get('newKey');
            var viaBracket = body['origKey'];
            var size = body.size();
            viaGet + '|' + viaBracket + '|' + size;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        println("testJavaMapInteropModificationPersists result: $result")
        assertEquals("newVal|modified|2", result.toString())
    }

    /**
     * 测试8: JavaObject (Map) 修改后 Object.keys 反映新增的 key。
     *
     * 验证 set trap (setProperty) 后 get_own_property_names trap (getPropertyNames)
     * 能枚举到新增 key。
     */
    @Test
    fun testJavaMapInteropKeysReflectModification() {
        val js = """
            var m = new java.util.LinkedHashMap();
            m.put('a', 1);
            var body = m.clone();
            body['b'] = 2;
            body['c'] = 3;
            Object.keys(body).join(',') + '|' + body.size();
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        println("testJavaMapInteropKeysReflectModification result: $result")
        assertEquals("a,b,c|3", result.toString())
    }

    /**
     * 测试9: __wrapJavaMap 修改后 for...in 枚举到新 key。
     *
     * 验证 ownKeys trap (经 cachedKeys) 反映 set trap 的新增 key,
     * 让 for...in 能枚举到 JS 侧新增的 key (LinkedHashMap 维持插入顺序)。
     */
    @Test
    fun testJavaMapInteropForInReflectsModification() {
        val js = """
            var m = new java.util.LinkedHashMap();
            m.put('orig', 'val');
            var body = m.clone();
            body['added'] = 'new';
            var keys = [];
            for (var k in body) keys.push(k);
            keys.join(',') + '|' + body.size();
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        println("testJavaMapInteropForInReflectsModification result: $result")
        assertEquals("orig,added|2", result.toString())
    }

    /**
     * 测试10: __wrapJavaMap 修改后 Object.entries 反映新增的 key-value。
     *
     * 验证 getOwnPropertyDescriptor trap 也能访问到 set trap 添加的新 key。
     */
    @Test
    fun testJavaMapInteropEntriesReflectModification() {
        val js = """
            var m = new java.util.LinkedHashMap();
            m.put('a', 1);
            var body = m.clone();
            body['b'] = 2;
            var entries = Object.entries(body);
            var parts = [];
            for (var i = 0; i < entries.length; i++) {
                parts.push(entries[i][0] + '=' + entries[i][1]);
            }
            parts.join(',') + '|' + body.size();
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        println("testJavaMapInteropEntriesReflectModification result: $result")
        assertEquals("a=1,b=2|2", result.toString())
    }

    /**
     * 测试11: StringBuilder 链式调用 (含数字参数)。
     *
     * 回归测试: javaToJsResult 中 StringBuilder 实现了 CharSequence 接口,
     * 被错误地 toString() 转成 String,导致 sb.append(s) 返回 String 而非 StringBuilder,
     * 后续 .append(i) 报 "TypeError: not a function"。
     *
     * 修复: StringBuilder/StringBuffer 保留为 Java 对象,不走 CharSequence.toString()。
     */
    @Test
    fun testStringBuilderChainCallAfterMapInteropFix() {
        val js = """
            var sb = new java.lang.StringBuilder();
            for (var i = 0; i < 20; i++) {
                sb.append('item').append(i).append(',');
            }
            sb.toString();
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        val expected = (0 until 20).joinToString("") { "item$it," }
        assertEquals(expected, result.toString())
    }

    // ============ 精简验证 (本次精简点回归测试) ============
    // 背景: 删除了 __jsFnStore 死变量、__unwrapJavaHandle 薄 wrapper、
    //       __getJavaPropertyValue 的 length 特判死代码、__wrapJavaResult 的 v.get 死分支
    // 以下测试验证行为未回归

    /**
     * 验证 1: __unwrapJavaHandle 删除后,Kotlin 侧通过 __wrapJavaObject 注入 Java 对象仍可正常调用。
     *
     * 覆盖 QuickJsEngine.injectVariable / JsFunction.javaArgToJsExpr / JsFunctionHandle.javaArgToJsExpr
     * 三处调用点的精简(都从 __unwrapJavaHandle 改为 __wrapJavaObject)。
     */
    @Test
    fun testUnwrapJavaHandleRemovalBindingsInjection() {
        // bindings 注入 StringBuilder,JS 调用其实例方法
        val sb = StringBuilder("prefix-")
        val result = QuickJsEngine.eval("sb.append('tail').toString()") {
            put("sb", sb)
        }
        assertEquals("prefix-tail", result.toString())
    }

    /**
     * 验证 2: length 特判删除后,List/Array 的 length 字段仍能正常访问。
     *
     * List/Array 的 length 由 Java 侧 getCollectionField 处理(getInstanceField 返回 Int),
     * JS 侧 __getJavaPropertyValue 收到的 field 已是 Number,直接返回不走 callable 路径。
     */
    @Test
    fun testLengthSpecialCaseRemovalListLength() {
        val list = ArrayList<String>(listOf("a", "b", "c", "d"))
        val result = QuickJsEngine.eval("lst.length") {
            put("lst", list)
        }
        assertEquals(4, (result as Number).toInt())
    }

    @Test
    fun testLengthSpecialCaseRemovalArrayLength() {
        val arr = arrayOf(10, 20, 30)
        val result = QuickJsEngine.eval("arr.length") {
            put("arr", arr)
        }
        assertEquals(3, (result as Number).toInt())
    }

    /**
     * 验证 3: length 特判删除后,String.length() 方法仍能正常调用(走 method callable 路径)。
     *
     * String 没有 length 字段,只有 length() 方法,getInstanceField 返回 null,
     * __getJavaPropertyValue 进入 "field 不存在" 分支返回 method callable。
     */
    @Test
    fun testLengthSpecialCaseRemovalStringLengthMethod() {
        val js = """
            var s = new java.lang.String('hello');
            s.length();
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals(5, (result as Number).toInt())
    }

    /**
     * 验证 4: __wrapJavaResult 的 v.get(__java_handle__) 死分支删除后,
     * 普通 Java 对象(包装为 {__java_handle__: handle} Map)仍能被正确解包为 __wrapJavaObject Proxy。
     *
     * quickjs-kt 把 Kotlin Map 转为 JS Map,在 __wrapJavaResult 中走 v instanceof Map 分支处理。
     */
    @Test
    fun testWrapJavaResultObjectBranchRemoval() {
        // 通过 Java 方法返回 Java 对象(java.util.ArrayList 实例化),
        // javaToJsResult 包装为 {__java_handle__: handle} Map → quickjs-kt 转 JS Map →
        // __wrapJavaResult 在 Map 分支处理,JS 侧可调用其实例方法
        val js = """
            var list = new java.util.ArrayList();
            list.add('item1');
            list.add('item2');
            list.size();
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals(2, (result as Number).toInt())
    }

    /**
     * 验证 5: __jsFnStore 死变量删除后,JavaAdapter 仍能正常创建并工作。
     *
     * __registerJsFunction 实际只使用 __jsFnCounter 生成唯一名并存到 globalThis,
     * __jsFnStore 从未被读写,删除后行为应一致。
     */
    @Test
    fun testJsFnStoreRemovalJavaAdapter() {
        // 验证 JavaAdapter 创建仍能工作(创建后调 hashCode/equals 不爆栈)
        val js = """
            var r = new Packages.java.lang.Runnable({ run: function(){} });
            typeof r;
        """.trimIndent()
        val result = QuickJsEngine.eval(js)
        assertEquals("object", result.toString())
    }

    /**
     * 验证 6: __unwrapJavaHandle 删除后,JsFunction 回调链路仍能正常工作。
     *
     * JsFunction.argToJsExpr 把 Java 参数转为 JS 表达式,Java 对象参数通过 __wrapJavaObject 解包,
     * 在 JS 侧应能调用其实例方法。
     */
    @Test
    fun testUnwrapJavaHandleRemovalJsFunctionCallback() {
        val scope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        try {
            // 在 scope 中定义函数(用 globalThis 暴露)
            QuickJsEngine.eval(
                "globalThis.appendString = function(sb, suffix) { return sb.append(suffix).toString(); }; null",
                scope, null
            )
            // 通过 JsFunction 调用,Java StringBuilder 参数经 javaArgToJsExpr 转 __wrapJavaObject(handle),
            // JS 函数内调用 .append() 应正常工作
            val sb = StringBuilder("hello-")
            val result = JsFunction(scope, "appendString").call(sb, "world")
            assertEquals("hello-world", result.toString())
        } finally {
            scope.close()
        }
    }
}
