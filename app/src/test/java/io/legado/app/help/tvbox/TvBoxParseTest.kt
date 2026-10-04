package io.legado.app.help.tvbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TVBox parses[] 解析站 type≠0 (json API / Json 扩展 / 聚合) 的 JVM 单测:
 * 钉住 FongMi ParseJob 语义下的 URL/头提取、按线路挑解析、jar 聚合类入参拼装。
 * 全部走纯函数/纯数据, 不触碰 Android 面 (org.json 由 testImplementation 提供真实实现)。
 */
class TvBoxParseTest {

    // ---- fromJson: 真实配置形态 (gao js.json 的 巧技/Json聚合/虾米) ----

    @Test
    fun fromJson_type1_jsonApi() {
        val obj = JSONObject(
            """{"name":"巧技","type":1,"url":"http://pan.qiaoji8.com/tvbox/neibu.php?url=",""" +
                """"ext":{"flag":["qq","腾讯","qiyi","爱奇艺"],"header":{"User-Agent":"Mozilla/5.0","Referer":"https://example.com"}}}""",
        )
        val parse = TvBoxParse.fromJson(obj)!!
        assertEquals("巧技", parse.name)
        assertEquals(TvBoxParse.TYPE_JSON, parse.type)
        assertTrue(parse.isJsonApi)
        assertFalse(parse.isWebSniff)
        assertFalse(parse.isAggregate)
        assertEquals(listOf("qq", "腾讯", "qiyi", "爱奇艺"), parse.flags)
        assertEquals("Mozilla/5.0", parse.headers["User-Agent"])
        assertEquals("https://example.com", parse.headers["Referer"])
        // ext 原样保留为 JSON 文本 (聚合类透传用)
        assertTrue(parse.extJson.contains("\"flag\""))
    }

    @Test
    fun fromJson_type3_aggregate() {
        val obj = JSONObject("""{"name":"Json聚合","type":3,"url":"Demo"}""")
        val parse = TvBoxParse.fromJson(obj)!!
        assertEquals(TvBoxParse.TYPE_MIX, parse.type)
        assertTrue(parse.isAggregate)
        assertFalse(parse.isJsonApi)
        // url "Demo" 是 jar 内类名后缀, 不是 http 地址
        assertFalse(parse.url.startsWith("http"))
    }

    @Test
    fun fromJson_defaultType0() {
        val obj = JSONObject("""{"name":"虾米","url":"https://jx.xmflv.com/?url="}""")
        val parse = TvBoxParse.fromJson(obj)!!
        assertEquals(TvBoxParse.TYPE_WEB_SNIFF, parse.type)
        assertTrue(parse.isWebSniff)
    }

    @Test
    fun fromJson_urlBlank_skipped() {
        assertNull(TvBoxParse.fromJson(JSONObject("""{"name":"空","url":"  "}""")))
    }

    // ---- 按线路挑解析 ----

    @Test
    fun pickJsonApi_flagMatch_preferred() {
        val list = listOf(
            parseOf(0, "虾米", "https://jx.xmflv.com/?url="),
            parseOf(1, "巧技", "http://pan.qiaoji8.com/tvbox/neibu.php?url=", flags = listOf("qq")),
            parseOf(1, "巧技二", "http://pan.qiaoji8.com/tvbox/gouzi.php?url=", flags = listOf("youku")),
        )
        assertEquals("巧技", list.pickJsonApi("qq")!!.name)
        assertEquals("巧技二", list.pickJsonApi("youku")!!.name)
        // 线路不匹配时退回首条可用 type=1
        assertEquals("巧技", list.pickJsonApi("unknown")!!.name)
        assertNull(listOf(parseOf(0, "虾米", "https://jx.xmflv.com/?url=")).pickJsonApi("qq"))
    }

    @Test
    fun pickAggregate_firstAggregate() {
        val list = listOf(
            parseOf(1, "巧技", "http://pan.qiaoji8.com/tvbox/neibu.php?url="),
            parseOf(3, "Json聚合", "Demo"),
            parseOf(2, "Json扩展", "App"),
        )
        assertEquals("Json聚合", list.pickAggregate()!!.name)
        assertNull(listOf(parseOf(1, "巧技", "http://x/?url=")).pickAggregate())
    }

    // ---- type=1 返回体提取 (FongMi jsonParse / Json.safeString) ----

    @Test
    fun extractJsonUrl_rootUrl() {
        val root = JSONObject("""{"url":"https://cdn.example.com/video/2024/01/01/abc123.m3u8?sign=abc","data":{"url":"https://other.example.com/x.m3u8"}}""")
        assertEquals("https://cdn.example.com/video/2024/01/01/abc123.m3u8?sign=abc", extractJsonUrl(root))
    }

    @Test
    fun extractJsonUrl_dataUrlFallback() {
        val root = JSONObject("""{"data":{"url":"https://cdn.example.com/video/abc.m3u8"},"msg":"ok"}""")
        assertEquals("https://cdn.example.com/video/abc.m3u8", extractJsonUrl(root))
    }

    @Test
    fun extractJsonUrl_missing() {
        val root = JSONObject("""{"msg":"error"}""")
        assertEquals("", extractJsonUrl(root))
    }

    @Test
    fun extractJsonHeaders_uaRefererCookie() {
        val root = JSONObject(
            """{"url":"https://x.m3u8","User-Agent":"Mozilla/5.0 X","REFERER":"https://ref.example",""" +
                """"Cookie":"a=1","parse":0}""",
        )
        val headers = extractJsonHeaders(root)
        assertEquals(3, headers.size)
        assertEquals("Mozilla/5.0 X", headers["User-Agent"])
        assertEquals("https://ref.example", headers["Referer"])
        assertEquals("a=1", headers["Cookie"])
    }

    @Test
    fun extractJsonHeaders_uaNormalized() {
        val root = JSONObject("""{"url":"https://x.m3u8","ua":"LegacyUA"}""")
        val headers = extractJsonHeaders(root)
        assertEquals("LegacyUA", headers["User-Agent"])
    }

    @Test
    fun extractJsonHeaders_ignoresOthers() {
        val root = JSONObject("""{"url":"https://x.m3u8","playUrl":"http://y","msg":"ok"}""")
        assertTrue(extractJsonHeaders(root).isEmpty())
    }

    // ---- jar 聚合类入参拼装 (FongMi Parse.extUrl / mixMap / jsonExt / jsonExtMix) ----

    @Test
    fun extUrl_withExt_insertsCatExt() {
        val parse = TvBoxParse.fromJson(
            JSONObject("""{"name":"巧技","type":1,"url":"http://pan.qiaoji8.com/tvbox/neibu.php?url=","ext":{"flag":["qq"]}}"""),
        )!!
        val out = parse.extUrl()
        assertTrue(out.startsWith("http://pan.qiaoji8.com/tvbox/neibu.php?cat_ext="))
        assertTrue(out.endsWith("&url="))
        // cat_ext 是 URL 安全 base64 的 ext JSON (可解码还原)
        val encoded = out.substringAfter("cat_ext=").substringBefore("&")
        val decoded = String(java.util.Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        assertTrue(decoded.contains("\"flag\":[\"qq\"]"))
    }

    @Test
    fun extUrl_noExtOrNoQuery_returnsUrl() {
        assertEquals("http://x/?url=", parseOf(1, "a", "http://x/?url=").extUrl())
        assertEquals("http://x/path", parseOf(1, "a", "http://x/path").extUrl())
    }

    @Test
    fun mixMap_shape() {
        val parse = parseOf(3, "Json聚合", "Demo")
        val mix = parse.mixMap()
        assertEquals("3", mix["type"])
        assertEquals("Demo", mix["url"])
        assertEquals("", mix["ext"])
    }

    @Test
    fun jsonExtJxs_onlyType1() {
        val list = listOf(
            parseOf(1, "巧技", "http://x/neibu.php?url="),
            parseOf(1, "巧技二", "http://x/gouzi.php?url="),
            parseOf(0, "虾米", "https://jx.xmflv.com/?url="),
        )
        val jxs = jsonExtJxs(list)
        assertEquals(setOf("巧技", "巧技二"), jxs.keys)
        assertTrue(jxs["巧技"]!!.startsWith("http://x/neibu.php?"))
    }

    @Test
    fun mixJxs_allParses() {
        val list = listOf(parseOf(3, "Json聚合", "Demo"), parseOf(1, "巧技", "http://x/?url="))
        val jxs = mixJxs(list)
        assertEquals(setOf("Json聚合", "巧技"), jxs.keys)
        assertEquals("3", jxs["Json聚合"]!!["type"])
        assertEquals("http://x/?url=", jxs["巧技"]!!["url"])
    }

    // ---- 解析页拼装 ----

    @Test
    fun pageOf_appendsPlayPage() {
        val parse = parseOf(0, "虾米", "https://jx.xmflv.com/?url=")
        assertEquals("https://jx.xmflv.com/?url=https://v.qq.com/x/page/1.html", parse.pageOf("https://v.qq.com/x/page/1.html"))
    }

    /** 便捷构造: 只填关心的字段。 */
    private fun parseOf(
        type: Int,
        name: String,
        url: String,
        flags: List<String> = emptyList(),
    ): TvBoxParse = TvBoxParse(name = name, type = type, url = url, flags = flags, headers = emptyMap())
}
