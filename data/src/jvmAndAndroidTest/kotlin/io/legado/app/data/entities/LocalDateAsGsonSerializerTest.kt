package io.legado.app.data.entities

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Book.LocalDateAsGsonSerializer] 行为锁: 守护 LocalDate 旧 GSON 格式兼容
 * ({"year":Y,"month":M,"day":D}) 的序列化/反序列化往返。
 *
 * 字段数断言经 descriptor.elementsCount 守护 Surrogate(year/month/day) 三字段不变;
 * 若 Surrogate 加字段会破坏旧格式兼容, 测试失败。
 */
class LocalDateAsGsonSerializerTest {

    // ReadConfig.startDate 字段挂 @Serializable(with = LocalDateAsGsonSerializer::class),
    // 借 ReadConfig 路径做 LocalDate 往返 (避免手动构造 SerializersModule)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun `LocalDate 往返 保持 year month day`() {
        val original = localDateOf(2024, 1, 15)
        val config = Book.ReadConfig(startDate = original)
        val encoded = json.encodeToString(Book.ReadConfig.serializer(), config)
        val restored = json.decodeFromString(Book.ReadConfig.serializer(), encoded)
        assertNotNull(restored.startDate)
        assertEquals(original, restored.startDate)
    }

    @Test
    fun `序列化输出旧 GSON 格式 year month day 三字段`() {
        val config = Book.ReadConfig(startDate = localDateOf(2024, 1, 15))
        val encoded = json.encodeToString(Book.ReadConfig.serializer(), config)
        // 守护旧 GSON 格式: {"startDate":{"year":2024,"month":1,"day":15}}
        assertEquals("""{"startDate":{"year":2024,"month":1,"day":15}}""", encoded)
    }

    @Test
    fun `反序列化旧 GSON 格式字符串`() {
        // 模拟旧版本 Gson 序列化的 LocalDate 字符串
        val jsonStr = """{"startDate":{"year":2023,"month":12,"day":31}}"""
        val restored = json.decodeFromString(Book.ReadConfig.serializer(), jsonStr)
        assertNotNull(restored.startDate)
        assertEquals(localDateOf(2023, 12, 31), restored.startDate)
    }

    @Test
    fun `LocalDate null 往返`() {
        val config = Book.ReadConfig(startDate = null)
        val encoded = json.encodeToString(Book.ReadConfig.serializer(), config)
        // startDate=null 且 encodeDefaults=false → 字段省略
        val restored = json.decodeFromString(Book.ReadConfig.serializer(), encoded)
        assertNull(restored.startDate)
    }

    @Test
    fun `LocalDate 边界日期 往返`() {
        // 年初
        val jan1 = localDateOf(2024, 1, 1)
        val config1 = Book.ReadConfig(startDate = jan1)
        val encoded1 = json.encodeToString(Book.ReadConfig.serializer(), config1)
        val restored1 = json.decodeFromString(Book.ReadConfig.serializer(), encoded1)
        assertEquals(jan1, restored1.startDate)

        // 年末
        val dec31 = localDateOf(2024, 12, 31)
        val config2 = Book.ReadConfig(startDate = dec31)
        val encoded2 = json.encodeToString(Book.ReadConfig.serializer(), config2)
        val restored2 = json.decodeFromString(Book.ReadConfig.serializer(), encoded2)
        assertEquals(dec31, restored2.startDate)

        // 闰年 2 月 29 日
        val leapDay = localDateOf(2024, 2, 29)
        val config3 = Book.ReadConfig(startDate = leapDay)
        val encoded3 = json.encodeToString(Book.ReadConfig.serializer(), config3)
        val restored3 = json.decodeFromString(Book.ReadConfig.serializer(), encoded3)
        assertEquals(leapDay, restored3.startDate)
    }

    @Test
    fun `字段数不变保障 Surrogate 三字段`() {
        // 守护 LocalDateAsGsonSerializer 内部 Surrogate(year/month/day) 三字段不变,
        // 防止加字段破坏旧 GSON 格式兼容。
        // descriptor 直接复用 Surrogate.serializer().descriptor, elementsCount 即字段数。
        val expectedFieldCount = 3
        val actualFieldCount = Book.LocalDateAsGsonSerializer.descriptor.elementsCount
        assertEquals(
            expectedFieldCount, actualFieldCount,
            "LocalDateAsGsonSerializer Surrogate 字段数变了 (expected=$expectedFieldCount, actual=$actualFieldCount), " +
                "请检查是否破坏旧 GSON 格式 {year,month,day} 兼容"
        )
    }

    @Test
    fun `descriptor 标识符来自 Surrogate`() {
        // descriptor 复用 Surrogate.serializer().descriptor, serialName 应包含 Surrogate 类名
        // 守护 descriptor 来源, 防止误改成 buildClassSerialDescriptor (那样 serialName 会是手写串)
        val serialName = Book.LocalDateAsGsonSerializer.descriptor.serialName
        // Surrogate 是 Book.LocalDateAsGsonSerializer 内的 private data class,
        // kotlinx.serialization 自动生成的 serialName 包含 "Surrogate"
        assertTrue(
            serialName.endsWith("Surrogate"),
            "descriptor.serialName 应来自 Surrogate, 实际: $serialName"
        )
    }

    @Test
    fun `toYearMonthDay 扩展函数返回正确三元组`() {
        val date = localDateOf(2024, 7, 20)
        val (year, month, day) = date.toYearMonthDay()
        assertEquals(2024, year)
        assertEquals(7, month)
        assertEquals(20, day)
    }

    @Test
    fun `localDateNow 返回有效 LocalDate 可被 toYearMonthDay 解析`() {
        // 不硬编码日期, 只验证 localDateNow 返回的 LocalDate 经 toYearMonthDay 三元组能正常取出
        // (year 在合理区间, month 1-12, day 1-31)
        val now = localDateNow()
        val (y, m, d) = now.toYearMonthDay()
        assertTrue(y in 2020..2099, "year 在合理区间, 实际: $y")
        assertTrue(m in 1..12, "month 在 1-12, 实际: $m")
        assertTrue(d in 1..31, "day 在 1-31, 实际: $d")
    }
}
