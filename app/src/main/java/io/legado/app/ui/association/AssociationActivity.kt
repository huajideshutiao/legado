package io.legado.app.ui.association

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.Theme
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.videoDirectTarget
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.LaunchRequestBus
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.dialog.awaitWaitDialogDismissed
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * legado:// / yuedu:// deep link + 文件关联透明壳 Activity (对照 master AssociationActivity):
 *
 * - **透明浮层**: AppTheme.Transparent + excludeFromRecents + singleTask, 点击深链时本壳
 *   浮在其他应用之上, 只显示导入对话框/转圈, 主界面页面 (书架/发现等) 零渲染;
 *   主界面内部导入也落本壳 (普通透明宿主进出, isShellHost=false, 打开书籍普通页面跳转)。
 * - **寿命**: 只看自己的在途工作 —— 文件导入执行器、展示中的导入请求 (确认/取消/失败才
 *   完成交互)、抓书导航; 全部收口才 finish, 不以全局 pending 是否为空决定。已交给共享
 *   导入宿主的请求由其推进, 壳在期间保持在前台。
 * - **ADD_TO_BOOKSHELF / READ_BOOK** (需要导航): 壳内显示共享 [WaitDialog] 抓书
 *   (书架直读先查书架, 无则抓详情; 添加书架直接抓详情 + addType(notShelf)),
 *   **不落库**, 把内存书装进 [AppRoute] (BookRef.Stored 持引用, 不拷贝不序列化) 后
 *   [LaunchRequestBus] 直投 [LaunchRequest.OpenRoute] (asRoot: 书架不进导航栈,
 *   对照 master BookInfoActivity/ReadBookActivity 直开), Intent 只负责唤起主界面, 壳 finish。
 *
 * 容器照常加载 (App 进程 + App.onCreate 的 provider), 但主界面页面不组合;
 * 冷启动 (App 未运行) 时壳是首个 Activity, 需自注册最小 [PlatformCapabilities]
 * (AppDialog 依赖 dialogTransitionSpec, get() 未注册直接 error); 热启动 (MainActivity
 * 已注册 AndroidPlatformCapabilities) 则保留其实现, 不覆盖。
 */
class AssociationActivity : BaseComposeActivity(theme = Theme.Transparent, imageBg = false) {

    /** 待处理的"需要导航"请求 (addToBookshelf / read): 壳内转圈抓书后转发主界面。 */
    private var pendingBookNav by mutableStateOf<Pair<DeepLinkImportType, String>?>(null)

    /** 抓书是否已结束 (无论成败): 抓完先收起加载框, 再转发主界面。 */
    private var bookNavResolved by mutableStateOf(false)

    /** 本壳注册的 capabilities 实例, onDestroy 身份校验用。 */
    private var registeredCapabilities: PlatformCapabilities? = null

    /** 在途文件导入执行器数 (每条文件关联请求一个)。 */
    private var activeFlows = 0

    /** 本壳移交、尚未结算的请求载荷 (类型+源): 含仍在全局队列未接纳的。 */
    private val openHandOffPayloads = mutableSetOf<Pair<DeepLinkImportType, String>>()

    /** 本窗口已接纳、尚未结算的请求载荷 (接纳顺序; 与 onSettled 结算一一配对)。 */
    private val adoptedPayloads = ArrayDeque<Pair<DeepLinkImportType, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 冷启动: 壳是首个 Activity, PlatformCapabilities 未注册 (AppDialog.get() 会 error);
        // 热启动 (MainActivity 已注册) 保留其实现, 避免覆盖系统动画缩放等差异
        if (PlatformCapabilityProviders.getOrNull() == null) {
            val capabilities = object : PlatformCapabilities {
                override fun exitApplication() = finish()

                override fun openExternalUrl(url: String) {
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }

                override fun shareText(text: String) {
                    runCatching {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        startActivity(Intent.createChooser(intent, null))
                    }
                }
            }
            registeredCapabilities = capabilities
            PlatformCapabilityProviders.register(capabilities)
        }
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        // 只清理本壳注册的实例: 期间被 MainActivity 覆盖 (热启动转发导航) 则不动, 避免还原覆盖
        val mine = registeredCapabilities
        if (mine != null && PlatformCapabilityProviders.getOrNull() === mine) {
            // Providers 无 unregister API 且 register 参数非空, "还原为未注册"不可达;
            // 换成不捕获 Activity 的静态兜底, 死壳 (持有本 Activity 的匿名对象) 不再常驻 get()
            PlatformCapabilityProviders.register(idleCapabilities)
        }
        super.onDestroy()
    }

    companion object {

        /** 主界面内部导入的标记 (外部 VIEW intent 不带, 默认按独立壳处置)。 */
        const val EXTRA_IS_SHELL_HOST = "assoc.isShellHost"

        /** 文件关联 scheme (对照原版挂在透明壳上的两条 VIEW filter 的 data scheme; 原版的
         *  `app` scheme 无 MIME 时匹配不了任何 filter, 从无功能性行为, 已清理)。 */
        private val fileAssociationSchemes = setOf("content", "file")

        /** 壳销毁后仍未注册时的静态兜底: 不捕获任何 Activity, 按当前前台 Activity 惰性操作 */
        private val idleCapabilities = object : PlatformCapabilities {
            override fun exitApplication() {
                LifecycleHelp.currentActivity?.finish()
            }

            override fun openExternalUrl(url: String) {
                val context = LifecycleHelp.currentActivity ?: return
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }

            override fun shareText(text: String) {
                val context = LifecycleHelp.currentActivity ?: return
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            null
                        )
                    )
                }
            }
        }
    }

    @Composable
    override fun Content() {
        val nav = pendingBookNav
        if (nav == null) {
            // 纯导入 (勾选六型/readConfig/unknown/文件关联 JSON): 只挂共享导入宿主与壳内弹窗宿主,
            // 不渲染主界面页面; 请求结算 (确认/取消/失败) 经 onSettled 回报壳收尾
            DeepLinkImportHost(onSettled = ::onRequestSettled)
            // 接纳观察: pending 非空 → 空的迁移即本窗口接纳了一条请求 (接纳即离开全局容器),
            // 按接纳顺序入队, 与 onSettled 逐一配对 —— 外部先投的请求由本窗口接纳展示, 同样陪到结算
            LaunchedEffect(Unit) {
                var shown: Pair<DeepLinkImportType, String>? = null
                LegadoDeepLinkHandler.pending.collect { request ->
                    val previous = shown
                    shown = request?.let { it.type to it.src }
                    if (previous != null && previous != shown) {
                        adoptedPayloads.addLast(previous)
                    }
                }
            }
            return
        }
        // addToBookshelf / read: 壳内转圈抓书 (透明浮层), 抓完先收框再转发主界面
        WaitDialog(visible = !bookNavResolved, onDismissRequest = {})
        LaunchedEffect(nav) {
            val result = runCatching {
                withContext(Dispatchers.IO) { resolveRouteForNav(nav.first, nav.second) }
            }
            // 先收起加载框并等它确实离开界面, 再唤起主界面: 壳的窗口在页面转场期间仍在合成,
            // 框不收就会整段转场带着它 (顺序与原版一致: 关框 → 打开详情页 → 结束壳)
            awaitWaitDialogDismissed { bookNavResolved = true }
            result.onSuccess { route ->
                // 同进程直投路由引用 (BookRef.Stored 零拷贝, 保持别名契约; 不序列化不查库),
                // asRoot = 书架不进导航栈 (对照 master BookInfoActivity/ReadBookActivity 直开);
                // Intent 只负责把主界面唤到前台, 不带载荷
                LaunchRequestBus.dispatch(LaunchRequest.OpenRoute(route, asRoot = true))
                startActivity<MainActivity>()
                finish()
            }.onFailure { e ->
                Toasters.get().toast(e.message ?: "打开失败")
                finish()
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (savedInstanceState != null) {
            // 界面重建: 在途工作随旧实例消亡 (协程/对话框/选择请求不复活), 直接收壳不承接业务;
            // 已移交共享导入宿主的请求仍在全局队列, 由回到前台的宿主继续
            finish()
            return
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isFinishing) return
        handleIntent(intent)
    }

    /** 全部在途工作 (文件流程/移交的请求/本窗口已接纳未结算的请求) 收口后结束透明壳;
     * 导航在途时由导航路径自己收壳。 */
    private fun maybeFinish() {
        if (pendingBookNav != null) return
        if (activeFlows > 0 || openHandOffPayloads.isNotEmpty() || adoptedPayloads.isNotEmpty()) return
        finish()
    }

    /** 本窗口接纳的请求结算 (确认/取消/失败): 按接纳顺序配对移出, 扣减移交集合。 */
    private fun onRequestSettled() {
        adoptedPayloads.removeFirstOrNull()?.let { openHandOffPayloads.remove(it) }
        maybeFinish()
    }

    private fun handleIntent(intent: Intent) {
        // 视频直投排在文件关联(书籍)分流之前: manifest 的视频 VIEW filter 就挂在本壳上, 不抢下来
        // 会被 FileAssociationImport 当书嗅探并弹"不支持该格式"。判据与 MainActivity 共用
        // [videoDirectTarget] (shared VideoDirect 一份); asRoot = 书架不进栈, back 即退调用方。
        intent.videoDirectTarget()?.let { target ->
            LaunchRequestBus.dispatch(LaunchRequest.OpenRoute(AppRoute.VideoPlay(target), asRoot = true))
            startActivity<MainActivity>()
            finish()
            return
        }
        val uri = intent.data ?: run {
            finish()
            return
        }
        // 文件关联 (content/file/app): 壳内直接跑一次性执行器; isShellHost 按当次 intent 生效
        // (外部 VIEW 默认 true, 主界面内部导入带 false), 并发请求互不混淆
        if (uri.scheme in fileAssociationSchemes) {
            startFileImport(uri, intent.getBooleanExtra(EXTRA_IS_SHELL_HOST, true))
            return
        }
        val request = LegadoDeepLink.parse(uri.toString())
        if (request == null) {
            // 缺 src / 非 legado 系: 静默结束 (对照 app 端 handleOnLineImport 缺 src finish)
            finish()
            return
        }
        if (request.type == DeepLinkImportType.ADD_TO_BOOKSHELF ||
            request.type == DeepLinkImportType.READ_BOOK
        ) {
            // 需要导航的两型: 壳内处理 (Content 里转圈 → 抓书 → 转发主界面), 不进 shared pending
            pendingBookNav = request.type to request.src
            return
        }
        LegadoDeepLinkHandler.enqueue(request)
        openHandOffPayloads.add(request.type to request.src)
    }

    private fun startFileImport(uri: Uri, isShellHost: Boolean) {
        activeFlows++
        FileAssociationImport(
            activity = this,
            uri = uri,
            isShellHost = isShellHost,
            onDone = {
                activeFlows--
                maybeFinish()
            },
            onHandOff = { request ->
                // 同载荷请求只认一条 (共享入队对在途/排队/进行中同载荷去重): 载荷集合天然
                // 合并多次移交, 结算一次即全部视为完成
                if (openHandOffPayloads.add(request.type to request.src)) {
                    LegadoDeepLinkHandler.enqueue(request)
                }
            },
        ).start()
    }

    /**
     * 抓书并定路由 (复用 shared [ReadBookShared] / [AddToBookshelfShared] 的统一路由解析):
     * - read: 命中在架书直接读 (进入阅读页), 未在书架则走 addToBookshelf (抓详情进详情页);
     * - addToBookshelf: 抓详情 + 未上架标记 → 详情页。
     */
    private suspend fun resolveRouteForNav(
        type: DeepLinkImportType,
        bookUrl: String,
    ): AppRoute = when (type) {
        DeepLinkImportType.READ_BOOK -> ReadBookShared.resolveRoute(bookUrl)
        DeepLinkImportType.ADD_TO_BOOKSHELF -> AddToBookshelfShared.resolveRoute(bookUrl)
        else -> error("不支持的导航类型: $type")
    }
}
