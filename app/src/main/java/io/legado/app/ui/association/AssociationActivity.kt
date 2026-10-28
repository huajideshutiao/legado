package io.legado.app.ui.association

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.core.net.toUri
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.Theme
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.videoDirectTarget
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.LaunchRequestBus
import io.legado.app.base.ComposeDialog
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.startActivity

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

    /** 本壳注册的 capabilities 实例, onDestroy 身份校验用。 */
    private var registeredCapabilities: PlatformCapabilities? = null

    /** 本壳 ViewModelStore: 导入会话、文件流程与抓书导航都随它跨配置重建。 */
    private val importSessionHolder by viewModels<DeepLinkImportSessionViewModel>()

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

        /** 文件关联 scheme (对照原版挂在透明壳上的 VIEW filter 的 content/file 分支);
         *  `app` scheme 是原版在线导入入口, 交共享解析器 (仅限壳外部入口)。 */
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
        // 共享导入宿主始终在组合: 抓书导航/文件阶段切换不卸载它, 已接纳导入不因展示切换被结算
        DeepLinkImportHost(
            holder = importSessionHolder,
            settleOnDispose = { !isChangingConfigurations },
            onAdopted = { request -> importSessionHolder.onAdopted(request) },
            onSettled = { request -> importSessionHolder.onSettled(request) },
        )

        // 抓书导航: 每条请求独立 WaitDialog 与结果分发 (按请求 id 给组合身份, 互不覆盖/迁位);
        // 先执行真实跳转再消费本条记账, 有其他在途工作时壳不收
        importSessionHolder.bookNavs.values.toList().forEach { nav ->
            key(nav.id) {
                WaitDialog(
                    visible = !nav.resolved,
                    onDismissRequest = {},
                    handle = nav.waitHandle,
                )
                val route = nav.route
                LaunchedEffect(nav.id, route) {
                    route ?: return@LaunchedEffect
                    // 同进程直投路由引用 (BookRef.Stored 零拷贝); asRoot = 书架不进栈 (对照 master 直开)
                    LaunchRequestBus.dispatch(LaunchRequest.OpenRoute(route, asRoot = true))
                    startActivity<MainActivity>()
                    importSessionHolder.consumeBookNav(nav.id)
                }
                val error = nav.error
                LaunchedEffect(nav.id, error) {
                    error ?: return@LaunchedEffect
                    Toasters.get().toast(error)
                    importSessionHolder.consumeBookNav(nav.id)
                }
            }
        }

        // 文件流程的 UI 阶段: 始终由当前有效宿主呈现; 按请求 key 给组合身份,
        // 前一条完成移除不引起后面条目的 effect 迁位重启
        importSessionHolder.fileImports.forEach { (importKey, flow) ->
            key(importKey) {
                when (val stage = flow.uiStage) {
                    is ImportUiStage.TreeSelect -> TreeSelectPresenter(flow, stage)
                    is ImportUiStage.UnsupportedConfirm -> ConfirmPresenter(flow, stage)
                    null -> Unit
                }
            }
        }

        // 待交打开结果: 按文件请求 key 归属, 每条独立分发/消费; 计入宿主收尾不丢
        importSessionHolder.pendingOpenRoutes.forEach { (sourceKey, openRoute) ->
            key(sourceKey) {
                LaunchedEffect(sourceKey, openRoute) {
                    LaunchRequestBus.dispatch(openRoute)
                    if (openRoute.asRoot) startActivity<MainActivity>()
                    importSessionHolder.consumeOpenRoute(sourceKey)
                }
            }
        }

        // 全部在途工作收口后结束透明壳: 由 VM 状态驱动, 不调用任何旧 Activity 闭包
        LaunchedEffect(importSessionHolder.workEpoch) {
            if (importSessionHolder.intentConsumed && !importSessionHolder.hasWork()) {
                finish()
            }
        }
    }

    /** 目录选择阶段: 以稳定 key 注册入口; 首次呈现发起, 重建后只重绑在途请求等真实回执。
     *  回执按本次等待的 attemptId 交付, 参数相同 (同一本书重选) 的两次等待互不串。 */
    @Composable
    private fun TreeSelectPresenter(flow: FileAssociationImport, stage: ImportUiStage.TreeSelect) {
        LaunchedEffect(flow, stage) {
            val launcher = registerHandleFile(key = flow.treeKey, callback = { result ->
                val error = result.error
                if (error != null) {
                    flow.failTree(stage.attemptId, error)
                    return@registerHandleFile
                }
                val treeUri = result.uri
                val current = result.value?.toUri()
                flow.resolveTree(
                    stage.attemptId,
                    if (treeUri != null && current != null) treeUri to current else null,
                )
            })
            flow.bindTreeAttemptCancel(stage.attemptId) { launcher.cancel() }
            flow.attachTreePresenter(stage.attemptId) {
                launcher.launch {
                    title = androidAppString("select_book_folder")
                    mode = HandleFileContract.DIR_SYS
                    value = stage.bookUri.toString()
                }
            }
        }
    }

    /** 不支持格式确认阶段: 显示者持本框, 解绑即关 (配置重建不合成用户拒绝);
     *  按钮只结算创建本框的那次等待 (attemptId), 旧框/新框互不串回执;
     *  show 真实失败原异常回原等待 (failConfirm), 不冒充用户拒绝也不永挂。 */
    @Composable
    private fun ConfirmPresenter(flow: FileAssociationImport, stage: ImportUiStage.UnsupportedConfirm) {
        DisposableEffect(flow, stage) {
            var dialog: ComposeDialog? = null
            try {
                dialog = alert(
                    title = androidAppString("draw"),
                    message = androidAppString("file_not_supported", stage.name),
                ) {
                    yesButton { flow.resolveConfirm(stage.attemptId, true) }
                    noButton { flow.resolveConfirm(stage.attemptId, false) }
                    onCancelled { flow.resolveConfirm(stage.attemptId, false) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                flow.failConfirm(stage.attemptId, e)
            }
            onDispose { dialog?.dismiss() }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        // VM 已有在途工作则只重绑 (UI 阶段由新组合重新呈现); 已消费且工作已结束
        // (例如文件流程在重建空档完成) 直接收壳; 只有进程死亡后新 VM 尚未消费
        // intent, 才按仍保留的原 intent 重新接纳
        if (savedInstanceState != null) {
            if (importSessionHolder.hasWork()) return
            if (importSessionHolder.intentConsumed) {
                maybeFinish()
                return
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isFinishing) return
        handleIntent(intent)
    }

    /** 全部在途工作 (文件流程/已投递未接纳/本窗口已接纳未结算的请求) 收口后结束透明壳;
     * 导航在途时由导航路径自己收壳。 */
    private fun maybeFinish() {
        if (importSessionHolder.hasWork()) return
        finish()
    }

    private fun handleIntent(intent: Intent) {
        // 视频直投排在文件关联(书籍)分流之前: manifest 的视频 VIEW filter 就挂在本壳上, 不抢下来
        // 会被 FileAssociationImport 当书嗅探并弹"不支持该格式"。判据与 MainActivity 共用
        // [videoDirectTarget] (shared VideoDirect 一份); asRoot = 书架不进栈, back 即退调用方。
        intent.videoDirectTarget()?.let { target ->
            // 视频真实分发只结清它本条; 壳里还有其他在途工作 (已接纳导入等) 不能连带收掉
            LaunchRequestBus.dispatch(LaunchRequest.OpenRoute(AppRoute.VideoPlay(target), asRoot = true))
            startActivity<MainActivity>()
            maybeFinish()
            return
        }
        val uri = intent.data ?: run {
            // 本投递无载荷: 只按宿主在途判定收尾, 不终结其他合法请求
            maybeFinish()
            return
        }
        importSessionHolder.markIntentConsumed()
        // 文件关联 (content/file/app): 壳内直接跑一次性执行器; isShellHost 按当次 intent 生效
        // (外部 VIEW 默认 true, 主界面内部导入带 false), 并发请求互不混淆
        if (uri.scheme in fileAssociationSchemes) {
            startFileImport(uri, intent.getBooleanExtra(EXTRA_IS_SHELL_HOST, true))
            return
        }
        val request = LegadoDeepLink.parse(uri.toString(), allowAppScheme = true)
        if (request == null) {
            // 缺 src / 非 legado 系: 静默收本投递 (对照 app 端缺 src finish),
            // 但壳里还有其他在途工作时不连带取消
            maybeFinish()
            return
        }
        if (request.type == DeepLinkImportType.ADD_TO_BOOKSHELF ||
            request.type == DeepLinkImportType.READ_BOOK
        ) {
            // 需要导航的两型: 壳内处理 (转圈 → 抓书 → 转发主界面), 不进 shared pending;
            // 每条独立投递独立 nav, 不覆盖也不结束其他导航
            importSessionHolder.startBookNav(request)
            return
        }
        // 同一载荷已被别处接纳: 本投递没有待办, 收掉本次透明壳, 不触碰别处的请求
        if (!importSessionHolder.enqueue(request)) maybeFinish()
    }

    private fun startFileImport(uri: Uri, isShellHost: Boolean) {
        // 执行器与 Job 归 VM: 配置重建只换 Activity, 不取消读取/权限/选择阶段
        importSessionHolder.startFileImport(uri, isShellHost)
    }

}
