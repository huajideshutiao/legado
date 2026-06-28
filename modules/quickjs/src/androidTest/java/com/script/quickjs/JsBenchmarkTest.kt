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
 * 覆盖书源 JS 的各类场景:
 * - A. JS 基础操作 (算术/字符串/数组/JSON/正则)
 * - B. Java 调用 (静态方法/实例方法/字段/构造器)
 * - C. 加解密 (MD5/AES/HMAC/Base64/URL)
 * - D. 集合操作 (List/Map, 验证 __wrapJavaMap 互操作性能)
 * - E. 日期/URL/Cookie (书源常见 Java 工具类)
 * - F. 综合场景 (xGorgon 签名/书源完整流程/书籍列表解析)
 * - G. ES6+ 特性 (模板字符串/解构/Map/Set/Symbol/生成器)
 * - H. 加密扩展 (SHA-256/AES-CBC/DES/BigInteger)
 * - I. 字符串扩展方法 (padStart/repeat/split + 数组方法链)
 * - J. 书源实际规则场景 (URL 模板/规则解析/详情页字段处理)
 *
 * 运行方式:
 * .\gradlew :modules:quickjs:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.script.quickjs.JsBenchmarkTest" --console=plain 2>&1 | Tee-Object -FilePath "bench_output.txt"
 */
@RunWith(AndroidJUnit4::class)
class JsBenchmarkTest {

    companion object {
        private const val WARMUP = 5
        private const val ITERATIONS = 50
        private const val HEAVY_ITERATIONS = 20
        private const val COMPLEX_ITERATIONS = 10

        // 从番茄小说书源提取的 xGorgon 核心计算逻辑 (无网络请求)
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

        // AES/ECB/PKCS5Padding 加解密往返 (书源常见加密)
        private val AES_JS = """
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

        // HMAC-SHA256 签名 (书源 API 签名)
        private val HMAC_JS = """
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

        // Base64 编解码往返 (书源数据传输)
        private val BASE64_JS = """
            var encoder = java.util.Base64.getEncoder();
            var decoder = java.util.Base64.getDecoder();
            var original = 'Hello, Base64 编解码测试! 中文';
            var bytes = java.lang.String(original).getBytes('UTF-8');
            var encoded = encoder.encodeToString(bytes);
            var decoded = decoder.decode(encoded);
            java.lang.String(decoded, 'UTF-8');
        """.trimIndent()

        // URL 编码 (书源 URL 参数处理)
        private val URLENCODE_JS = """
            var params = 'q=测试搜索&category=玄幻&sort=hot';
            java.net.URLEncoder.encode(params, 'UTF-8');
        """.trimIndent()

        // Java 实例方法链式调用 (StringBuilder, 书源字符串拼接)
        private val STRINGBUILDER_JS = """
            var sb = new java.lang.StringBuilder();
            for (var i = 0; i < 20; i++) {
                sb.append('item').append(i).append(',');
            }
            sb.toString();
        """.trimIndent()

        // Java 对象字段访问 (Point, 书源解析坐标等)
        private val POINT_FIELD_JS = """
            var p = new android.graphics.Point(100, 200);
            var sum = p.x + p.y;
            p.x = sum;
            p.x;
        """.trimIndent()

        // List 操作 (ArrayList, 书源解析书名列表)
        private val LIST_JS = """
            var list = new java.util.ArrayList();
            for (var i = 0; i < 50; i++) list.add('book_' + i);
            var sb = new java.lang.StringBuilder();
            for (var i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(list.get(i));
            }
            sb.toString();
        """.trimIndent()

        // Map 操作 (LinkedHashMap.clone() 触发 __wrapJavaMap, 书源段评场景)
        private val MAP_JS = """
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

        // Map 互操作修改 (set trap → Map.put, 验证 __wrapJavaMap 真正互操作性能)
        private val MAP_INTEROP_JS = """
            var m = new java.util.LinkedHashMap();
            for (var i = 0; i < 30; i++) m.put(String(i), i * 10);
            var body = m.clone();
            for (var i = 0; i < 30; i++) {
                body[String(i)] = body[String(i)] + 1;
            }
            var sum = 0;
            for (var key in body) sum += body[key];
            sum;
        """.trimIndent()

        // 日期格式化 (SimpleDateFormat, 书源时间戳处理)
        private val DATE_JS = """
            var sdf = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss');
            var cal = java.util.Calendar.getInstance();
            cal.set(2026, 5, 27, 12, 30, 45);
            var date = cal.getTime();
            var formatted = sdf.format(date);
            var parsed = sdf.parse(formatted);
            var ts = parsed.getTime();
            ts;
        """.trimIndent()

        // URL 构建与解析 (java.net.URL/URI, 书源 URL 处理)
        private val URL_JS = """
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

        // Cookie 操作 (java.net.HttpCookie, 书源 Cookie 解析)
        // HttpCookie.parse 返回 List,Collection 走 Java 句柄路径 (与 Rhino NativeJavaList 一致),
        // 支持 size()/get() 方法
        private val COOKIE_JS = """
            var cookies = java.net.HttpCookie.parse('sessionid=abc123; Path=/; Domain=example.com; Max-Age=3600; Secure; HttpOnly');
            var list = new java.util.ArrayList();
            for (var i = 0; i < cookies.size(); i++) {
                var c = cookies.get(i);
                list.add(c.getName() + '=' + c.getValue() + '; Domain=' + c.getDomain());
            }
            list.size();
        """.trimIndent()

        // JSON 处理 (org.json.JSONObject, 书源 API 响应解析)
        private val ORGJSON_JS = """
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

        // 正则提取 (Pattern/Matcher, 书源规则匹配)
        private val PATTERN_JS = """
            var text = 'book_12345_chapter_67890_page_42';
            var p = java.util.regex.Pattern.compile('(\\d+)');
            var m = p.matcher(text);
            var sum = 0;
            while (m.find()) {
                sum += parseInt(m.group(1));
            }
            sum;
        """.trimIndent()

        // JSON.stringify + parse (书源数据序列化)
        private val JSON_JS = """
            var books = [];
            for (var i = 0; i < 20; i++) {
                books.push({
                    name: '小说' + i,
                    author: '作者' + i,
                    score: (i * 0.5).toFixed(1),
                    tags: ['玄幻', '连载', '完本'].slice(0, (i % 3) + 1)
                });
            }
            var json = JSON.stringify(books);
            var parsed = JSON.parse(json);
            var count = 0;
            for (var i = 0; i < parsed.length; i++) {
                count += parsed[i].tags.length;
            }
            count;
        """.trimIndent()

        // 正则表达式 (书源规则匹配, 如提取数字/清理 HTML)
        private val REGEX_JS = """
            var html = '<div class="info"><span>评分:9.5</span><span>字数:123.4万</span></div>';
            var scoreMatch = html.match(/评分[:：]\\s*([\\d.]+)/);
            var wordMatch = html.match(/字数[:：]\\s*([\\d.]+)万/);
            var cleaned = html.replace(/<[^>]+>/g, ' ').replace(/\\s+/g, ' ').trim();
            (scoreMatch ? scoreMatch[1] : '') + '|' + (wordMatch ? wordMatch[1] : '') + '|' + cleaned;
        """.trimIndent()

        // JavaAdapter 创建 (书源接口实现, 如 Runnable)
        private val ADAPTER_JS = """
            var r = new Packages.java.lang.Runnable({ run: function() {} });
            var h1 = r.hashCode();
            var h2 = r.hashCode();
            h1 === h2;
        """.trimIndent()

        // Packages 深层访问 (书源访问不常用类)
        private val PACKAGES_DEEP_JS = """
            var sb = new java.lang.StringBuilder();
            var url = new java.net.URL('https://example.com/api/v1/books?page=1');
            sb.append(url.getProtocol()).append('://');
            sb.append(url.getHost()).append(url.getPath());
            sb.toString();
        """.trimIndent()

        // 综合场景: 模拟书源完整流程 (init + 搜索字段处理 + 段评解析)
        private val COMPOSITE_JS = """
            // 1. 初始化: 构建 headers 和 baseUrl
            var headers = {};
            headers['User-Agent'] = 'okhttp/3.12.3';
            headers['X-App-ID'] = 'novelapp';

            // 2. 搜索字段处理: 清理书名/简介/字数
            var bookName = '  测试《小说》第1部  ';
            var cleanName = bookName.replace(/^[\\s《]+|[\\s》]+$/g, '');

            var intro = '这是简介。\\n\\n讲述主角冒险。';
            var cleanIntro = intro.replace(/\\s+/g, ' ').trim();

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

            signHex;
        """.trimIndent()

        // ============ G. ES6+ 特性 ============

        // ES6 模板字符串 + 箭头函数 + 默认参数 (书源常见 ES6 写法)
        private val ES6_TEMPLATE_JS = """
            const format = (name, author, idx = 0) => `${'$'}{name} - ${'$'}{author} #${'$'}{idx}`;
            const books = [
                { name: '斗破苍穹', author: '天蚕土豆' },
                { name: '凡人修仙传', author: '忘语' },
                { name: '诡秘之主', author: '爱潜水的乌贼' }
            ];
            const lines = books.map((b, i) => format(b.name, b.author, i));
            const joined = lines.join(' | ');
            joined;
        """.trimIndent()

        // ES6 解构 + let/const 块级作用域 (书源 ES6 写法)
        private val ES6_DESTRUCTURE_JS = """
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

        // ES6 Map/Set 内置集合 (书源去重/索引场景)
        private val ES6_MAPSET_JS = """
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

        // ES6 Symbol + Spread/Rest (书源高级用法)
        private val ES6_SYMBOL_SPREAD_JS = """
            const sym = Symbol('id');
            const base = { name: 'book', author: 'unknown' };
            const extra = { [sym]: 42, tags: ['玄幻', '连载'] };
            const merged = { ...base, ...extra };
            const { name, tags, ...rest } = merged;
            name + tags.length + (rest[sym] || 0);
        """.trimIndent()

        // ES6 for...of + 生成器函数 (书源迭代场景)
        private val ES6_GENERATOR_JS = """
            function* bookGenerator(count) {
                for (let i = 0; i < count; i++) {
                    yield { id: i, name: `book_${'$'}{i}` };
                }
            }
            let sum = 0;
            for (const { id } of bookGenerator(50)) sum += id;
            sum;
        """.trimIndent()

        // ============ H. 加密扩展 ============

        // SHA-256 哈希 (书源更安全的签名)
        private val SHA256_JS = """
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

        // AES/CBC/PKCS5Padding 加解密 (带 IV, 书源常见完整 AES)
        private val AES_CBC_JS = """
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

        // DES 加解密 (书源老式加密场景)
        private val DES_JS = """
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

        // BigInteger 运算 (RSA 模数, 书源部分加密源)
        private val BIGINTEGER_JS = """
            var n1 = new java.math.BigInteger('12345678901234567890');
            var n2 = new java.math.BigInteger('98765432109876543210');
            var sum = n1.add(n2);
            var mul = n1.multiply(n2);
            var mod = mul.mod(n1);
            var hex = sum.toString(16);
            hex.length + mod.toString().length;
        """.trimIndent()

        // ============ I. 字符串扩展方法 ============

        // String 扩展方法链 (padStart/repeat/includes, 书源 URL/ID 处理)
        private val STRING_EXT_JS = """
            var ids = [];
            for (var i = 0; i < 30; i++) {
                var id = String(i).padStart(6, '0');
                if (id.includes('5')) ids.push(id);
            }
            var prefix = 'book_'.repeat(2);
            var joined = prefix + ids.join(',');
            joined;
        """.trimIndent()

        // String.split + 正则 + 数组方法链 (书源规则解析)
        private val STRING_SPLIT_JS = """
            var raw = 'a:b,c:d;e:f,g:h;i:j,k:l';
            var pairs = raw.split(/[,:;]/);
            var map = {};
            for (var i = 0; i < pairs.length; i += 2) {
                if (i + 1 < pairs.length) map[pairs[i]] = pairs[i + 1];
            }
            var keys = Object.keys(map).sort();
            var vals = keys.map(k => map[k]).filter(v => v !== '');
            keys.length + vals.length;
        """.trimIndent()

        // ============ J. 书源实际规则场景 ============

        // URL 模板填充 (书源 ruleBookInfo.init 中的 URL 构建)
        private val URL_TEMPLATE_JS = """
            var tpl = 'https://api.example.com/v1/book/{id}/chapters?page={page}&size={size}&order={order}';
            var data = { id: '7638538561230228', page: 1, size: 20, order: 'desc' };
            var url = tpl.replace(/\{(\w+)\}/g, function(m, k) { return data[k] !== undefined ? encodeURIComponent(data[k]) : m; });
            var parsed = new java.net.URL(url);
            parsed.getHost() + parsed.getPath() + '|' + parsed.getQuery().length;
        """.trimIndent()

        // 书源规则解析 (splitSourceRule 模拟: @js:/@json:/@css: 分隔)
        private val RULE_PARSE_JS = """
            var rules = [
                '@js: result.startsWith("http") ? result : "https://api.example.com" + result',
                '$.data.book_list[*]',
                '@css:.book-name@text',
                '@XPath://div[@class="chapter"]/text()',
                'class Chapter\\n  rule: $.data.list',
                '@js: result + "?sign=" + md5(result)'
            ];
            var stats = { js: 0, json: 0, css: 0, xpath: 0, other: 0 };
            for (var i = 0; i < rules.length; i++) {
                var r = rules[i];
                if (r.indexOf('@js:') === 0) stats.js++;
                else if (r.indexOf('$') === 0) stats.json++;
                else if (r.indexOf('@css:') === 0) stats.css++;
                else if (r.indexOf('@XPath:') === 0) stats.xpath++;
                else stats.other++;
            }
            stats.js + stats.json + stats.css + stats.xpath + stats.other;
        """.trimIndent()

        // 书源详情页解析 (ruleBookInfo 模拟, 多字段同步处理)
        private val BOOK_INFO_PARSE_JS = """
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
            total;
        """.trimIndent()
    }

    @Test
    fun benchmarkAll() {
        println("\n========== rhino vs quickjs 性能基准测试 ==========")
        println("迭代次数: $ITERATIONS (warmup $WARMUP, 重型场景 $HEAVY_ITERATIONS, 综合场景 $COMPLEX_ITERATIONS)\n")

        println("──── A. JS 基础操作 ────")
        runBench("A1. 基本算术", "1 + 2 * 3 + 4 * 5")
        runBench("A2. 字符串拼接", "'hello' + ' ' + 'world' + ' ' + 'test'")
        runBench(
            "A3. Object.entries (段评)", """
            var obj = {a:1, b:2, c:3, d:4, e:5, f:6, g:7, h:8};
            var r = {};
            for (var [id, info] of Object.entries(obj)) r[Number(id)+1] = info;
            JSON.stringify(r);
        """.trimIndent()
        )
        runBench(
            "A4. 循环+数组 (1000 元素)", """
            var arr = [];
            for (var i = 0; i < 1000; i++) arr.push(i * i);
            var sum = 0;
            for (var i = 0; i < arr.length; i++) sum += arr[i];
            sum;
        """.trimIndent()
        )
        runBench("A5. JSON.stringify+parse", JSON_JS)
        runBench("A6. 正则表达式 (HTML 清理)", REGEX_JS)

        println("\n──── B. Java 调用 ────")
        runBench("B1. Java String.valueOf", "java.lang.String.valueOf(123456)")
        runBench(
            "B2. Java MD5 哈希", """
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
        runBench("B3. Java StringBuilder 链式", STRINGBUILDER_JS)
        runBench("B4. Java 对象字段 (Point)", POINT_FIELD_JS)
        runBench(
            "B5. JavaImporter + with", """
            var importer = new JavaImporter(java.lang.String, java.lang.Math);
            with (importer) {
                String.valueOf(Math.max(100, 200));
            }
        """.trimIndent()
        )
        runBench("B6. Packages 深层访问", PACKAGES_DEEP_JS)
        runBench("B7. JavaAdapter 创建", ADAPTER_JS)

        println("\n──── C. 加解密 ────")
        runBench("C1. AES 加解密往返", AES_JS, iterations = HEAVY_ITERATIONS)
        runBench("C2. HMAC-SHA256 签名", HMAC_JS, iterations = HEAVY_ITERATIONS)
        runBench("C3. Base64 编解码往返", BASE64_JS, iterations = HEAVY_ITERATIONS)
        runBench("C4. URLEncoder", URLENCODE_JS, iterations = HEAVY_ITERATIONS)

        println("\n──── D. 集合操作 (验证 __wrapJavaMap) ────")
        runBench("D1. ArrayList 操作", LIST_JS, iterations = HEAVY_ITERATIONS)
        runBench("D2. LinkedHashMap+clone (读)", MAP_JS, iterations = HEAVY_ITERATIONS)
        runBench(
            "D3. LinkedHashMap 互操作 (set trap)",
            MAP_INTEROP_JS,
            iterations = HEAVY_ITERATIONS
        )

        println("\n──── E. 日期/URL/Cookie (书源 Java 工具类) ────")
        runBench("E1. SimpleDateFormat 格式化+解析", DATE_JS, iterations = HEAVY_ITERATIONS)
        runBench("E2. java.net.URL/URI 构建解析", URL_JS, iterations = HEAVY_ITERATIONS)
        runBench("E3. HttpCookie.parse 解析", COOKIE_JS, iterations = HEAVY_ITERATIONS)
        runBench("E4. org.json.JSONObject 解析", ORGJSON_JS, iterations = HEAVY_ITERATIONS)
        runBench("E5. Pattern/Matcher 正则提取", PATTERN_JS, iterations = HEAVY_ITERATIONS)

        println("\n──── F. 综合场景 ────")
        runBench("F1. xGorgon 签名 (3次 MD5)", XGORGON_JS, iterations = HEAVY_ITERATIONS)
        runBench("F2. 书源完整流程", COMPOSITE_JS, iterations = COMPLEX_ITERATIONS)

        println("\n──── G. ES6+ 特性 ────")
        runBench("G1. 模板字符串 + 箭头函数", ES6_TEMPLATE_JS)
        runBench("G2. 解构 + let/const 块级作用域", ES6_DESTRUCTURE_JS)
        runBench("G3. ES6 Map/Set 内置集合", ES6_MAPSET_JS)
        runBench("G4. Symbol + Spread/Rest", ES6_SYMBOL_SPREAD_JS)
        runBench("G5. 生成器函数 + for...of", ES6_GENERATOR_JS)

        println("\n──── H. 加密扩展 ────")
        runBench("H1. SHA-256 哈希", SHA256_JS, iterations = HEAVY_ITERATIONS)
        runBench("H2. AES/CBC + IvParameterSpec", AES_CBC_JS, iterations = HEAVY_ITERATIONS)
        runBench("H3. DES 加解密", DES_JS, iterations = HEAVY_ITERATIONS)
        runBench("H4. BigInteger 运算", BIGINTEGER_JS, iterations = HEAVY_ITERATIONS)

        println("\n──── I. 字符串扩展方法 ────")
        runBench("I1. padStart/repeat/includes 链式", STRING_EXT_JS)
        runBench("I2. split + 正则 + 数组方法链", STRING_SPLIT_JS)

        println("\n──── J. 书源实际规则场景 ────")
        runBench("J1. URL 模板填充 + URL 解析", URL_TEMPLATE_JS)
        runBench("J2. 规则解析 (@js:/@json:/@css:)", RULE_PARSE_JS)
        runBench("J3. 书源详情页解析 (多字段)", BOOK_INFO_PARSE_JS)

        println("\n========== 单场景测试完成 ==========")
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
        val qjScope = QuickJsEngine.getRuntimeScope(ScriptBindings())
        val qjCompiled = fieldRules.map { QuickJsEngine.compile(QuickJsEngine.wrapJsForEval(it)) }
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
        // Rhino warmup 可能失败 (如 QuickJS 独有的 __wrapJavaMap set trap 语法),
        // 失败时跳过 Rhino 基准测试,只测 QuickJS
        var rhinoSupported = true
        var rhinoError: Throwable? = null
        try {
            repeat(WARMUP) { RhinoScriptEngine.eval(js) }
        } catch (e: Throwable) {
            rhinoSupported = false
            rhinoError = e
        }

        // quickjs
        val qjResult = QuickJsEngine.eval(js)
        val qjTime = measureTimeMillis {
            repeat(iterations) { QuickJsEngine.eval(js) }
        }

        // rhino (若不支持则跳过)
        val rhResult: Any?
        val rhTime: Long
        if (rhinoSupported) {
            rhResult = RhinoScriptEngine.eval(js)
            rhTime = measureTimeMillis {
                repeat(iterations) { RhinoScriptEngine.eval(js) }
            }
        } else {
            rhResult = null
            rhTime = 0
        }

        val qjAvg = qjTime.toDouble() / iterations
        val rhAvg = if (rhinoSupported) rhTime.toDouble() / iterations else Double.NaN
        val ratio = if (rhinoSupported) qjAvg / rhAvg else Double.NaN

        if (rhinoSupported) {
            println(
                String.format(
                    "%-38s | QJ: %6.2f ms/it | Rh: %6.2f ms/it | QJ/Rh: %5.2fx",
                    name, qjAvg, rhAvg, ratio
                )
            )
        } else {
            // 打印 Rhino 不支持的具体原因,便于定位是语法不支持还是其他错误
            val errMsg = rhinoError?.javaClass?.simpleName + ": " +
                (rhinoError?.message?.take(80) ?: "unknown")
            println(
                String.format(
                    "%-38s | QJ: %6.2f ms/it | Rh:     N/A          | %s",
                    name, qjAvg, errMsg
                )
            )
        }
            // 注: 结果一致性校验已剥离到 RealWorldSourceScenarioTest (功能性测试独立运行)。
            // 本基准测试只关注性能数据,不再做 assert。
    }
}
