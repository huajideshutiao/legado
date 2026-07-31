package io.legado.desktop.help.webview

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.utils.NetworkUtils
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * 跨平台兜底引擎: 项目已声明的 `org.openjfx:javafx-web` + `javafx-swing` 依赖。
 *
 * 定位: 系统引擎 (Windows WebView2 / Linux webkit2gtk) 缺失时的二级回退, 替代已归档的
 * KCEF / 未接线的 CEF 骨架 (见 `FallbackWebViewEngines` 的历史 KDoc)。选 JavaFX WebView 的理由:
 * - 依赖已在 `desktop/build.gradle.kts` 声明 (P0.3 方案), 无需再引入 Chromium natives;
 * - `WebEngine.load / loadContent` 天然支持 url / html 两种形态 (配合验证码窗口的 POST/dataUri 场景);
 * - 纯 JVM, 不随包发 native, 跨 Win/Linux 打包目标一致。
 *
 * 已知限制 (运行时才暴露, 探测与调用都包 runCatching):
 * - 无资源请求拦截 API, `sourceRegex/overrideUrlRegex` 嗅探模式抛异常回退 HTTP;
 * - cookie 读写走 JDK 内部 `com.sun.webkit.network.CookieManager` 反射, 拿不到时静默跳过;
 * - 无头机器 (无显示) `Platform.startup` 失败, [isAvailable] 返回 false 回退系统浏览器。
 */
internal object JavaFXWebViewEngine : DesktopWebViewEngine {

    override val id: String get() = "javafx"

    private const val MAX_JS_RETRY = 30
    private const val JS_RETRY_INTERVAL_MS = 1000L
    private const val COOKIE_TIMEOUT_MS = 5_000L
    internal const val DEFAULT_JS = "document.documentElement.outerHTML"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var fxStarted = false

    @Volatile
    private var fxProbed = false

    /** JavaFX 工具包只初始化一次; 失败 (无显示 / 无 GTK) 记为不可用。任意线程可调。 */
    @Synchronized
    private fun ensureFxStarted(): Boolean {
        if (fxStarted) return true
        if (fxProbed) return false
        fxProbed = true
        return runCatching {
            val latch = CountDownLatch(1)
            runCatching { Platform.startup { latch.countDown() } }
                .onFailure { e ->
                    if (e is IllegalStateException) {
                        // 已被其它组件先启动 (如后续接入 JavaFX 播放器), 视为可用
                        Platform.runLater { latch.countDown() }
                    } else throw e
                }
            latch.await(10, TimeUnit.SECONDS)
            fxStarted = true
            true
        }.onFailure { AppLog.put("JavaFX WebView 初始化失败, 继续回退系统浏览器", it) }
            .getOrDefault(false)
    }

    override fun isAvailable(): Boolean = ensureFxStarted()

    override suspend fun fetch(request: WebViewFetchRequest): WebViewFetchResult {
        if (!ensureFxStarted()) throw NoStackTraceException("JavaFX WebView 引擎不可用")
        val sniffing =
            !request.sourceRegex.isNullOrBlank() || !request.overrideUrlRegex.isNullOrBlank()
        if (sniffing) {
            // WebEngine 无资源拦截 API, 无法镜像 WebView2 的 SnifferWebClient
            throw NoStackTraceException("JavaFX WebView 暂不支持资源嗅探 (sourceRegex/overrideUrlRegex), 回退 HTTP")
        }
        return withTimeout(AppConst.timeLimit) {
            runHtml(request)
        }
    }

    /** 对应 app 端 HtmlWebViewClient: 等页面 + 延时 + 反复执行 JS 直到拿到非空结果。 */
    private suspend fun runHtml(request: WebViewFetchRequest): WebViewFetchResult {
        val engine = runOnFx { WebView().engine }
        val redirected = AtomicBoolean(false)
        request.headerMap?.get(AppConst.UA_NAME)?.let { ua ->
            runOnFx { engine.userAgent = ua }
        }
        injectCookies(engine, request)
        runOnFx {
            engine.locationProperty().addListener { _, _, newUrl ->
                if (newUrl.isNotBlank() && newUrl != request.url) redirected.set(true)
            }
            engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.SUCCEEDED) {
                    val location = engine.location
                    harvestCookies(engine, location, request.cookieTag)
                }
            }
            val html = request.html
            when {
                !html.isNullOrEmpty() -> engine.loadContent(html)
                !request.url.isNullOrEmpty() -> engine.load(request.url)
                else -> throw NoStackTraceException("url 与 html 不能同时为空")
            }
        }

        delay(request.delayTime)
        val script = request.javaScript?.takeIf { it.isNotEmpty() } ?: DEFAULT_JS
        repeat(MAX_JS_RETRY + 1) {
            val body = runOnFx { executeScript(engine, script) }
            if (!body.isNullOrEmpty()) {
                val url = runOnFx { engine.location }.takeIf { it.isNotBlank() } ?: request.url.orEmpty()
                return WebViewFetchResult(url, body, redirected.get())
            }
            delay(JS_RETRY_INTERVAL_MS)
        }
        throw NoStackTraceException("js执行超时")
    }

    override fun openWindow(request: WebViewWindowRequest): WebViewWindowHandle? {
        if (!ensureFxStarted()) return null
        val handle = JavaFxWindowHandle(request)
        scope.launch { handle.open() }
        return handle
    }

    /** 把 CookieStore 里已有的 cookie 注入浏览器再导航 (见 [WindowsWebViewEngine.injectCookies] 的说明)。 */
    private suspend fun injectCookies(engine: WebEngine, request: WebViewFetchRequest) {
        val url = request.url?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val store = CookieStoreProviders.get() ?: return
            val cookie = store.getCookie(url).takeIf { it.isNotBlank() } ?: return
            val domain = NetworkUtils.getSubDomain(url).takeIf { it.isNotBlank() } ?: return
            FxCookieManager.set(domain, cookie)
        }.onFailure { AppLog.put("JavaFX cookie 注入失败", it) }
    }

    /** 回收 cookie 写入 CookieStore, 语义对齐 WebView2 的 harvestCookies。 */
    private fun harvestCookies(engine: WebEngine, url: String, tag: String?) {
        if (tag.isNullOrBlank() || url.isBlank()) return
        scope.launch {
            val cookie = withTimeoutOrNull(COOKIE_TIMEOUT_MS) {
                runOnFx { FxCookieManager.get(url) }
            } ?: return@launch
            runCatching { CookieStoreProviders.get()?.setCookie(tag, cookie) }
                .onFailure { AppLog.put("JavaFX cookie 回写失败", it) }
        }
    }

    /** 可见窗口的 cookie 回收, 语义对齐 WebView2 的 harvestWindowCookies。 */
    internal fun harvestWindowCookies(engine: WebEngine, url: String, tag: String?) {
        if (url.isBlank()) return
        scope.launch {
            val cookie = withTimeoutOrNull(COOKIE_TIMEOUT_MS) {
                runOnFx { FxCookieManager.get(url) }
            } ?: return@launch
            runCatching {
                val store = CookieStoreProviders.get() ?: return@runCatching
                store.setCookie(url, cookie)
                if (!tag.isNullOrBlank()) store.setCookie(tag, cookie)
            }.onFailure { AppLog.put("JavaFX 窗口 cookie 回写失败", it) }
        }
    }

    /**
     * 在 FX Application Thread 上执行 [block] 并取回结果 (suspend 版)。
     * JavaFX WebEngine 的创建/load/executeScript 都有线程亲和, 必须经由 runLater。
     */
    internal suspend fun <T> runOnFx(block: () -> T): T {
        if (Platform.isFxApplicationThread()) return block()
        val future = CompletableFuture<T>()
        Platform.runLater {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return withTimeout(AppConst.timeLimit) { future.await() }
    }

    /** WebEngine.executeScript 在 JavaFX 直接返回 JS 值 (String), 与 WebView2 的 JSON 编码不同。 */
    internal fun executeScript(engine: WebEngine, script: String): String? =
        engine.executeScript(script)?.toString()
}

/**
 * 可见窗口句柄: Swing JFrame + JFXPanel 内嵌 JavaFX WebView。
 */
private class JavaFxWindowHandle(
    private val request: WebViewWindowRequest,
) : WebViewWindowHandle {

    @Volatile
    private var engine: WebEngine? = null

    @Volatile
    private var panel: JFXPanel? = null

    @Volatile
    private var frame: JFrame? = null

    @Volatile
    override var currentUrl: String? = null
        private set

    private val closedOnce = AtomicBoolean(false)

    suspend fun open() {
        val created = runOnFxCatch {
            val webView = WebView()
            webView.engine.locationProperty().addListener { _, _, newUrl ->
                if (newUrl.isNotBlank()) {
                    currentUrl = newUrl
                    engine?.let { JavaFXWebViewEngine.harvestWindowCookies(it, newUrl, request.cookieTag) }
                    runCatching { request.onNavigated(newUrl) }
                }
            }
            webView to webView.engine
        }
        if (created == null) {
            close()
            return
        }
        val (webView, webEngine) = created
        engine = webEngine
        currentUrl = request.url
        request.userAgent?.let { webEngine.userAgent = it }

        if (!buildWindow()) {
            close()
            return
        }
        runOnFxCatch { panel?.scene = Scene(webView) }
        injectCookies(webEngine)
        runOnFxCatch {
            val html = request.html
            if (!html.isNullOrEmpty()) webEngine.loadContent(html) else webEngine.load(request.url)
        }
    }

    /** 在 EDT 建 JFrame + JFXPanel。 */
    private suspend fun buildWindow(): Boolean = try {
        val (createdPanel, createdFrame) = runOnEdt {
            val p = JFXPanel()
            val f = JFrame(request.title)
            f.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            f.addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent) = close()
                override fun windowClosing(e: WindowEvent) = close()
            })
            f.contentPane.add(p, BorderLayout.CENTER)
            f.setSize(1000, 700)
            f.setLocationRelativeTo(null)
            f.isVisible = true
            p to f
        }
        panel = createdPanel
        frame = createdFrame
        true
    } catch (t: Throwable) {
        AppLog.put("JavaFX 窗口创建失败: ${request.title}", t)
        false
    }

    private suspend fun injectCookies(engine: WebEngine) {
        val url = request.url.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val store = CookieStoreProviders.get() ?: return
            val cookie = store.getCookie(url).takeIf { it.isNotBlank() } ?: return
            val domain = NetworkUtils.getSubDomain(url).takeIf { it.isNotBlank() } ?: return
            runOnFxCatch { FxCookieManager.set(domain, cookie) }
        }.onFailure { AppLog.put("JavaFX 窗口 cookie 注入失败", it) }
    }

    override suspend fun currentHtml(): String? {
        val target = engine ?: return null
        return JavaFXWebViewEngine.runOnFx {
            JavaFXWebViewEngine.executeScript(target, JavaFXWebViewEngine.DEFAULT_JS)
        }
    }

    override fun reload() {
        val target = engine ?: return
        Platform.runLater { runCatching { target.reload() } }
    }

    override fun close() {
        if (!closedOnce.compareAndSet(false, true)) return
        val targetFrame = frame
        frame = null
        panel = null
        engine = null
        if (targetFrame != null) {
            SwingUtilities.invokeLater { runCatching { targetFrame.dispose() } }
        }
        runCatching { request.onClosed() }
    }

    private suspend fun <T> runOnFxCatch(block: () -> T): T? = try {
        runOnFx(block)
    } catch (t: Throwable) {
        AppLog.put("JavaFX WebView 操作失败", t)
        null
    }

    private suspend fun <T> runOnFx(block: () -> T): T = JavaFXWebViewEngine.runOnFx(block)

    private suspend fun <T> runOnEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val future = CompletableFuture<T>()
        SwingUtilities.invokeLater {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return withTimeout(AppConst.timeLimit) { future.await() }
    }
}

/** JavaFX WebView 的 cookie 读写: 反射访问 JDK 内部单例 `com.sun.webkit.network.CookieManager`。 */
private object FxCookieManager {

    private val manager: Any? by lazy {
        runCatching {
            Class.forName("com.sun.webkit.network.CookieManager")
                .getMethod("getDefault")
                .invoke(null)
        }.onFailure { AppLog.put("JavaFX cookie manager 反射失败", it) }.getOrNull()
    }

    /** 读 [url] 域下的全部 cookie, 拼成 "k=v; k=v"; 无 cookie 返回 null。 */
    fun get(url: String): String? {
        val mgr = manager ?: return null
        return runCatching {
            val store = cookieStore(mgr) ?: return null
            val cookies = store.javaClass.getMethod("get", java.net.URI::class.java)
                .invoke(store, java.net.URI(url)) as List<*>
            cookies.filterIsInstance<java.net.HttpCookie>()
                .joinToString("; ") { "${it.name}=${it.value}" }
                .takeIf { it.isNotBlank() }
        }.onFailure { AppLog.put("JavaFX cookie 读取失败", it) }.getOrNull()
    }

    /** 写入 cookie 到 [domain] (形如 "k=v; k2=v2", 路径 `/`)。 */
    fun set(domain: String, cookie: String): Boolean {
        val mgr = manager ?: return false
        return runCatching {
            val store = cookieStore(mgr) ?: return false
            val add = store.javaClass.getMethod(
                "add", java.net.URI::class.java, java.net.HttpCookie::class.java
            )
            val uri = java.net.URI.create("http://$domain/")
            cookie.split(';').forEach { entry ->
                val index = entry.indexOf('=')
                if (index <= 0) return@forEach
                val c = java.net.HttpCookie(
                    entry.substring(0, index).trim(), entry.substring(index + 1).trim()
                )
                c.path = "/"
                c.domain = domain
                add.invoke(store, uri, c)
            }
            true
        }.onFailure { AppLog.put("JavaFX cookie 写入失败", it) }.getOrDefault(false)
    }

    private fun cookieStore(mgr: Any): Any? = runCatching {
        mgr.javaClass.methods.firstOrNull { it.name == "getCookieStore" }
            ?.invoke(mgr)
    }.getOrNull()
}
