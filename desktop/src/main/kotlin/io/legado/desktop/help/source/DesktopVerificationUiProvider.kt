package io.legado.desktop.help.source

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.SourceUiRequest
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.SourceVerificationHelpShared
import io.legado.app.help.source.VerificationUiProvider
import io.legado.app.help.source.VerificationUiProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.utils.FlowBus
import io.legado.app.utils.browseUrl
import io.legado.desktop.help.webview.DesktopWebViewEngines
import io.legado.desktop.help.webview.WebViewWindowHandle
import io.legado.desktop.help.webview.WebViewWindowRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [VerificationUiProvider] 桌面端实现。
 *
 * - 图片验证码: 发 [SourceUiRequest.VerificationCode] 事件, 由 SourceUiEventBridgeHost
 *   弹 sharedUiMain 的 Compose VerificationCodeDialog 采集并回填结果;
 * - 网页验证 (`saveResult == true`): 用 [DesktopWebViewEngines] 的内嵌浏览器开独立窗口,
 *   窗口带 CustomTab 式工具栏 (标题/进度/返回/前进/刷新/关闭/确定),
 *   语义对照 app 端 `WebViewModel.saveVerificationResult` —— 每次导航完成同步 cookie,
 *   工具栏"确定"或用户关窗后按 refetchAfterSuccess 决定"用新 cookie 重新 HTTP 请求"还是
 *   "回传网页源码"; 引擎不可用时降级: 系统浏览器打开验证页 + 提示, 稍后按 refetch 语义重拉回填
 *   (浏览器 cookie 无法回收, 重拉结果可能仍是验证页, 但给用户一条可操作的路径, 不再直接报错);
 * - 纯打开链接 (`saveResult != true`, 即 java.startBrowser): 同样开内嵌浏览器窗口
 *   (内置浏览器语义, 原 app 端无论 saveResult 都启动内置 WebViewActivity),
 *   cookie 经 cookieTag 回写; 引擎不可用时降级系统浏览器 + 提示。
 */
object DesktopVerificationUiProvider : VerificationUiProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 无引擎降级时, 等用户在系统浏览器完成验证的最长等待 (之后按 refetch 语义重拉回填)。 */
    private const val FALLBACK_VERIFY_WAIT_MS = 90_000L

    override fun showVerificationCodeDialog(url: String, source: BaseSource) {
        // 与 BaseSource.showLoginDialog 同总线; 调用方随后 registerWaitingThread 轮询等待,
        // UI 确认/关闭时经 SourceVerificationHelpShared.setResult + notifyResultArrived 唤醒
        FlowBus.with(EventBus.SOURCE_UI_REQUEST)
            .tryEmit(SourceUiRequest.VerificationCode(source, url))
    }

    override fun startBrowser(
        source: BaseSource,
        url: String,
        title: String,
        saveResult: Boolean?,
        refetchAfterSuccess: Boolean?,
        asBottomSheet: Boolean,
    ) {
        // 原 app 端 SourceVerificationHelp.startBrowser 无论 saveResult 都启动内置
        // WebViewActivity; 桌面端"内置浏览器"= 系统引擎窗口 (WebView2/webkit2gtk/WKWebView)。
        // saveResult != true (java.startBrowser 纯打开) 仅开窗浏览 + cookie 回写,
        // 不接验证回传逻辑; saveResult == true 时窗口关闭后按 refetch 语义回传结果。
        // asBottomSheet 桌面端忽略 (2026-08-11 用户拍板): 一律开普通居中窗口,
        // 不做 app 端 BottomSheetDialog 的贴底半屏长条形态。
        openBrowserWindow(
            source = source,
            url = url,
            title = title,
            verification = saveResult == true,
            refetchAfterSuccess = refetchAfterSuccess != false,
        )
    }

    /**
     * 开内置浏览器窗口 (登录 / 纯浏览 / 网页验证共用)。
     *
     * 对照 WebViewModel.initData: 开窗前先算一次 AnalyzeUrl (含书源 header/登录头 JS),
     * 成功后重拉复用同一份 headerMap, 避免重复 eval 且与原版传参一致;
     * [verification] 为 true 时窗口带"确定"按钮 (回传后关窗), 关窗同样触发验证结果回传,
     * 否则仅浏览 (窗口工具栏仅 返回/前进/刷新/关闭/标题/进度)。
     *
     * 注意: 接口传入的 [startBrowser] asBottomSheet 在桌面端被忽略 (2026-08-11 用户拍板):
     * 一律开普通居中窗口, 不做 app 端 BottomSheetDialog 的贴底半屏长条形态。
     */
    private fun openBrowserWindow(
        source: BaseSource,
        url: String,
        title: String,
        verification: Boolean,
        refetchAfterSuccess: Boolean,
    ) {
        val sourceKey = source.getKey()
        val analyzeUrl = runCatching {
            AnalyzeUrlFactories.create(url, source = source)
        }.getOrNull()
        val headerMap = analyzeUrl?.headerMap?.toMap()
        val engine = DesktopWebViewEngines.get()
        if (engine == null) {
            // 无内嵌引擎降级: 系统浏览器打开 + 提示
            // (对照 WebViewRoute 的 host==null 兜底分支; 系统浏览器 cookie 无法回收,
            // 但验证页本身无需回传源码时 (如站点二次确认/手动放行) 仍可走通)
            browseUrl(url)
            if (verification) {
                val msg = "已在系统浏览器打开验证页($title), 请完成验证; 稍后自动获取结果"
                runCatching { Toasters.get().toastLong(msg) }
                scope.launch {
                    // 给用户留出在浏览器内完成验证的时间; 完成后 setResult+unpark 唤醒等待线程
                    delay(FALLBACK_VERIFY_WAIT_MS)
                    saveVerificationResult(
                        sourceKey = sourceKey,
                        url = url,
                        refetchAfterSuccess = refetchAfterSuccess,
                        headerMap = headerMap,
                        html = { null },
                    )
                }
            } else {
                val msg = "内置浏览器不可用, 已用系统浏览器打开: $title"
                runCatching { Toasters.get().toastLong(msg) }
            }
            return
        }
        // 与原版 baseUrl 取值一致: 有 Origin 头用 Origin, 否则用解析后的地址
        // (rawUrl 可能带 ",{...}" 选项串, 直接丢给浏览器会导航到无效地址)
        val baseUrl = headerMap?.get("Origin") ?: analyzeUrl?.url?.takeIf { it.isNotBlank() } ?: url
        // 对照 WebViewModel.initData 的三条 html 分支 (窗口非登录态 = 原版 !isLogin):
        // 非 http/data 的 url 本身就是 html; POST 先 HTTP 拉 body; dataUri 解码。
        // 验证流程强制后台线程 (shared 侧 check), 用 runBlocking 同步取回, 保持
        // "引擎不可用 / 窗口打开失败"仍同步抛, 避免等待线程永久挂起。
        // 仅在 AWT EDT 上阻塞会冻结 UI, 此时跳过同步抓取 (退回纯 loadUrl)。
        val onEdt = runCatching { java.awt.EventQueue.isDispatchThread() }.getOrDefault(false)
        val html = if (onEdt) null else analyzeUrl?.let { au ->
            when {
                !url.startsWith("data", true) && !url.startsWith("http", true) -> url
                au.isPost() -> runBlocking {
                    runCatching { au.getStrResponseAwait(allowWebView = false).body }.getOrNull()
                }
                AppPattern.dataUriRegex.matches(au.url) -> runBlocking {
                    runCatching { au.getByteArrayAwait().toString(Charsets.UTF_8) }.getOrNull()
                }
                else -> null
            }
        }
        // 关窗回调里要用到句柄本身 (取网页源码), 故先声明再赋值
        var handle: WebViewWindowHandle? = null
        // 同一验证窗口只回传一次: "确定"按钮先抓 html 回传再关窗, 关窗回调不再重复回传
        // (setResult 是覆盖写, 二次空结果会把好结果冲掉)
        val delivered = AtomicBoolean(false)
        handle = engine.openWindow(
            WebViewWindowRequest(
                url = baseUrl,
                html = html,
                title = if (verification) "$title - 完成验证后关闭本窗口" else title,
                // 对照 WebViewActivity.initWebView: 书源指定 UA 时同步给浏览器,
                // 否则验证站点拿到的 UA 与后续 HTTP 重拉不一致, cookie 可能失效
                userAgent = headerMap?.get(AppConst.UA_NAME),
                cookieTag = sourceKey,
                // 书源菜单 (2026-08-08): 真实源类型/源名, 禁用/删除源动作与确认弹窗用
                sourceType = source.getSourceType(),
                sourceName = source.getTag(),
                // 2026-08-11 用户拍板: 桌面端忽略 asBottomSheet (app 端 startBrowser
                // 第三参 true 的 BottomSheetDialog 半屏语义), 一律普通居中窗口,
                // 避免出现"全屏宽 × 半屏高贴底"的长条形窗口
                // 窗口带 CustomTab 式工具栏; startBrowser 无 isLogin 语义 (登录走
                // SourceLoginOverlayDialog → DesktopWebViewSlot), 仅验证窗显示"确定"按钮
                isLogin = false,
                saveResult = verification,
                // 工具栏"确定"按钮 (对照 menu_ok → saveVerificationResult): 页面还活着时
                // 已由引擎抓好 outerHTML, 回传后关窗
                onSaveResult = if (verification) { pageHtml ->
                    if (delivered.compareAndSet(false, true)) {
                        scope.launch {
                            saveVerificationResult(
                                sourceKey = sourceKey,
                                url = url,
                                refetchAfterSuccess = refetchAfterSuccess,
                                headerMap = headerMap,
                                html = { pageHtml },
                            )
                            handle?.close()
                        }
                    }
                } else null,
                onClosed = if (verification) {
                    {
                        // F74 关窗回传语义; "确定"已回传过则跳过
                        if (delivered.compareAndSet(false, true)) {
                            scope.launch {
                                saveVerificationResult(
                                    sourceKey = sourceKey,
                                    url = url,
                                    refetchAfterSuccess = refetchAfterSuccess,
                                    headerMap = headerMap,
                                    html = { handle?.currentHtml() },
                                )
                            }
                        }
                    }
                } else {
                    {} // 纯浏览 (java.startBrowser): 无验证回传, 关窗即结束
                },
            )
        )
        if (handle == null) {
            if (verification) {
                val msg = "内置浏览器窗口打开失败: $title"
                runCatching { Toasters.get().toastLong(msg) }
                throw NoStackTraceException(msg)
            }
            // 纯浏览路径无等待线程, 降级系统浏览器即可 (原版语义不丢)
            browseUrl(url)
            val msg = "内置浏览器窗口打开失败, 已用系统浏览器打开: $title"
            runCatching { Toasters.get().toastLong(msg) }
        }
    }

    /**
     * 对照 app 端 `WebViewModel.saveVerificationResult`:
     * refetchAfterSuccess 时用刚拿到的 cookie 重新走 HTTP 拉一次 (不再过 webView),
     * 否则直接回传窗口里的网页源码; 两条路都以 setResult + 唤醒等待线程收尾。
     */
    private suspend fun saveVerificationResult(
        sourceKey: String,
        url: String,
        refetchAfterSuccess: Boolean,
        headerMap: Map<String, String>?,
        html: suspend () -> String?,
    ) {
        val result = runCatching {
            if (refetchAfterSuccess) {
                val bookSource = AppDbProviders.get().bookSourceDao.getBookSource(sourceKey)
                // headerMapF / coroutineContext 与原版一致: 前者复用开窗前算好的请求头,
                // 后者让重拉能随调用方取消
                AnalyzeUrlFactories.create(
                    url,
                    source = bookSource,
                    coroutineContext = currentCoroutineContext(),
                    headerMapF = headerMap,
                ).getStrResponseAwait(allowWebView = false).body
            } else {
                html()
            }
        }.onFailure { AppLog.put("书源验证结果获取失败", it) }.getOrNull()
        // 对齐 app 端 checkResult: 拿不到也要回填空串, 否则等待线程会一直挂着
        SourceVerificationHelpShared.setResult(sourceKey, result ?: "")
        SourceVerificationHelpShared.notifyResultArrived(sourceKey)
    }
}

/** 桌面端 main 入口注册一次, 任何书源验证流程之前。 */
fun registerDesktopVerificationUiProvider() {
    VerificationUiProviders.register(DesktopVerificationUiProvider)
}
