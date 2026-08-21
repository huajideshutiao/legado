package io.legado.app.ui.book.read.review

import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 发表段评/书评 shared ScreenModel。
 *
 * 下沉自 app 端 `ReviewPostActivity`: Activity 仅采集输入文本并以 setResult 回传,
 * 实际提交由调用方 (ReviewListDialog) 处理; shared 版本同样仅托管输入态,
 * 提交动作通过 [ReviewPostUiActions.onSubmit] 回调上抛, 由弹窗 Host
 * ([io.legado.app.ui.route.ReviewPostDialogHost]) 回传调用方。
 *
 * 无"提交中"态: 原版 submit() 即 setResult + finish, 不等网络结果 (提交由列表页 VM
 * 异步做, 失败走 runRule 的 toast), 故这里也不设 loading。
 *
 * hint (placeholder) 对照 Activity onCreate:
 * - replyPreview 非空 → "回复: %s" % replyPreview.take(15) + (超长补省略号)
 * - 否则 → review_post_hint
 * 弹窗 Host 根据 replyPreview 决定, 通过 [ReviewPostUiEvent.ShowHint] 注入。
 */
class ReviewPostScreenModel : ScreenModel {

    private val _state = MutableStateFlow(ReviewPostUiState())
    val state: StateFlow<ReviewPostUiState> = _state.asStateFlow()

    fun dispatch(event: ReviewPostUiEvent) {
        when (event) {
            is ReviewPostUiEvent.ContentChange -> _state.update {
                it.copy(content = event.content)
            }

            is ReviewPostUiEvent.ShowHint -> _state.update { it.copy(hint = event.hint) }
        }
    }
}

data class ReviewPostUiState(
    val content: String = "",
    /** 输入框 placeholder (对照 Activity hint, 默认 review_post_hint) */
    val hint: String = "",
)

sealed interface ReviewPostUiEvent {
    data class ContentChange(val content: String) : ReviewPostUiEvent

    /** 设置输入框 placeholder (对照 Activity onCreate hint 构造) */
    data class ShowHint(val hint: String) : ReviewPostUiEvent
}
