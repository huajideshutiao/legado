package io.legado.desktop.help.webview

import io.legado.app.constant.AppLog
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.toast.Toasters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 可见窗口句柄的公共基类: 收敛三平台 (WebView2 / WebKitGTK / WKWebView) 逐字重复的
 * 窗口侧行为 —— 手动历史栈 (后退/前进, 见各类 KDoc: 引擎无低成本原生历史 API)、
 * cookie 回收、禁用/删除源、确定按钮 (对照原版 menu_ok) 与关窗幂等;
 * 平台差异 (会话类型 / 确认弹窗 / 导航调用) 留给子类钩子。
 */
internal abstract class DesktopWebViewWindowHandleBase(
    protected val request: WebViewWindowRequest,
) : WebViewWindowHandle {

    /** 日志里的平台名 (cookie 回写失败信息前缀)。 */
    protected abstract val platformLabel: String

    /** 缓存最近一次导航地址: 属性读取不能阻塞去问引擎线程。 */
    @Volatile
    override var currentUrl: String? = null
        protected set

    /** isLogin 确认流程: 确定 → reload, 下次导航完成关窗 (对照 menu_ok 的 checking)。 */
    private val checking = AtomicBoolean(false)

    /** JS 抓取 / 源操作等窗口内异步动作的作用域。 */
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 关窗幂等标志 (子类 open 晚到时据此丢弃已创建的平台会话)。 */
    protected val closedOnce = AtomicBoolean(false)

    /**
     * 手动历史栈: 导航完成入栈, 前进后退移动 index。
     * 读写加锁: 导航回调在引擎线程, 路由侧 goBack 可能来自 Compose 线程。
     */
    private val history = ArrayList<String>()

    private var historyIndex = -1

    /** 前进/后退导航只移动 index, 不重复入栈。 */
    private var historyNavPending = false

    /** 手动历史栈维护: 前进/后退导航只移动 index; 普通导航截断前进项后入栈。 */
    protected fun onNavigationForHistory(url: String) {
        synchronized(history) {
            if (historyNavPending) {
                historyNavPending = false
            } else if (history.isEmpty() || history[historyIndex] != url) {
                while (history.size - 1 > historyIndex) history.removeAt(history.size - 1)
                history.add(url)
                historyIndex = history.size - 1
            }
        }
    }

    /** 历史栈是否有可后退页 (工具栏 setCanNavigate 与路由侧 canGoBack 共用)。 */
    protected fun canGoBackInHistory(): Boolean = synchronized(history) { historyIndex > 0 }

    /** 历史栈是否有可前进页。 */
    protected fun canGoForwardInHistory(): Boolean =
        synchronized(history) { historyIndex < history.size - 1 }

    /** 页面内是否可后退, 转发窗口手动历史栈 (供路由侧「页面可后退则后退」逻辑)。 */
    override fun canGoBack(): Boolean = canGoBackInHistory()

    /** 页面内是否可前进。 */
    override fun canGoForward(): Boolean = canGoForwardInHistory()

    override fun goBack() {
        navigateHistory(back = true)
    }

    override fun goForward() {
        navigateHistory(back = false)
    }

    /**
     * 页面内后退/前进: 只移动历史栈 index 并导航, 越界返回 false
     * (Windows 工具栏据此回落到"无历史时返回 = 关窗")。
     */
    protected fun navigateHistory(back: Boolean): Boolean {
        val url = synchronized(history) {
            val index = if (back) historyIndex - 1 else historyIndex + 1
            if (index < 0 || index >= history.size) return false
            historyNavPending = true
            historyIndex = index
            history[index]
        }
        navigateInWindow(url)
        return true
    }

    /** 在窗口内加载指定地址 (平台各自的导航调用; 路由侧 goBack 可能来自非引擎线程, 须自行投递)。 */
    protected abstract fun navigateInWindow(url: String)

    /**
     * 可见窗口的 cookie 回收, 对应 app 端 `WebViewActivity.onPageFinished`:
     * 按页面地址存一份, 有书源 key 时再存一份 (登录态要按 key 取)。
     */
    protected fun harvestWindowCookies(
        url: String,
        tag: String?,
        readCookies: suspend () -> String?
    ) {
        scope.harvestWebViewCookies(
            url,
            listOf(url, tag),
            platformLabel,
            "窗口 cookie",
            readCookies
        )
    }

    /** isLogin 确认流程进行中 (导航完成时据此关窗)。 */
    protected fun isLoginChecking(): Boolean = checking.get()

    /** 禁用源: 直接执行 (对照原版无确认), 成功后关窗。 */
    protected fun onDisableSource() {
        val key = request.cookieTag ?: return
        scope.launch {
            runCatching { SourceHelp.enableSource(key, request.sourceType, false) }
                .onSuccess { close() }
                .onFailure { AppLog.put("禁用书源失败: $key", it) }
        }
    }

    /** 删除源: 先弹确认 (sure_del + 源名, 对照原版 alert), 确认后执行, 成功后关窗。 */
    protected fun onDeleteSource(confirm: (message: String) -> Boolean) {
        val key = request.cookieTag ?: return
        val name = request.sourceName.ifBlank { key }
        if (!confirm("是否确认删除？\n$name")) return
        scope.launch {
            runCatching { SourceHelp.deleteSource(key, request.sourceType) }
                .onSuccess { close() }
                .onFailure { AppLog.put("删除书源失败: $key", it) }
        }
    }

    /**
     * 确定按钮 (对照 menu_ok): isLogin → 提示后 [reloadForCheck] (下次导航完成关窗);
     * saveResult → 页面还活着时抓 outerHTML 回传, 由调用方决定关窗。
     */
    protected fun onOkPressed(reloadForCheck: () -> Unit, evalDefaultJs: suspend () -> String?) {
        when {
            request.isLogin -> {
                if (checking.compareAndSet(false, true)) {
                    runCatching { Toasters.get().toast(CHECK_HOST_COOKIE_TEXT) }
                    reloadForCheck()
                }
            }

            request.saveResult -> {
                // 页面还活着时抓 outerHTML 回传 (对照 saveVerificationResult 的 html 分支)
                scope.launch {
                    val html = runCatching { evalDefaultJs() }.getOrNull()
                    request.onSaveResult?.invoke(html)
                }
            }
        }
    }

    override suspend fun currentHtml(): String? =
        evaluateJavascript(DesktopWebViewEngineBase.DEFAULT_JS)

    final override fun close() {
        if (!closedOnce.compareAndSet(false, true)) return
        destroySession()
        runCatching { request.onClosed() }
    }

    /** 关窗时销毁平台会话 (基类保证只调一次, 之后再回调 onClosed)。 */
    protected abstract fun destroySession()
}
