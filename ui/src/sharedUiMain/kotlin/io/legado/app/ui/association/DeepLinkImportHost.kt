package io.legado.app.ui.association

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.model.webBook.WebBook.matchBookSourceByUrl
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.dialog.WaitDialogHandle
import io.legado.app.ui.widget.dialog.awaitWaitDialogDismissed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.import_complete
import org.jetbrains.compose.resources.stringResource

/**
 * 无"勾选列表"的四型 deep link: 全平台统一走共享实现 ([runSchemeImport]), 无平台优先分支。
 * [DeepLinkImportTarget.of] 对这四型返回 null (见其 KDoc), 不再进"不支持"提示分支。
 */
private val schemeOnlyTypes = setOf(
    DeepLinkImportType.ADD_TO_BOOKSHELF,
    DeepLinkImportType.READ_CONFIG,
    DeepLinkImportType.READ_BOOK,
    DeepLinkImportType.UNKNOWN,
)

/**
 * 直通四型 (addToBookshelf/read/readConfig/unknown) 的共享实现:
 * - ADD_TO_BOOKSHELF: 直接跳详情页;
 * - READ_BOOK: 书架内直接阅读, 不在书架抓详情进详情页;
 * - READ_CONFIG: 一步导入完成;
 * - UNKNOWN: **先走书籍识别** (零网络按各源 bookUrlPattern 认领这条地址) —— 被认领就直接带
 *   着这个源进抓书链 (不再重算认领), 不被认领才下载嗅探内容类型 —— zip 当排版配置一步完成,
 *   JSON 识别出具体类型后经 [onResolved] 把**同一请求**升级为具体类型 (id 不变, 仍归本窗口),
 *   由宿主继续展示对应导入界面; 不重新入全局队列。
 *
 * @param beforeNavigate 两型导航前的等待钩子 (收起加载框并等它离开界面), 由宿主传入。
 * @param onResolved UNKNOWN 嗅探出具体 JSON 类型时回调; 调用后本请求转入具体类型界面。
 */
private suspend fun runSchemeImport(
    req: DeepLinkImportRequest,
    beforeNavigate: suspend () -> Unit,
    onResolved: (DeepLinkImportRequest) -> Unit,
) {
    when (req.type) {
        DeepLinkImportType.ADD_TO_BOOKSHELF ->
            runCatching { AddToBookshelfShared.add(req.src, beforeNavigate) }
                .onFailure {
                    AppLog.put("添加书籍 ${req.src} 出错", it)
                    Toasters.get().toast(it.message ?: "")
                }

        DeepLinkImportType.READ_BOOK ->
            runCatching { ReadBookShared.read(req.src, beforeNavigate) }
                .onFailure {
                    AppLog.put("阅读书籍 ${req.src} 出错", it)
                    Toasters.get().toast(it.message ?: "")
                }

        DeepLinkImportType.READ_CONFIG -> runCatching { SchemeImportOps.importReadConfig(req.src) }
            .onSuccess { Toasters.get().toast("导入排版成功: $it") }
            .onFailure { Toasters.get().toast(it.message ?: "导入排版失败") }

        DeepLinkImportType.UNKNOWN -> {
            // 先零网络按书源认领: 认领到的源直接带到抓书链, 不让下游再算一遍认领;
            // 不认领才下载内容嗅探类型 (排版配置 / 各类 JSON 一键导入)。
            val matchedSource = runCatching { matchBookSourceByUrl(req.src) }
                .onFailure { AppLog.put("按书源认领 ${req.src} 出错", it) }
                .getOrNull()
            if (matchedSource != null) {
                runCatching { AddToBookshelfShared.add(req.src, beforeNavigate, matchedSource) }
                    .onFailure {
                        AppLog.put("添加书籍 ${req.src} 出错", it)
                        Toasters.get().toast(it.message ?: "")
                    }
            } else runCatching { SchemeImportOps.determineType(req.src) }
                .onSuccess { result ->
                    when (result) {
                        is SchemeImportOps.DetermineResult.ReadConfig ->
                            Toasters.get().toast("导入排版成功: ${result.configName}")

                        is SchemeImportOps.DetermineResult.Json ->
                            onResolved(DeepLinkImportRequest(result.type, result.json, req.id))
                    }
                }
                // 与 ImportTargetDialog 的 Res.string.wrong_format 同一资源, 避免同语义双文案
                .onFailure { Toasters.get().toast(it.message ?: syncGetString("wrong_format")) }
        }

        else -> Unit
    }
}

/**
 * 一次已接纳请求的会话: 请求身份 + 本请求独占的协程作用域。
 *
 * 作用域归请求而非组合: 对话框关闭只结算请求, 写入期间由宿主决定是否结算;
 * 宿主销毁/用户取消时取消作用域, 未提交的下载、解析与写入随之撤销。
 */
class ImportSession internal constructor(
    val request: DeepLinkImportRequest,
    val scope: CoroutineScope,
)

/**
 * 由实际宿主 ViewModelStore 保有的导入会话。
 *
 * 所有业务 Job 与结果状态都在这里, 不保存任何 Composable 回调:
 * scheme/target 的 Job 挂 [session] 的 scope, 配置重建只让新组合观察同一份状态。
 */
class ImportRetained internal constructor(initial: ImportSession) {

    var session by mutableStateOf(initial)
        internal set

    var target by mutableStateOf<DeepLinkImportTarget?>(null)
        internal set

    var checked by mutableStateOf<MutableList<Boolean>?>(null)
        internal set

    var importStarted by mutableStateOf(false)
        internal set

    var schemeStarted by mutableStateOf(false)
        internal set

    var schemeResolved by mutableStateOf(false)
        internal set

    var schemeCompleted by mutableStateOf(false)
        internal set

    /** 真实终态; UI 只消费它并结算, 旧组合不存在也不会被调用。 */
    var done by mutableStateOf(false)
        internal set

    var importedCount by mutableStateOf<Int?>(null)
        internal set

    val waitVisible: MutableState<Boolean> = mutableStateOf(true)

    /** 本请求等待框的归属句柄: "先关框再跳转"只等本请求自己的框。 */
    val waitHandle = WaitDialogHandle()

    internal var schemeJob: Job? = null
    internal var importJob: Job? = null

    /** scheme 业务只起一次, 且 Job 留在请求 scope 内; 新组合只观察。 */
    internal fun startScheme(request: DeepLinkImportRequest) {
        if (schemeJob != null || schemeStarted || schemeResolved || schemeCompleted) return
        schemeStarted = true
        schemeJob = session.scope.launch {
            try {
                var resolved = false
                runSchemeImport(
                    request,
                    beforeNavigate = {
                        awaitWaitDialogDismissed(
                            { waitVisible.value = false },
                            waitHandle,
                        )
                    },
                ) { upgraded ->
                    resolved = true
                    schemeResolved = true
                    session = ImportSession(upgraded, session.scope)
                    target = null
                    checked = null
                    importStarted = false
                }
                if (!resolved) {
                    awaitWaitDialogDismissed(
                        { waitVisible.value = false },
                        waitHandle,
                    )
                    schemeCompleted = true
                    done = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("导入 ${request.src} 出错", e)
                done = true
            }
        }
    }

    /** 勾选导入只触发一次; VM 自己的任务仍在 session.scope 内。 */
    internal fun startTargetImport(target: DeepLinkImportTarget, src: String) {
        if (importStarted || importJob != null) return
        importStarted = true
        importJob = session.scope.launch {
            try {
                target.startImport(src)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("导入 ${src} 出错", e)
                done = true
            }
        }
    }
}

/** 宿主自己的会话保留接口 (Android 用 ViewModelStore 寿命, 其它端可空)。 */
interface ImportSessionHolder {
    fun put(retained: ImportRetained)

    fun get(id: Long): ImportRetained?

    fun remove(id: Long)

    /** 返回本宿主仍可恢复的会话; 没有则 null。 */
    fun restore(): ImportRetained?
}

private class NoOpImportSessionHolder : ImportSessionHolder {
    override fun put(retained: ImportRetained) = Unit
    override fun get(id: Long): ImportRetained? = null
    override fun remove(id: Long) = Unit
    override fun restore(): ImportRetained? = null
}

/**
 * legado:// deep link 导入宿主 (Android/iOS/鸿蒙/desktop 共用, 挂载点见各端 UI 顶层;
 * 语义源头是原版 AssociationActivity + FileAssociationFragment 的 path 分发)。
 *
 * 链路: 窗口可接纳且 [LegadoDeepLinkHandler.pending] 非空 → [LegadoDeepLinkHandler.adopt]
 * 接纳该请求 (移出全局容器, 唯一归属本窗口) → 按类型分流:
 * - 直通四型 → [runSchemeImport] (加载中显示共享 [WaitDialog], 结果 toast);
 * - 勾选六型 → [DeepLinkImportTarget.of] 建 VM → [ImportTargetDialog] 下载解析勾选入库
 *   (与各 Screen 的手动网络导入同链) → 完成/取消均 [LegadoDeepLinkHandler.consume] 本请求。
 *
 * 归属回执: 接纳成功经 [onAdopted] 明确回报承载壳; 结算经 [onSettled] 回报, 壳据此收尾。
 * 请求在接纳后归本窗口私有, 直到确认/取消/失败; 窗口暂停不结束已接纳的请求, 只有真实
 * 销毁才按本策略作废。
 *
 * 冷启动投递先于 UI 组合也不丢: pending 是 StateFlow, 组合起来后立即收到当前值。
 *
 * 导入完成 toast 放在本层而非 [ImportTargetDialog]: 完成回调紧跟 onDismiss→consume,
 * 请求归 null 后内层已退出组合, 计数文案取不到 (rememberString 是 @Composable)。
 *
 * @param canAdopt 本窗口当前是否有资格接纳新请求 (Android 主界面按前台控制);
 *   已接纳请求的展示与执行不受该值影响。
 * @param onAdopted 接纳成功回执 (请求已离开全局容器, 归本窗口)。
 * @param onSettled 本窗口接纳的请求结算 (完成/取消/失败) 后回调, 供承载宿主的壳收尾自身。
 * @param holder 实际宿主拥有的会话保存器; Android 配置重建传 ViewModel 实现, 其它端默认空实现。
 */
@Composable
fun DeepLinkImportHost(
    canAdopt: Boolean = true,
    onAdopted: (DeepLinkImportRequest) -> Unit = {},
    onSettled: (DeepLinkImportRequest) -> Unit = {},
    holder: ImportSessionHolder = NoOpImportSessionHolder(),
    // Android 配置重建时宿主组件会离开组合: false 保留会话/target 给新宿主重新绑定
    settleOnDispose: () -> Boolean = { true },
) {
    var retained by remember { mutableStateOf(holder.restore()) }
    val pendingRequest by LegadoDeepLinkHandler.pending.collectAsState()

    /** 结算当前请求: 只对仍是当前阶段的请求生效, 二次调用为空操作。 */
    fun settle(target: ImportRetained) {
        if (retained !== target) return
        retained = null
        holder.remove(target.session.request.id)
        target.schemeJob?.cancel()
        target.importJob?.cancel()
        target.session.scope.cancel()
        LegadoDeepLinkHandler.consume(target.session.request.id)
        onSettled(target.session.request)
    }

    // 可接纳且空闲时接纳首投请求: 接纳成功后请求离开全局容器, 归本窗口私有;
    // 并发窗口抢同一条时只有一个窗口拿到 true, 其余保持空闲
    LaunchedEffect(pendingRequest, retained, canAdopt) {
        val req = pendingRequest ?: return@LaunchedEffect
        if (!canAdopt || retained != null) return@LaunchedEffect
        if (!LegadoDeepLinkHandler.adopt(req.id)) return@LaunchedEffect
        val created = ImportRetained(
            ImportSession(req, CoroutineScope(SupervisorJob() + mainDispatcher)),
        )
        holder.put(created)
        retained = created
    }
    // 恢复或新接纳都回报一次接纳回执: 配置重建后新壳的 adopted 账目据此重建,
    // 同载荷再次投递才不会把仍在交互的原请求当 DUPLICATE 收壳
    LaunchedEffect(retained) {
        retained?.let { onAdopted(it.session.request) }
    }
    // 业务终态由保留状态驱动; 新组合只消费结果并结算, 旧组合不存在也不会被调用
    val currentDone = retained?.done == true
    LaunchedEffect(retained, currentDone) {
        if (currentDone) retained?.let(::settle)
    }
    // 宿主销毁时结算当前已接纳的请求: 清理只随实际宿主寿命, 不在会话 key 变化时触发,
    // 避免首次接纳把旧 effect 的清理误作用到刚接纳的新会话
    DisposableEffect(Unit) {
        onDispose {
            if (settleOnDispose()) retained?.let(::settle)
        }
    }

    val current = retained
    if (current != null) {
        DeepLinkImportContent(current)
        current.importedCount?.let { count ->
            val importCompleteText = stringResource(Res.string.import_complete, count)
            LaunchedEffect(count) {
                Toasters.get().toast(importCompleteText)
                current.importedCount = null
            }
        }
    }
}

/**
 * 单条已接纳请求的导入流程。target/VM/勾选/业务启动标记都挂在 [retained] 上:
 * 新宿主组合只重新绑定 UI, 不重新下载、解析或入库。
 */
@Composable
private fun DeepLinkImportContent(retained: ImportRetained) {
    val req = retained.session.request
    if (req.type in schemeOnlyTypes) {
        // 直通四型: 全平台统一共享实现, 加载中显示共享 WaitDialog (sharedUiMain 全平台同款;
        // 原 Android handleDeepLinkImport 覆盖与其平台外壳已删除)
        WaitDialog(
            visible = retained.waitVisible.value,
            onDismissRequest = { /* 操作不中断, 对齐原 Android 转圈对话框不可点外取消 */ },
            handle = retained.waitHandle,
        )
        LaunchedEffect(retained, req) {
            retained.startScheme(req)
        }
        return
    }

    // 勾选导入六型: 统一走 DeepLinkImportTarget + ImportTargetDialog
    // (与规则订阅同一条链; loading/错误文案由 ImportTargetDialog 内部处理)
    val target = retained.target ?: DeepLinkImportTarget.of(req.type, retained.session.scope).also {
        retained.target = it
        retained.checked = null
        retained.importStarted = false
    }
    LaunchedEffect(retained, target) {
        retained.startTargetImport(target, req.src)
    }
    ImportTargetDialog(
        target = target,
        onDismiss = { retained.done = true },
        onImported = { count -> retained.importedCount = count },
        checkedState = retained.checked,
        onCheckedState = { retained.checked = it },
    )
}
