package io.legado.app.data.entities.rule

import io.legado.app.utils.KS_JSON
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [RulePolymorphicSerializer] 行为锁: 守护 6 个 Rule 类的双形态反序列化语义。
 *
 * 复刻原 Gson JsonDeserializer:
 * - JsonNull → null
 * - JsonObject → 用 tSerializer 反序列化
 * - JsonPrimitive(string) → parse 字符串, 若 JsonObject 则反序列化, 否则 null
 * - 其他 (JsonArray) → null
 *
 * 字段数断言: 6 个 Rule 类各断言 memberProperties.size, 防止加字段不更新 serializer/测试。
 */
class RulePolymorphicSerializerTest {

    // ---- 字段数不变保障 (反射 memberProperties) ----

    @Test
    fun `SearchRule 字段数不变保障`() {
        // 12 字段: checkKeyWord + 11 个 BookListRule override
        assertEquals(12, SearchRule::class.memberProperties.size,
            "SearchRule 字段数变了, 请检查 RulePolymorphicSerializer 是否需要更新")
    }

    @Test
    fun `BookInfoRule 字段数不变保障`() {
        // 12 字段: init/name/author/intro/kind/lastChapter/updateTime/coverUrl/tocUrl/wordCount/canReName/downloadUrls
        assertEquals(12, BookInfoRule::class.memberProperties.size,
            "BookInfoRule 字段数变了, 请检查 RulePolymorphicSerializer 是否需要更新")
    }

    @Test
    fun `ContentRule 字段数不变保障`() {
        // 12 字段: content/title/nextContentUrl/webJs/sourceRegex/replaceRegex/imageStyle/imageDecode/payAction/subContent/musicCover/shouldOverrideUrlLoading
        assertEquals(12, ContentRule::class.memberProperties.size,
            "ContentRule 字段数变了, 请检查 RulePolymorphicSerializer 是否需要更新")
    }

    @Test
    fun `ExploreRule 字段数不变保障`() {
        // 11 字段: 11 个 BookListRule override (无 checkKeyWord)
        assertEquals(11, ExploreRule::class.memberProperties.size,
            "ExploreRule 字段数变了, 请检查 RulePolymorphicSerializer 是否需要更新")
    }

    @Test
    fun `ReviewRule 字段数不变保障`() {
        // 21 字段: reviewUrl/reviewList/reviewCountRule/reviewIdRule/avatarRule/nameRule/contentRule/postTimeRule/extraRule/imagesRule/voteUpCountRule/voteUpSelectedRule/voteDownSelectedRule/replyCountRule/totalCountRule/replyListUrl/hasMoreRule/voteUpRule/voteDownRule/replyRule/deleteRule
        assertEquals(21, ReviewRule::class.memberProperties.size,
            "ReviewRule 字段数变了, 请检查 RulePolymorphicSerializer 是否需要更新")
    }

    @Test
    fun `TocRule 字段数不变保障`() {
        // 9 字段: preUpdateJs/chapterList/chapterName/chapterUrl/isVolume/isVip/isPay/updateTime/nextTocUrl
        assertEquals(9, TocRule::class.memberProperties.size,
            "TocRule 字段数变了, 请检查 RulePolymorphicSerializer 是否需要更新")
    }

    // ---- SearchRule 往返测试 ----

    @Test
    fun `SearchRule 全字段往返`() {
        val original = SearchRule(
            checkKeyWord = "kw",
            hasMoreRule = "hasMore",
            bookList = "bl",
            name = "n",
            author = "a",
            intro = "i",
            kind = "k",
            lastChapter = "lc",
            updateTime = "ut",
            bookUrl = "bu",
            coverUrl = "cu",
            wordCount = "wc"
        )
        val serializer = RulePolymorphicSerializer(SearchRule.serializer())
        val json = KS_JSON.encodeToString(serializer, original)
        val restored = KS_JSON.decodeFromString(serializer, json)
        assertEquals(original, restored)
    }

    @Test
    fun `SearchRule 默认值 null 往返`() {
        val original = SearchRule()
        val serializer = RulePolymorphicSerializer(SearchRule.serializer())
        val json = KS_JSON.encodeToString(serializer, original)
        // 所有字段 null, encodeDefaults=false → 输出空 JsonObject
        assertEquals("{}", json)
        val restored = KS_JSON.decodeFromString(serializer, json)
        assertEquals(original, restored)
    }

    // ---- BookInfoRule 往返测试 ----

    @Test
    fun `BookInfoRule 全字段往返`() {
        val original = BookInfoRule(
            init = "init",
            name = "n",
            author = "a",
            intro = "i",
            kind = "k",
            lastChapter = "lc",
            updateTime = "ut",
            coverUrl = "cu",
            tocUrl = "tu",
            wordCount = "wc",
            canReName = "cr",
            downloadUrls = "du"
        )
        val serializer = RulePolymorphicSerializer(BookInfoRule.serializer())
        val json = KS_JSON.encodeToString(serializer, original)
        val restored = KS_JSON.decodeFromString(serializer, json)
        assertEquals(original, restored)
    }

    // ---- ContentRule 往返测试 (含 @JsonNames alternate) ----

    @Test
    fun `ContentRule 全字段往返`() {
        val original = ContentRule(
            content = "c",
            title = "t",
            nextContentUrl = "ncu",
            webJs = "wj",
            sourceRegex = "sr",
            replaceRegex = "rr",
            imageStyle = "is",
            imageDecode = "id",
            payAction = "pa",
            subContent = "sc",
            musicCover = "mc",
            shouldOverrideUrlLoading = "sou"
        )
        val serializer = RulePolymorphicSerializer(ContentRule.serializer())
        val json = KS_JSON.encodeToString(serializer, original)
        val restored = KS_JSON.decodeFromString(serializer, json)
        assertEquals(original, restored)
    }

    @Test
    fun `ContentRule subContent 接受 lrcRule 旧字段名`() {
        // @SerialName("subContent") + @JsonNames("lrcRule"): 旧书源用 lrcRule 作为键
        val json = """{"content":"c","lrcRule":"sc"}"""
        val serializer = RulePolymorphicSerializer(ContentRule.serializer())
        val restored = KS_JSON.decodeFromString(serializer, json) as ContentRule
        assertEquals("c", restored.content)
        assertEquals("sc", restored.subContent, "lrcRule 旧字段名应映射到 subContent")
    }

    // ---- ExploreRule 往返测试 ----

    @Test
    fun `ExploreRule 全字段往返`() {
        val original = ExploreRule(
            hasMoreRule = "hasMore",
            bookList = "bl",
            name = "n",
            author = "a",
            intro = "i",
            kind = "k",
            lastChapter = "lc",
            updateTime = "ut",
            bookUrl = "bu",
            coverUrl = "cu",
            wordCount = "wc"
        )
        val serializer = RulePolymorphicSerializer(ExploreRule.serializer())
        val json = KS_JSON.encodeToString(serializer, original)
        val restored = KS_JSON.decodeFromString(serializer, json)
        assertEquals(original, restored)
    }

    // ---- ReviewRule 往返测试 ----

    @Test
    fun `ReviewRule 全字段往返`() {
        val original = ReviewRule(
            reviewUrl = "ru",
            reviewList = "rl",
            reviewCountRule = "rcr",
            reviewIdRule = "rir",
            avatarRule = "ar",
            nameRule = "nr",
            contentRule = "cr",
            postTimeRule = "ptr",
            extraRule = "er",
            imagesRule = "ir",
            voteUpCountRule = "vucr",
            voteUpSelectedRule = "vusr",
            voteDownSelectedRule = "vdsr",
            replyCountRule = "rpcr",
            totalCountRule = "tcr",
            replyListUrl = "rplu",
            hasMoreRule = "hmr",
            voteUpRule = "vur",
            voteDownRule = "vdr",
            replyRule = "rpr",
            deleteRule = "dr"
        )
        val serializer = RulePolymorphicSerializer(ReviewRule.serializer())
        val json = KS_JSON.encodeToString(serializer, original)
        val restored = KS_JSON.decodeFromString(serializer, json)
        assertEquals(original, restored)
    }

    // ---- TocRule 往返测试 ----

    @Test
    fun `TocRule 全字段往返`() {
        val original = TocRule(
            preUpdateJs = "puj",
            chapterList = "cl",
            chapterName = "cn",
            chapterUrl = "cu",
            isVolume = "iv",
            isVip = "ip",
            isPay = "ip2",
            updateTime = "ut",
            nextTocUrl = "ntu"
        )
        val serializer = RulePolymorphicSerializer(TocRule.serializer())
        val json = KS_JSON.encodeToString(serializer, original)
        val restored = KS_JSON.decodeFromString(serializer, json)
        assertEquals(original, restored)
    }

    // ---- 双形态反序列化 (JsonObject / JsonPrimitive string) ----

    @Test
    fun `JsonObject 形态反序列化`() {
        val json = """{"name":"test"}"""
        val serializer = RulePolymorphicSerializer(SearchRule.serializer())
        val restored = KS_JSON.decodeFromString(serializer, json) as SearchRule
        assertEquals("test", restored.name)
    }

    @Test
    fun `JsonPrimitive 字符串内嵌 JsonObject 反序列化`() {
        // JsonPrimitive(string), 字符串内容是 JsonObject → parse 后反序列化
        val innerJson = """{"name":"test"}"""
        val primitiveStr = JsonPrimitive(innerJson).toString()  // "{\"name\":\"test\"}"
        val serializer = RulePolymorphicSerializer(SearchRule.serializer())
        val restored = KS_JSON.decodeFromString(serializer, primitiveStr) as SearchRule
        assertEquals("test", restored.name, "字符串内嵌 JsonObject 应被反序列化")
    }

    @Test
    fun `JsonPrimitive 字符串非 JsonObject 返回 null`() {
        // 字符串内容是 JsonArray → parse 后非 JsonObject → null
        val primitiveStr = JsonPrimitive("[1,2,3]").toString()
        val serializer = RulePolymorphicSerializer(SearchRule.serializer())
        val restored = KS_JSON.decodeFromString(serializer, primitiveStr)
        assertNull(restored, "字符串内嵌非 JsonObject 应返回 null")
    }

    @Test
    fun `JsonPrimitive 字符串非合法 JSON 返回 null`() {
        // 字符串内容非合法 JSON → parse 失败 → null
        val primitiveStr = JsonPrimitive("not a json").toString()
        val serializer = RulePolymorphicSerializer(SearchRule.serializer())
        val restored = KS_JSON.decodeFromString(serializer, primitiveStr)
        assertNull(restored, "非合法 JSON 字符串应返回 null")
    }

    @Test
    fun `JsonNull 返回 null`() {
        val serializer = RulePolymorphicSerializer(SearchRule.serializer())
        val restored = KS_JSON.decodeFromString(serializer, "null")
        assertNull(restored)
    }

    @Test
    fun `JsonArray 返回 null`() {
        // JsonArray 等不匹配 rule 形态, 返回 null (对应原 Gson else 分支)
        val serializer = RulePolymorphicSerializer(SearchRule.serializer())
        val restored = KS_JSON.decodeFromString(serializer, "[1,2,3]")
        assertNull(restored)
    }

    // ---- null 序列化为 encodeNull ----

    @Test
    fun `null 序列化为 null`() {
        val serializer = RulePolymorphicSerializer(SearchRule.serializer())
        val json = KS_JSON.encodeToString(serializer, null)
        assertEquals("null", json)
    }

    @Test
    fun `非空 序列化为嵌套对象形状`() {
        val original = SearchRule(name = "test")
        val serializer = RulePolymorphicSerializer(SearchRule.serializer())
        val json = KS_JSON.encodeToString(serializer, original)
        // 输出嵌套对象形状, 不是字符串
        assertEquals("""{"name":"test"}""", json)
    }

    // ---- descriptor 来自 tSerializer ----

    @Test
    fun `descriptor 直接复用 tSerializer descriptor`() {
        // descriptor 复用 tSerializer.descriptor, elementsCount 即字段数
        // 这是字段数断言的另一种方式 (kotlinx.serialization 路径, 不依赖反射)
        val searchSerializer = RulePolymorphicSerializer(SearchRule.serializer())
        assertEquals(12, searchSerializer.descriptor.elementsCount,
            "SearchRule descriptor.elementsCount 应为 12 (与反射一致)")
        val tocSerializer = RulePolymorphicSerializer(TocRule.serializer())
        assertEquals(9, tocSerializer.descriptor.elementsCount,
            "TocRule descriptor.elementsCount 应为 9 (与反射一致)")
    }
}
