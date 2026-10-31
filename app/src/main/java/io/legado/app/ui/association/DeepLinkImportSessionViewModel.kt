package io.legado.app.ui.association

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.widget.dialog.WaitDialogHandle
import io.legado.app.ui.widget.dialog.awaitWaitDialogDismissed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 透明导入壳的宿主会话保存器 (生命周期 = 实际 Activity 的 ViewModelStore:
 * 配置重建保留, 真正 finish 才清理)。
 *
 * 已接纳的 deep link 会话、文件关联执行器、抓书导航、待交打开结果全部归这里持有;
 * 配置重建只换 Activity, 长任务与 UI 阶段状态不取消、不重跑。真退出才由 [onCleared]
 * 统一取消清理。
 */
class DeepLinkImportSessionViewModel : ViewModel(), ImportSessionHolder, ImportUiSink {

    private val retained = LinkedHashMap<Long, ImportRetained>()

    /** 壳内全部长任务的作用域: 只随 ViewModel 真正清理而取消。 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    /** 在途抓书导航 (按请求 id 独立, 互不覆盖); Compose 只观察。 */
    val bookNavs = mutableStateMapOf<Long, BookNav>()

    /**
     * 一条抓书导航请求的独立状态与 Job: 第二条 READ/ADD 是独立投递, 不写这里任何
     * 共享槽位, 也不结束第一条。
     */
    inner class BookNav internal constructor(
        val id: Long,
        val type: DeepLinkImportType,
        val src: String,
    ) {
        /** 本条加载框的归属句柄: "先关框再跳转"只等本框。 */
        val waitHandle = WaitDialogHandle()

        /** 已结束 (无论成败): 抓完先收起本条的加载框, 再交结果。 */
        var resolved by mutableStateOf(false)
            internal set

        var route by mutableStateOf<AppRoute?>(null)
            internal set

        var error by mutableStateOf<String?>(null)
            internal set

        internal var job: Job? = null
    }

    /** 在途文件关联执行器 (uiStage 可观察, 由当前有效宿主呈现)。 */
    val fileImports = mutableStateMapOf<String, FileAssociationImport>()
    private val fileSequence = AtomicLong()

    /** 已投递未接纳 / 本窗口已接纳未结算, 均跨配置重建保留。 */
    val pendingHandOffs = LinkedHashMap<Long, DeepLinkImportRequest>()
    val adoptedRequests = LinkedHashSet<Long>()

    /** 原 intent 已被本 VM 消费; 配置重建期间即使工作已结束也不重投。 */
    var intentConsumed by mutableStateOf(false)
        private set

    /** 工作项版本: 任何增删都自增, 当前 Activity 据此状态判定收壳。 */
    var workEpoch by mutableStateOf(0)
        private set

    /**
     * 待交打开结果 (按文件请求 key 归属): 文件流程在重建空档完成也不丢 ——
     * [hasWork] 计入它, 壳不会在结果交付前收掉; 两条请求同帧完成互不覆盖。
     */
    val pendingOpenRoutes = mutableStateMapOf<String, LaunchRequest.OpenRoute>()

    init {
        // 移交方账目结清: 回执本体是持续状态 (adopt→consume 连发也丢不了),
        // 变化信号只用于唤醒, 判定一律读 isHandoffAdopted
        scope.launch {
            LegadoDeepLinkHandler.adoptionTick.collect { pruneAdoptedHandoffs() }
        }
    }

    private fun notifyWorkChanged() {
        workEpoch++
    }

    /** 任意宿主 (含主界面) 已接纳本壳移交的请求: 清掉自己的移交账目并释放回执。 */
    private fun pruneAdoptedHandoffs() {
        var changed = false
        pendingHandOffs.keys.toList().forEach { id ->
            if (LegadoDeepLinkHandler.isHandoffAdopted(id)) {
                pendingHandOffs.remove(id)
                LegadoDeepLinkHandler.releaseHandoff(id)
                changed = true
            }
        }
        if (changed) notifyWorkChanged()
    }

    override fun put(retained: ImportRetained) {
        this.retained[retained.session.request.id] = retained
        notifyWorkChanged()
    }

    override fun get(id: Long): ImportRetained? = retained[id]

    override fun remove(id: Long) {
        if (retained.remove(id) != null) notifyWorkChanged()
    }

    override fun restore(): ImportRetained? = retained.values.firstOrNull {
        LegadoDeepLinkHandler.adoptedRequest(it.session.request.id) != null
    }

    fun hasWork(): Boolean =
        bookNavs.isNotEmpty() ||
            fileImports.isNotEmpty() ||
            pendingOpenRoutes.isNotEmpty() ||
            pendingHandOffs.isNotEmpty() ||
            adoptedRequests.isNotEmpty() ||
            retained.isNotEmpty()

    fun markIntentConsumed() {
        intentConsumed = true
    }

    fun onAdopted(request: DeepLinkImportRequest) {
        pendingHandOffs.remove(request.id)
        LegadoDeepLinkHandler.releaseHandoff(request.id)
        adoptedRequests.add(request.id)
        notifyWorkChanged()
    }

    fun onSettled(request: DeepLinkImportRequest) {
        adoptedRequests.remove(request.id)
        notifyWorkChanged()
    }

    /** @return true 本次投递已入队等待接纳; false 同一载荷已在别处处理。 */
    fun enqueue(request: DeepLinkImportRequest): Boolean {
        val accepted = when (LegadoDeepLinkHandler.enqueue(request)) {
            EnqueueResult.ACCEPTED -> {
                pendingHandOffs[request.id] = request
                // 为本投递登记可持续观察的接纳回执 (结清后释放, 不无限累计)
                LegadoDeepLinkHandler.registerHandoff(request.id)
                true
            }

            EnqueueResult.DUPLICATE -> false
        }
        notifyWorkChanged()
        return accepted
    }

    /** 抓书只启动一次且 Job 归 VM scope; 每条请求独立 nav, 重建只观察同一结果。 */
    fun startBookNav(request: DeepLinkImportRequest) {
        val nav = BookNav(request.id, request.type, request.src)
        bookNavs[request.id] = nav
        notifyWorkChanged()
        nav.job = scope.launch {
            try {
                val result = runCatching {
                    withContext(Dispatchers.IO) { resolveRouteForNav(nav.type, nav.src) }
                }
                // 先收本条的加载框并等它确实离开界面, 再交 UI 导航 (按句柄只等本框)
                awaitWaitDialogDismissed(
                    { nav.resolved = true },
                    nav.waitHandle,
                )
                result.onSuccess { nav.route = it }
                    .onFailure { nav.error = it.message ?: "打开失败" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                nav.error = e.message ?: "打开失败"
            }
        }
    }

    /** UI 已消费本条导航结果: 只清这一条, 其他在途请求不受影响。 */
    fun consumeBookNav(id: Long) {
        bookNavs.remove(id)?.job?.cancel()
        notifyWorkChanged()
    }

    fun startFileImport(uri: Uri, isShellHost: Boolean) {
        val key = "file-import-${fileSequence.incrementAndGet()}"
        val flow = FileAssociationImport(
            scope = scope,
            importKey = key,
            uri = uri,
            isShellHost = isShellHost,
            sink = this,
            onDone = {
                fileImports.remove(key)
                notifyWorkChanged()
            },
            onHandOff = { request -> enqueue(request) },
        )
        fileImports[key] = flow
        notifyWorkChanged()
        flow.start()
    }

    override fun openBook(sourceKey: String, request: LaunchRequest.OpenRoute) {
        pendingOpenRoutes[sourceKey] = request
        notifyWorkChanged()
    }

    /** 当前 UI 已分发本条打开结果: 只消费这一条。 */
    fun consumeOpenRoute(sourceKey: String) {
        if (pendingOpenRoutes.remove(sourceKey) != null) notifyWorkChanged()
    }

    private suspend fun resolveRouteForNav(
        type: DeepLinkImportType,
        bookUrl: String,
    ): AppRoute = when (type) {
        DeepLinkImportType.READ_BOOK -> ReadBookShared.resolveRoute(bookUrl)
        DeepLinkImportType.ADD_TO_BOOKSHELF -> AddToBookshelfShared.resolveRoute(bookUrl)
        else -> error("不支持的导航类型: $type")
    }

    override fun onCleared() {
        // 每个已接纳请求的 scope 是独立 SupervisorJob (不随 VM.scope 取消), 必须逐个取消,
        // 不依赖 UI 稍后的 dispose 补救
        retained.values.forEach { it.session.scope.cancel() }
        retained.keys.toList().forEach { LegadoDeepLinkHandler.consume(it) }
        retained.clear()
        // 只撤尚未被任何宿主接纳的移交; 已接纳的由真实拥有者 consume
        pendingHandOffs.keys.toList().forEach { id ->
            LegadoDeepLinkHandler.discard(id)
            LegadoDeepLinkHandler.releaseHandoff(id)
        }
        pendingHandOffs.clear()
        // 本壳自己接纳的请求由本壳 consume (别的窗口接纳的不在这份账上)
        adoptedRequests.toList().forEach { LegadoDeepLinkHandler.consume(it) }
        adoptedRequests.clear()
        bookNavs.values.forEach { it.job?.cancel() }
        bookNavs.clear()
        fileImports.clear()
        pendingOpenRoutes.clear()
        scope.cancel()
    }
}
