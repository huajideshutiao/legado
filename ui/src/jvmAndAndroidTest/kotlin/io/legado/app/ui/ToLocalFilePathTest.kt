package io.legado.app.ui

import okio.Path
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [toLocalFilePath] 输入形态矩阵: 它是文件关联分发链与导入框共用的唯一 file:// → 本地路径
 * 收口, 输出必须能被消费方 (okio Path / java.io.File) 按本机规则解析。
 *
 * 网络主机形态的期望值按平台目录分隔符分立: 分隔符为反斜杠的平台 (Windows) 必须输出原生
 * UNC `\\host\share\...` —— okio 的 UNC 判定只认反斜杠双前缀, 正斜杠双前缀会被折叠成
 * 当前盘符根路径 (okio 3.18.1 Buffer.toPath 实证); POSIX 平台维持 `//host/...` 原样。
 */
class ToLocalFilePathTest {

    private val isBackslashPlatform = Path.DIRECTORY_SEPARATOR == "\\"

    private fun check(input: String, expected: String) {
        assertEquals(expected, input.toLocalFilePath())
    }

    @Test
    fun `裸路径与非file载荷一律原样透出`() {
        listOf(
            "D:\\书源\\a.json",
            "/home/user/a.json",
            "\\\\10.0.0.5\\数据\\x.json",
            "http://example.com/a.json",
        ).forEach { check(it, it) }
    }

    @Test
    fun `file三斜杠与单斜杠形态去scheme并剥离盘符前导斜杠`() {
        listOf(
            "file:///D:/a/b/c.json" to "D:/a/b/c.json",
            "file:/D:/a/b.json" to "D:/a/b.json",
            "file:///home/a/c.json" to "/home/a/c.json",
            "file:/Users/x/y.json" to "/Users/x/y.json",
        ).forEach { (input, expected) -> check(input, expected) }
    }

    @Test
    fun `百分号编码与localhost均还原`() {
        listOf(
            "file:///D:/%E4%B8%AD%E6%96%87/a%20b.json" to "D:/中文/a b.json",
            "file://localhost/D:/a/b.json" to "D:/a/b.json",
            "file://localhost/home/a" to "/home/a",
        ).forEach { (input, expected) -> check(input, expected) }
    }

    @Test
    fun `网络主机形态按平台输出原生UNC或维持正斜杠`() {
        // 拖拽链给的是 File.toURI() 形态 (百分号编码); 粘贴/转发可能是未编码中文
        listOf(
            "file://10.0.0.5/%E6%95%B0%E6%8D%AE/x.json",
            "file://10.0.0.5/数据/x.json",
        ).forEach { input ->
            check(
                input,
                if (isBackslashPlatform) "\\\\10.0.0.5\\数据\\x.json" else "//10.0.0.5/数据/x.json",
            )
        }
        // file:\\host\share (非 RFC 的反斜杠形态) 同一收口
        check(
            "file:\\\\10.0.0.5\\数据\\x.json",
            if (isBackslashPlatform) "\\\\10.0.0.5\\数据\\x.json" else "//10.0.0.5\\数据\\x.json",
        )
    }

    @Test
    fun `反斜杠平台上网络主机输出经okio解析不折叠`() {
        // 本次缺陷的回归锚。POSIX 平台 okio 对 //host 的折叠是其既有行为, 不属本契约。
        if (!isBackslashPlatform) return
        val native = "file://10.0.0.5/数据/x.json".toLocalFilePath()
        assertEquals(native, native.toPath().toString())
    }

    @Test
    fun `无路径的主机形态按现行为透出主机段`() {
        check("file://10.0.0.5", "10.0.0.5")
    }
}
