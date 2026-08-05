package io.legado.desktop.help.webview.win

import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import io.legado.desktop.help.webview.ToolbarAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Button 方案回归 (2026-08-06): comctl32 的 ToolbarWindow32 在 JVM 进程里不可用
 * (TB_ADDBUTTONS=0 / TB_SETSTATE 访问违规, 最小纯 JNA 程序可复现), 工具栏已改为
 * user32 标准 Button 控件。本测试验证新方案在真实 Win32 上: 创建/禁用态/点击链路。
 *
 * 全部在 WebView2 loop 线程执行 (与生产一致), 不依赖 WebView2 Runtime。
 */
class WebView2ToolbarButtonTest {

    private data class Child(val hwnd: WinDef.HWND, val cls: String, val text: String, val id: Int)

    private fun enumChildren(parent: WinDef.HWND): List<Child> {
        val out = ArrayList<Child>()
        User32.INSTANCE.EnumChildWindows(
            parent,
            WinUser.WNDENUMPROC { h, _ ->
                val cls = CharArray(64)
                User32.INSTANCE.GetClassName(h, cls, 64)
                val text = CharArray(64)
                User32.INSTANCE.GetWindowText(h, text, 64)
                val id = User32.INSTANCE.GetWindowLongPtr(h, -12 /* GWL_ID */).toInt()
                out += Child(h, String(cls).trimEnd('\u0000'), String(text).trimEnd('\u0000'), id)
                true
            },
            null,
        )
        return out
    }

    private fun isWindowEnabled(h: WinDef.HWND): Boolean = User32.INSTANCE.IsWindowEnabled(h)

    /** 创建工具栏 (isLogin=true → 应有"确定"按钮), 校验按钮/进度条齐全且标签正确。 */
    @Test
    fun createToolbarWithButtons() {
        runBlocking {
            assumeTrue("仅 Windows", Platform.isWindows())
            assumeTrue("消息泵可用", WebView2Loop.ensureStarted())
            val created = WebView2Loop.runOnLoop(timeoutMs = 30_000) {
                try {
                    val hwnd =
                        WebView2Loop.createWindow(visible = false, title = "toolbar-btn-test")
                    val toolbar = WebView2Toolbar(hwnd, "t", isLogin = true, saveResult = false)
                    toolbar.create()
                    toolbar.resize(600)
                    val children = enumChildren(hwnd)
                    val buttons = children.filter { it.cls == "Button" }
                    val progress = children.filter { it.cls == "msctls_progress32" }
                    check(buttons.size == 5) { "应有 5 个按钮, 实际 ${buttons.size}: $children" }
                    check(progress.size == 1) { "应有进度条: $children" }
                    // 2026-08-07 图标化: 按钮文本 = Segoe MDL2 Assets 图标字符
                    // (返回 E72B/前进 E72A/刷新 E72C/确定 E73E/菜单 E712)
                    check(
                        buttons.map { it.text } == listOf(
                            "\uE72B", "\uE72A", "\uE72C", "\uE73E", "\uE712"
                        )
                    ) {
                        "按钮标签不符: ${buttons.map { it.text }}"
                    }
                    // 按钮 ID 与句柄映射正确 (WM_COMMAND 路由依赖)
                    val byId = buttons.associateBy { it.id }
                    check(byId[1] != null && byId[2] != null && byId[3] != null && byId[5] != null) {
                        "按钮 ID 缺失: ${buttons.map { it.id }}"
                    }
                    "ok"
                } catch (t: Throwable) {
                    System.err.println("[btn-test] block 异常: $t")
                    t.printStackTrace()
                    null
                }
            }
            assertEquals("创建校验通过", "ok", created)
        }
    }

    /** setCanNavigate 经 post 归队后必须正确切换返回/前进禁用态。 */
    @Test
    fun setCanNavigateTogglesButtons() {
        runBlocking {
            assumeTrue("仅 Windows", Platform.isWindows())
            assumeTrue("消息泵可用", WebView2Loop.ensureStarted())
            val hwnd = WebView2Loop.runOnLoop(timeoutMs = 30_000) {
                WebView2Loop.createWindow(visible = false, title = "toolbar-nav-test")
            }!!
            val toolbar = WebView2Toolbar(hwnd, "t", false, false)
            WebView2Loop.runOnLoop { toolbar.create(); toolbar.resize(600) }
            // setCanNavigate 内部 post, 用后续 runOnLoop 作屏障
            toolbar.setCanNavigate(back = true, forward = false)
            val state1 = WebView2Loop.runOnLoop(timeoutMs = 10_000) {
                val buttons = enumChildren(hwnd).filter { it.cls == "Button" }
                val back = buttons.first { it.id == 1 }.hwnd
                val forward = buttons.first { it.id == 2 }.hwnd
                isWindowEnabled(back) to isWindowEnabled(forward)
            }!!
            assertTrue("返回应启用", state1.first)
            assertTrue("前进应禁用", !state1.second)
            toolbar.setCanNavigate(back = false, forward = true)
            val state2 = WebView2Loop.runOnLoop(timeoutMs = 10_000) {
                val buttons = enumChildren(hwnd).filter { it.cls == "Button" }
                val back = buttons.first { it.id == 1 }.hwnd
                val forward = buttons.first { it.id == 2 }.hwnd
                isWindowEnabled(back) to isWindowEnabled(forward)
            }!!
            assertTrue("返回应禁用", !state2.first)
            assertTrue("前进应启用", state2.second)
            WebView2Loop.runOnLoop { User32.INSTANCE.DestroyWindow(hwnd) }
        }
    }

    /** 点击链路: BM_CLICK → 宿主 WM_COMMAND → onCommand → onAction 回调。 */
    @Test
    fun buttonClickFiresAction() {
        runBlocking {
            assumeTrue("仅 Windows", Platform.isWindows())
            assumeTrue("消息泵可用", WebView2Loop.ensureStarted())
            val hwnd = WebView2Loop.runOnLoop(timeoutMs = 30_000) {
                WebView2Loop.createWindow(visible = false, title = "toolbar-click-test")
            }!!
            val toolbar = WebView2Toolbar(hwnd, "t", false, false)
            val action = AtomicReference<ToolbarAction?>()
            toolbar.onAction = { action.set(it) }
            WebView2Loop.runOnLoop {
                toolbar.create()
                toolbar.resize(600)
                WebView2Loop.hookWindow(hwnd) { msg, w, l ->
                    if (msg == 0x0111 /* WM_COMMAND */) toolbar.onCommand(w, l) else false
                }
            }
            // 点"刷新"(ID=3): BM_CLICK 让 BUTTON 向父窗口发 WM_COMMAND
            WebView2Loop.runOnLoop {
                val refresh = enumChildren(hwnd).first { it.cls == "Button" && it.id == 3 }.hwnd
                User32.INSTANCE.SendMessage(
                    refresh, 0x00F5 /* BM_CLICK */, WinDef.WPARAM(0), WinDef.LPARAM(0)
                )
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (action.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertEquals("点击刷新应触发 REFRESH", ToolbarAction.REFRESH, action.get())
            WebView2Loop.runOnLoop {
                WebView2Loop.unhookWindow(hwnd)
                User32.INSTANCE.DestroyWindow(hwnd)
            }
        }
    }

    /** setLoading 经 post 归队后进度条消息不崩 (PBM_SETMARQUEE)。 */
    @Test
    fun setLoadingTogglesProgress() {
        runBlocking {
            assumeTrue("仅 Windows", Platform.isWindows())
            assumeTrue("消息泵可用", WebView2Loop.ensureStarted())
            val hwnd = WebView2Loop.runOnLoop(timeoutMs = 30_000) {
                WebView2Loop.createWindow(visible = false, title = "toolbar-load-test")
            }!!
            val toolbar = WebView2Toolbar(hwnd, "t", false, false)
            WebView2Loop.runOnLoop { toolbar.create(); toolbar.resize(600) }
            toolbar.setLoading(true)
            toolbar.setLoading(false)
            // 屏障: 上面的 post 已消费即不崩
            val done = WebView2Loop.runOnLoop(timeoutMs = 10_000) { "ok" }
            assertEquals("ok", done)
            WebView2Loop.runOnLoop { User32.INSTANCE.DestroyWindow(hwnd) }
        }
    }

    /** 悬垂防护: 窗口销毁后 setCanNavigate/setLoading 仍调用不得抛。 */
    @Test
    fun callsAfterDestroyAreSafe() {
        runBlocking {
            assumeTrue("仅 Windows", Platform.isWindows())
            assumeTrue("消息泵可用", WebView2Loop.ensureStarted())
            val hwnd = WebView2Loop.runOnLoop(timeoutMs = 30_000) {
                WebView2Loop.createWindow(visible = false, title = "toolbar-destroy-test")
            }!!
            val toolbar = WebView2Toolbar(hwnd, "t", false, false)
            WebView2Loop.runOnLoop { toolbar.create(); toolbar.resize(600) }
            WebView2Loop.runOnLoop { User32.INSTANCE.DestroyWindow(hwnd) }
            // 销毁后再调 (post 归队执行, 句柄引用已被 dispose 清空, 不抛)
            toolbar.setCanNavigate(true, true)
            toolbar.setLoading(true)
            val done = WebView2Loop.runOnLoop(timeoutMs = 10_000) { "ok" }
            assertEquals("销毁后调用必须安全", "ok", done)
        }
    }
}
