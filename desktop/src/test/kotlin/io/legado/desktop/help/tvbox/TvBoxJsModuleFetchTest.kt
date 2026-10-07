// TVBox JS 模块取源失败归因回归 (桌面 JVM, 离线: 本地 HttpServer 造 403 / HTML / 正常 JS 三种响应)。
//
// 修的是通用机制: 宿主远程模块取源失败时, 失败原因必须随返回值回传 (原先回空串, JS 侧只能报
// `tvbox js module not found: <url>` —— 403、防盗链 HTML、404 全被压成"模块不存在")。
// 用例面向"任何宿主取源失败", 不绑定某个具体配置或站点。
package io.legado.desktop.help.tvbox

import com.sun.net.httpserver.HttpServer
import io.legado.app.help.tvbox.TvBoxSite
import io.legado.app.help.tvbox.TvBoxJsSpiderLoader
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetSocketAddress

class TvBoxJsModuleFetchTest {

    @Test
    fun `403 的远程模块报错带 HTTP 状态`() {
        withServer(403, "<!DOCTYPE html><html><title>Attention Required!</title>") { url ->
            assertFetchFailureFull("$url/cLFE.js", "403")
        }
    }

    @Test
    fun `200 但返回非 JS 的远程模块报错说明响应不是 JS`() {
        withServer(200, "<html><body>请开启 Cookie 后访问</body></html>") { url ->
            assertFetchFailureFull("$url/anti-leech.js", "不是 JS")
        }
    }

    @Test
    fun `正常 JS 模块仍可装载`() {
        withServer(200, "exports.default = function () { return {} }") { url ->
            // 不抛异常即通过 (标记机制不得影响正常模块)
            TvBoxJsSpiderLoader().getSpider(siteOf("$url/spider.js"), "")
        }
    }

    /** 组一个本地站点 api 与一次模块装载, 断言错误文本 (含 cause 链) 里出现 [expected]。 */
    private fun assertFetchFailureFull(api: String, expected: String) {
        val error = assertThrows(Throwable::class.java) {
            TvBoxJsSpiderLoader().getSpider(siteOf(api), "")
        }
        val text = generateSequence<Throwable>(error) { it.cause }
            .joinToString("\n") { it.message.orEmpty() }
        assertTrue("错误文本应含 '$expected', 实际:\n$text", text.contains(expected))
    }

    /** 站点只需 api 指向被测模块: 其余字段与配置无关 (装载在 evaluate 模块时就已失败/成功)。 */
    private fun siteOf(api: String): TvBoxSite = TvBoxSite(
        key = "module-fetch-probe",
        name = "module-fetch-probe",
        type = 3,
        api = api,
        ext = "",
        jar = "",
        playUrl = "",
        searchable = true,
        filterable = true,
        quickSearch = false,
        timeoutSeconds = null,
        header = emptyMap(),
    )

    private fun withServer(status: Int, body: String, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bytes = body.toByteArray()
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    companion object {

        @JvmStatic
        @BeforeClass
        fun bootDesktopRuntime() = TvBoxTestRuntime.bootstrap()
    }
}
