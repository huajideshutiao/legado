package io.legado.app.ui.association

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.fragment.app.commit
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.Theme
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.LaunchRequestBus
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * legado:// / yuedu:// deep link 透明壳 Activity (对照 master AssociationActivity):
 *
 * - **透明浮层**: AppTheme.Transparent + excludeFromRecents + singleTask, 点击深链时本壳
 *   浮在其他应用之上, 只显示导入对话框/转圈, 主界面页面 (书架/发现等) 零渲染。
 * - **勾选六型 / READ_CONFIG / UNKNOWN**: onActivityCreated/onNewIntent →
 *   [LegadoDeepLinkHandler.handle] → [DeepLinkImportHost] 弹导入对话框, 完成/取消
 *   (pending 置空) 后 finish 回到原应用。
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

    /** 本壳注册的 capabilities 实例, onDestroy 身份校验用。 */
    private var registeredCapabilities: PlatformCapabilities? = null

    /** 注册前的旧值 (冷启动场景恒为 null), onDestroy 还原用。 */
    private var previousCapabilities: PlatformCapabilities? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 冷启动: 壳是首个 Activity, PlatformCapabilities 未注册 (AppDialog.get() 会 error);
        // 热启动 (MainActivity 已注册) 保留其实现, 避免覆盖系统动画缩放等差异
        if (PlatformCapabilityProviders.getOrNull() == null) {
            previousCapabilities = PlatformCapabilityProviders.getOrNull()
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
            // 注册前旧值恒为 null, 故还原为不捕获 Activity 的静态兜底,
            // 死壳 (持有本 Activity 的匿名对象) 不再常驻 get(), Activity 泄漏消除
            PlatformCapabilityProviders.register(previousCapabilities ?: idleCapabilities)
        }
        super.onDestroy()
    }

    companion object {

        /** 文件关联 scheme (对照原版挂在透明壳上的两条 VIEW filter 的 data scheme)。 */
        private val fileAssociationSchemes = setOf("content", "file", "app")
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
            // 纯导入 (勾选六型/readConfig/unknown): 只挂共享导入宿主, 不渲染主界面页面
            DeepLinkImportHost()
            // 收壳: 先等到有导入请求, 再等它被消费 (对话框关闭) → 结束透明壳回到原应用。
            // 不能直接 "pending 为空即 finish": 文件关联走嗅探/权限申请, 期间 pending 恒空;
            // 深链路径 handleIntent 已在组合前置好 pending, 第一步立即通过, 语义不变。
            // 文件关联里不产生导入请求的分支 (书籍导入/权限拒绝/不支持格式) 由
            // FileAssociationFragment 自己 finish, 本 effect 挂着无副作用。
            LaunchedEffect(Unit) {
                LegadoDeepLinkHandler.pending.first { it != null }
                LegadoDeepLinkHandler.pending.first { it == null }
                finish()
            }
            return
        }
        // addToBookshelf / read: 壳内转圈抓书 (透明浮层), 直投进程内总线后唤起主界面
        WaitDialog(visible = true, onDismissRequest = {})
        LaunchedEffect(nav) {
            runCatching {
                withContext(Dispatchers.IO) { resolveRouteForNav(nav.first, nav.second) }
            }.onSuccess { route ->
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
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: run {
            finish()
            return
        }
        // 文件关联 (content/file/app): 对照原版把三条 VIEW filter 全挂透明壳, 壳内直接挂
        // FileAssociationFragment(isShellHost = true) —— 嗅探 json/书籍由它承担, 导入对话框
        // 走 shared pending 链 (壳内 DeepLinkImportHost 渲染), 打开书籍经 route extra 转发主界面
        if (uri.scheme in fileAssociationSchemes) {
            supportFragmentManager.commit {
                add(FileAssociationFragment(uri, isShellHost = true), "FileAssociationFragment")
            }
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
        LegadoDeepLinkHandler.handle(uri.toString())
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
