package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.platform.syncGetString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 导入链"当前显示阶段"的持有者: 阶段归请求 ([ImportStageHost] 由页面模型实现), 显示归当前组合。
 *
 * 三条必须成立的性质:
 * 1. **归属**: 阶段编号由本对象单调递增发放, 结算只认当前编号 (旧组合/迟到的点击不作数);
 *    请求取消或完成时只清"自己那一个"阶段, 不误伤别的请求。
 * 2. **重建只重显**: 阶段存在模型里, 宿主重建后新组合读到同一阶段即重显; 已结算的阶段在结算时
 *    就同步清空, 因此不会"重显已完成的结果"。
 * 3. **真回执**: [choose]/[confirm] 挂起到用户真选完, 请求据此继续或收口; 关闭/返回同样是一次
 *    真回执 (null / [ImportConfirmAction.Cancel]), 不存在"发了动作就没人管"的分支。
 *
 * 同一页面同时只应有一个阶段; 这里用 [gate] 把并发请求排队, 后到的选择不会覆盖先到的那份显示。
 */
class ImportStageOwner : ImportStageHost {

    /** 当前需要呈现的阶段; null = 没有阶段 (无框)。组合期读它, 也是唯一的显示来源。 */
    var stage by mutableStateOf<ImportStage?>(null)
        private set

    private val gate = Mutex()
    private var stageSeq = 0L
    private var choiceAnswer: CompletableDeferred<Int?>? = null
    private var confirmAnswer: CompletableDeferred<ImportConfirmAction>? = null

    override suspend fun choose(title: String, items: List<String>): Int? = gate.withLock {
        val answer = CompletableDeferred<Int?>()
        // 编号先分配再发布: 发布这一步本身可能被丢弃, 凭编号仍能准确清理自己那一个阶段
        val next = ImportStage.Choice(++stageSeq, title, items)
        try {
            // 发布放在保护范围内: 从 Default 切到 Main 再返回时, 返回点可被 prompt cancellation
            // 丢弃 —— 发布若在 try 外, 清理根本不会执行, 阶段与 answer 引用就留在外面
            withContext(mainDispatcher) {
                choiceAnswer = answer
                stage = next
            }
            answer.await()
        } finally {
            // 只终结自己: 阶段按编号清, answer 引用按身份清 (别人的阶段/答案一概不动)。
            // answer.cancel 也放进同一保证执行的范围: 它若排在 NonCancellable 返程之后,
            // 返程被取消就会跳过, 已取消的 Deferred 便留着接受迟到结算
            withContext(NonCancellable + mainDispatcher) {
                clearStage(next.stageId)
                if (choiceAnswer === answer) choiceAnswer = null
                answer.cancel()
            }
        }
    }

    override suspend fun confirm(
        title: String?,
        message: String,
        neutralText: String?,
        okText: String?,
    ): ImportConfirmAction = gate.withLock {
        val answer = CompletableDeferred<ImportConfirmAction>()
        val next = ImportStage.Confirm(++stageSeq, title, message, neutralText, okText)
        try {
            withContext(mainDispatcher) {
                confirmAnswer = answer
                stage = next
            }
            answer.await()
        } finally {
            withContext(NonCancellable + mainDispatcher) {
                clearStage(next.stageId)
                if (confirmAnswer === answer) confirmAnswer = null
                answer.cancel()
            }
        }
    }

    /** 候选列表选中 [index]; 非当前阶段的回执直接丢弃。 */
    fun onChoiceSelected(stageId: Long, index: Int?) {
        if ((stage as? ImportStage.Choice)?.stageId != stageId) return
        stage = null
        choiceAnswer?.complete(index)
        choiceAnswer = null
    }

    /** 确认框结算 ([ImportConfirmAction.Ok]/[Neutral]/[Cancel]); 非当前阶段的回执直接丢弃。 */
    fun onConfirmResolved(stageId: Long, action: ImportConfirmAction) {
        if ((stage as? ImportStage.Confirm)?.stageId != stageId) return
        stage = null
        confirmAnswer?.complete(action)
        confirmAnswer = null
    }

    /** 只清指定编号的阶段 (别的请求在显的那一份不动)。 */
    private fun clearStage(stageId: Long) {
        if (stage?.stageId == stageId) stage = null
    }
}

/**
 * 阶段呈现 (四端同一套 Compose 对话框): 只读 [ImportStageOwner.stage], 点击经 owner 结算。
 *
 * - **身份**: 按阶段编号 `key(stageId)` 呈现, 同类阶段连续接续时是新的一份对话框,
 *   不继承上一份的 dismissing/progress/滚动位置。
 * - **可见性**: 复用 Route 生命周期 —— 本页被另一页盖住或退到后台 (非 RESUMED) 时只隐藏,
 *   不结算: 阶段仍归请求, 回到本页继续呈现。
 * - **按钮语义**: 确认阶段只在给了确定文案时排确定键; 不支持格式那种"只有打开方式 + 取消"
 *   的原版出口不额外多一个只关闭不打开的键。
 */
@Composable
fun ImportStageDialog(owner: ImportStageOwner) {
    // 页面链: 复用既有 rememberRouteVisibleState (Route 生命周期), 不再自建观察器
    val visible by rememberRouteVisibleState()
    ImportStageDialog(owner, visible = visible)
}

/**
 * 窗口链 (主窗口组合, 不在 Route 根里) 的可见性: 取**本窗口自身** Compose 生命周期的
 * STARTED 区间。
 *
 * 不用 CREATED 起点: 官方生命周期映射里 iOS `didEnterBackground`/`viewDidDisappear`
 * 把 STARTED 降到 CREATED, 若按 ">= CREATED" 判, 退到后台的窗口仍算可见。
 * 也不用 RESUMED: 那是焦点区间, 对话框/子窗夺焦点就会掉出 RESUMED, 弹层会自激收放。
 *
 * 直接复用 [Lifecycle.currentStateFlow] (库已提供同源状态流), 不另造布尔观察器。
 */
@Composable
fun rememberWindowVisibleState(): State<Boolean> {
    val current by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    return remember(current) {
        mutableStateOf(current.isAtLeast(Lifecycle.State.STARTED))
    }
}

/**
 * 窗口链 (iOS/鸿蒙/桌面的主窗口组合): 可见性由调用方按**本窗口真实生命周期**给。
 *
 * 不能借页面标志: 主窗口不在 Route 根里, 页面可见性在那里恒为 false, 弹层会永不出现;
 * 也不默认 true: 窗口不可见时真值应为 false, 由窗口宿主提供 (见 [rememberWindowVisibleState])。
 */
@Composable
fun ImportStageDialog(owner: ImportStageOwner, visible: Boolean) {
    if (!visible) return
    when (val current = owner.stage) {
        null -> Unit

        is ImportStage.Choice -> key(current.stageId) {
            // 复用既有列表选择对话框 (虚拟列表 + 统一的取消/点外部关闭语义), 不自绘一份
            AppSelectorDialog(
                onDismissRequest = { owner.onChoiceSelected(current.stageId, null) },
                title = current.title,
                items = current.items,
                onItemSelected = { index -> owner.onChoiceSelected(current.stageId, index) },
            )
        }

        is ImportStage.Confirm -> key(current.stageId) {
            AppAlertDialog(
                onDismissRequest = {
                    owner.onConfirmResolved(current.stageId, ImportConfirmAction.Cancel)
                },
                title = current.title,
                message = current.message,
                neutralButton = current.neutralText?.let { text ->
                    AlertButton(text) {
                        owner.onConfirmResolved(current.stageId, ImportConfirmAction.Neutral)
                    }
                },
                cancelButton = AlertButton(syncGetString("cancel")) {
                    owner.onConfirmResolved(current.stageId, ImportConfirmAction.Cancel)
                },
                okButton = current.okText?.let { text ->
                    AlertButton(text) {
                        owner.onConfirmResolved(current.stageId, ImportConfirmAction.Ok)
                    }
                },
            )
        }
    }
}
