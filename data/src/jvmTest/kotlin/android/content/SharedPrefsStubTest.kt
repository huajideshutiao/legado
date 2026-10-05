// SharedPrefsStub 的持久化语义回归: 同名单例 / 各类型读写 roundtrip / 重开实例(模拟重启)内容保留。
// 依赖 AppFilesDirs 桌面注册 (FilesJsonStore 落 filesDir); 偏好文件名带测试前缀避免污染运行数据。
package android.content

import io.legado.app.help.file.registerDesktopAppFilesDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedPrefsStubTest {

    @Before
    fun registerFilesDirs() {
        registerDesktopAppFilesDir()
    }

    @Test
    fun `同名多次获取返回同一实例`() {
        val a = SharedPrefsStub.of("test-single")
        val b = SharedPrefsStub.of("test-single")
        assertSame(a, b)
    }

    @Test
    fun `各类型写入读回类型无损`() {
        val prefs = SharedPrefsStub.of("test-roundtrip")
        prefs.edit().apply {
            putString("s", "v")
            putBoolean("b", true)
            putInt("i", 3)
            putLong("l", 5L)
            putFloat("f", 1.5f)
            putStringSet("ss", mutableSetOf("x", "y"))
        }.apply()

        assertEquals("v", prefs.getString("s", null))
        assertEquals(true, prefs.getBoolean("b", false))
        assertEquals(3, prefs.getInt("i", 0))
        assertEquals(5L, prefs.getLong("l", 0L))
        assertEquals(1.5f, prefs.getFloat("f", 0f), 0f)
        assertEquals(setOf("x", "y"), prefs.getStringSet("ss", null))
        assertTrue(prefs.contains("s"))
    }

    @Test
    fun `重新构造实例模拟重启后内容保留`() {
        val name = "test-persist"
        SharedPrefsStub.of(name).edit()
            .putString("k", "v1")
            .putBoolean("kb", true)
            .putInt("ki", 42)
            .putStringSet("kss", mutableSetOf("z"))
            .commit()

        val fresh = SharedPrefsStub(name) // 绕进程内缓存, 模拟重启后从磁盘加载
        assertEquals("v1", fresh.getString("k", null))
        assertEquals(true, fresh.getBoolean("kb", false))
        assertEquals(42, fresh.getInt("ki", 0))
        assertEquals(setOf("z"), fresh.getStringSet("kss", null))
    }
}
