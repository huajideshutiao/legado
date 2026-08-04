package io.legado.desktop.help.webview.win

import io.legado.app.help.file.registerDesktopAppFilesDir
import io.legado.desktop.help.webview.unwrapScriptResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 闭环回归: 书源 JS `java.startBrowser` 在桌面端"打不开浏览器"。
 *
 * 根因 (已修复): `WebView2Loop.post` 不启动消息泵线程 (`ensureStarted` 只在
 * `runOnLoop` 里调用, 而 `runOnLoop` 又只在环境创建成功后才执行)。进程内**首次**
 * 调用 WebView2 时 (如启动后直接跑 startBrowser), 环境/窗口创建任务只入队不被消费,
 * 20s 静默超时 → `WebView2Instance.create` 返回 null → 仅打出
 * "WebView2 窗口创建失败: 登录", 且 `pending` 不复位导致进程内永久失败。
 *
 * 这些测试直接跑在真实 Win32 + 真实 WebView2 Runtime 上 (本机需已安装,
 * 未安装则 assume 跳过), 首个测试即"进程首次调用"场景, 修复前必挂。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WebView2BrowserTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUp() {
            // WebView2 环境需要 cacheDir 落盘目录
            registerDesktopAppFilesDir()
        }
    }

    /** 进程内首次调用: 消息泵未启动时必须能拉起线程并创建环境 + 无头实例。 */
    @Test
    fun a_firstCallCreatesEnvironmentAndInstance() = runBlocking {
        val env = WebView2Environment.get()
        assumeTrue("本机未安装 WebView2 Runtime, 跳过", env != null)
        val instance = WebView2Instance.create(visible = false, title = "legado-test")
        assertNotNull("首次调用 (消息泵未启动) 必须能创建 WebView2 实例", instance)
        try {
            val target = "legado-startbrowser-closed-loop"
            instance!!.navigateToString(
                "<!DOCTYPE html><html><head><title>$target</title></head><body>ok</body></html>"
            )
            // 完整链路验证: 环境 → controller → webview → 导航 → JS 执行 → 结果回传
            val title = pollUntil(15_000) {
                unwrapScriptResult(instance.executeScript("document.title", 5_000))
            }
            assertEquals(target, title)
        } finally {
            instance?.close()
        }
    }

    /** 修复前的行为: post 不 ensureStarted, 首次 post 的任务永远不执行。 */
    @Test
    fun b_postStartsMessagePumpWhenFirstUsed() {
        val latch = CountDownLatch(1)
        val threadName = AtomicReference<String?>(null)
        WebView2Loop.post {
            threadName.set(Thread.currentThread().name)
            latch.countDown()
        }
        assertTrue(
            "首次 post 必须启动消息泵并消费任务 (修复前任务永远排队)",
            latch.await(15, TimeUnit.SECONDS)
        )
        assertTrue("任务必须在专用消息泵线程执行", threadName.get()!!.contains("legado-webview2"))
    }

    /** 环境缓存: 多次调用返回同一环境 (pending 清理不能破坏缓存)。 */
    @Test
    fun c_environmentIsCachedAcrossCalls() = runBlocking {
        val env1 = WebView2Environment.get()
        assumeTrue("本机未安装 WebView2 Runtime, 跳过", env1 != null)
        val env2 = WebView2Environment.get()
        assertEquals("环境必须进程级复用", env1, env2)
    }

    /** 可见窗口 (startBrowser 实际路径): 带工具栏创建成功后可导航。 */
    @Test
    fun d_visibleWindowCreatesWithToolbarAndNavigates() = runBlocking {
        val env = WebView2Environment.get()
        assumeTrue("本机未安装 WebView2 Runtime, 跳过", env != null)
        val instance = WebView2Instance.create(
            visible = true,
            title = "legado-startbrowser-test",
            toolbarSpec = WebView2ToolbarSpec(
                title = "startBrowser 闭环测试",
                isLogin = false,
                saveResult = false,
            ),
        )
        assertNotNull("可见窗口必须创建成功", instance)
        try {
            assertNotNull("可见窗口必须带工具栏", instance!!.toolbar)
            instance.navigateToString(
                "<!DOCTYPE html><html><head><title>visible-ok</title></head><body>ok</body></html>"
            )
            val title = pollUntil(15_000) {
                unwrapScriptResult(instance.executeScript("document.title", 5_000))
            }
            assertEquals("visible-ok", title)
        } finally {
            instance?.close()
        }
    }

    /** 导航失败检测: IsSuccess=false 必须触发 onNavigationFailed (错误页/白屏修复)。 */
    @Test
    fun e_navigationFailureTriggersCallback() = runBlocking {
        val env = WebView2Environment.get()
        assumeTrue("本机未安装 WebView2 Runtime, 跳过", env != null)
        val instance = WebView2Instance.create(visible = false, title = "legado-fail-test")
        assertNotNull(instance)
        try {
            val failed = CompletableDeferred<String?>()
            instance!!.onNavigationFailed = { url -> failed.complete(url) }
            // 不可达地址: 连接被拒, NavigationCompleted 必带 IsSuccess=false
            instance.navigate("http://127.0.0.1:1/legado-unreachable")
            val url = withTimeoutOrNull(15_000) { failed.await() }
            assertNotNull("导航失败必须触发 onNavigationFailed (曾为静默白屏)", url)
        } finally {
            instance?.close()
        }
    }

    /** 轮询 [probe] 直到返回非 null, 超时抛断言失败。 */
    private suspend fun pollUntil(
        timeoutMs: Long,
        probe: suspend () -> String?,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val value = probe()
            if (!value.isNullOrBlank()) return value
            delay(200)
        }
        throw AssertionError("轮询超时 (${timeoutMs}ms), 页面未就绪")
    }
}
