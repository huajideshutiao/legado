package io.legado.app.data.entities

import io.legado.app.data.entities.Book.ReadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * ReadConfig JSON 兼容测试:
 * DB 存量 readConfig 列由旧 GSON 写入, 新 kotlinx Converters 必须能读全部旧形态
 * (全字段/部分字段/显式 null/未知字段), 且字段值一致; 再 encode→decode 往返一致。
 *
 * 关键点: GSON 把 LocalDate 反射成 {"year":Y,"month":M,"day":D} 非标准形态,
 * 自定义 LocalDateAsGsonSerializer 复刻该 shape。
 *
 * Phase D: 原 BookReadConfigConverterCompatTest 用 GsonBuilder 作为 oracle 生成旧 JSON,
 * 现改为硬编码等价 JSON 字符串 (pretty-printed 格式不影响 kotlinx 解析, 用紧凑格式)。
 * 字段顺序对齐 GSON 反射声明顺序: reverseToc, reSegment, imageStyle, useReplaceRule,
 * delTag, ttsEngine, splitLongChapter, readSimulating, startDate, startChapter, dailyChapters。
 */
class BookReadConfigConverterCompatTest {

    private val converters = Book.Converters()

    private fun assertConfigEquals(expected: ReadConfig, actual: ReadConfig?) {
        assertNotNull(actual)
        actual!!
        assertEquals(expected.reverseToc, actual.reverseToc)
        assertEquals(expected.reSegment, actual.reSegment)
        assertEquals(expected.imageStyle, actual.imageStyle)
        assertEquals(expected.useReplaceRule, actual.useReplaceRule)
        assertEquals(expected.delTag, actual.delTag)
        assertEquals(expected.ttsEngine, actual.ttsEngine)
        assertEquals(expected.splitLongChapter, actual.splitLongChapter)
        assertEquals(expected.readSimulating, actual.readSimulating)
        assertEquals(expected.startDate, actual.startDate)
        assertEquals(expected.startChapter, actual.startChapter)
        assertEquals(expected.dailyChapters, actual.dailyChapters)
        // 全字段等值 => 整体 equals 也应成立
        assertEquals(expected, actual)
    }

    @Test
    fun `GSON 全字段旧 JSON 可被 kotlinx 正确 decode`() {
        val cfg = ReadConfig(
            reverseToc = true,
            reSegment = true,
            imageStyle = "FULL",
            useReplaceRule = false,
            delTag = 6L,
            ttsEngine = "engineX",
            splitLongChapter = false,
            readSimulating = true,
            startDate = LocalDate.of(2024, 3, 5),
            startChapter = 12,
            dailyChapters = 7
        )
        // GSON 默认输出所有非 null 字段 (Boolean=false/Number=0 也会输出), null 字段省略
        val oldJson = """{"reverseToc":true,"reSegment":true,"imageStyle":"FULL","useReplaceRule":false,"delTag":6,"ttsEngine":"engineX","splitLongChapter":false,"readSimulating":true,"startDate":{"year":2024,"month":3,"day":5},"startChapter":12,"dailyChapters":7}"""
        assertConfigEquals(cfg, converters.stringToReadConfig(oldJson))
    }

    @Test
    fun `GSON LocalDate 非标准形态可被复刻 decode`() {
        val json = converters.stringToReadConfig(
            """{"readSimulating":true,"startDate":{"year":2020,"month":12,"day":31}}"""
        )
        assertNotNull(json)
        assertEquals(LocalDate.of(2020, 12, 31), json!!.startDate)
        assertEquals(true, json.readSimulating)
    }

    @Test
    fun `GSON 部分字段旧 JSON 缺省字段回落默认值`() {
        // GSON 默认不写 null 字段, 故只含被赋非默认值的字段 + 默认值非 null 的字段
        // ReadConfig(imageStyle = "TEXT", dailyChapters = 5) 的 GSON 输出:
        //   reverseToc=false, reSegment=false, imageStyle="TEXT", delTag=0,
        //   splitLongChapter=true, readSimulating=false, dailyChapters=5
        //   (useReplaceRule/ttsEngine/startDate/startChapter 为 null, GSON 默认省略)
        val oldJson = """{"reverseToc":false,"reSegment":false,"imageStyle":"TEXT","delTag":0,"splitLongChapter":true,"readSimulating":false,"dailyChapters":5}"""
        val decoded = converters.stringToReadConfig(oldJson)
        assertNotNull(decoded)
        assertEquals("TEXT", decoded!!.imageStyle)
        assertEquals(5, decoded.dailyChapters)
        // 未出现的字段应回落默认
        assertEquals(false, decoded.reverseToc)
        assertEquals(true, decoded.splitLongChapter)
        assertNull(decoded.useReplaceRule)
        assertNull(decoded.startDate)
        assertEquals(0L, decoded.delTag)
    }

    @Test
    fun `GSON 显式 null 字段可被 decode`() {
        // GSON serializeNulls 强制写出 "imageStyle":null 等
        val oldJson = """{"reverseToc":false,"reSegment":false,"imageStyle":null,"useReplaceRule":null,"delTag":0,"ttsEngine":null,"splitLongChapter":true,"readSimulating":false,"startDate":null,"startChapter":null,"dailyChapters":3}"""
        val decoded = converters.stringToReadConfig(oldJson)
        assertNotNull(decoded)
        assertNull(decoded!!.imageStyle)
        assertNull(decoded.useReplaceRule)
        assertNull(decoded.ttsEngine)
    }

    @Test
    fun `未知字段应被忽略而非报错`() {
        // 模拟被注释掉的 pageAnim 及未来新增字段
        val json = """{"reverseToc":true,"pageAnim":2,"futureField":"x","imageStyle":"SINGLE"}"""
        val decoded = converters.stringToReadConfig(json)
        assertNotNull(decoded)
        assertEquals(true, decoded!!.reverseToc)
        assertEquals("SINGLE", decoded.imageStyle)
    }

    @Test
    fun `encode decode 往返一致`() {
        val samples = listOf(
            ReadConfig(),
            ReadConfig(reverseToc = true, delTag = 2L),
            ReadConfig(
                readSimulating = true,
                startDate = LocalDate.of(2023, 1, 1),
                startChapter = 3,
                dailyChapters = 9,
                imageStyle = "FULL",
                useReplaceRule = true
            )
        )
        for (cfg in samples) {
            val str = converters.readConfigToString(cfg)
            if (cfg == ReadConfig()) {
                // 默认值短路: 与 GSON 旧逻辑一致, 返回 null 不落库
                assertNull(str)
            } else {
                assertNotNull(str)
                assertEquals(cfg, converters.stringToReadConfig(str))
            }
        }
    }

    @Test
    fun `默认值配置短路返回 null`() {
        assertNull(converters.readConfigToString(ReadConfig()))
        assertNull(converters.readConfigToString(null))
    }

    @Test
    fun `非法 JSON 返回 null 不抛异常`() {
        assertNull(converters.stringToReadConfig("{not valid json"))
        assertNull(converters.stringToReadConfig(null))
    }
}
