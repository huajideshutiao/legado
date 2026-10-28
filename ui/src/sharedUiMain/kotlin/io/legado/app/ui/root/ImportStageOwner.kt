package io.legado.app.ui.root

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
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
        val shown = withContext(mainDispatcher) {
            val next = ImportStage.Choice(++stageSeq, title, items)
            choiceAnswer = answer
            stage = next
            next
        }
        try {
            answer.await()
        } finally {
            // 请求撤销/异常: 只收本阶段; 已在 resolve 里结算过的清空是幂等的
            withContext(NonCancellable + mainDispatcher) { clearStage(shown.stageId) }
        }
    }

    override suspend fun confirm(
        title: String?,
        message: String,
        neutralText: String?,
        okText: String?,
    ): ImportConfirmAction = gate.withLock {
        val answer = CompletableDeferred<ImportConfirmAction>()
        val shown = withContext(mainDispatcher) {
            val next = ImportStage.Confirm(++stageSeq, title, message, neutralText, okText)
            confirmAnswer = answer
            stage = next
            next
        }
        try {
            answer.await()
        } finally {
            withContext(NonCancellable + mainDispatcher) { clearStage(shown.stageId) }
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

    private fun clearStage(stageId: Long) {
        if (stage?.stageId == stageId) stage = null
    }
}

/**
 * 阶段呈现 (四端同一套 Compose 对话框): 只读 [ImportStageOwner.stage], 点击经 owner 结算。
 *
 * 放在页面组合里而不是平台能力面里, 是为了让显示对象随当前宿主组合存亡: 重建即重新读阶段,
 * 不需要平台去"找回"一个已消失的 Dialog, 也不会把旧 Activity 交给仍在飞的请求。
 */
@Composable
fun ImportStageDialog(owner: ImportStageOwner) {
    when (val current = owner.stage) {
        null -> Unit

        is ImportStage.Choice -> AppAlertDialog(
            onDismissRequest = { owner.onChoiceSelected(current.stageId, null) },
            title = current.title,
            content = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    current.items.forEachIndexed { index, item ->
                        Text(
                            text = item,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { owner.onChoiceSelected(current.stageId, index) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
            },
            cancelButton = AlertButton(syncGetString("cancel")) {
                owner.onChoiceSelected(current.stageId, null)
            },
        )

        is ImportStage.Confirm -> AppAlertDialog(
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
            okButton = AlertButton(current.okText ?: syncGetString("ok")) {
                owner.onConfirmResolved(current.stageId, ImportConfirmAction.Ok)
            },
        )
    }
}