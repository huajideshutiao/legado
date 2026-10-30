package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
            // 只终结自己: 阶段按编号清, answer 引用按身份清 (别人的阶段/答案一概不动)
            withContext(NonCancellable + mainDispatcher) {
                clearStage(next.stageId)
                if (choiceAnswer === answer) choiceAnswer = null
            }
            // 已取消的 Deferred 不能留活: 迟到的点击/结算不得再成功
            answer.cancel()
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
            }
            answer.cancel()
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
    if (!routeResumed()) return
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

/**
 * 本页是否处于"可以呈现弹层"的状态 (栈顶且前台)。
 *
 * 直接读 Route 生命周期 ([LocalLifecycleOwner] 由 LegadoApp 的 RouteLifecycleOwner 提供):
 * 被压栈/退后台时页面组合可能仍在 (转场离场页、被盖住的页), 但用户看不见它 —— 此时隐藏弹层,
 * 与"取消本次选择"不是一件事。
 */
@Composable
private fun routeResumed(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}
