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
 * 提交动作通过 [ReviewPostUiActions.onSubmit] 回调由 Route 层 navigator.pop 回传结果。
 *
 * 评分 (rating) 为 shared 端新增字段, app 端原 BottomSheet 输入面板不含评分,
 * 桌面/iOS 全功能页面需要评分条入口。
 *
 * hint (placeholder) 对照 Activity onCreate:
 * - replyPreview 非空 → "回复 %s: " % replyPreview.take(15) + (省略号)
 * - 否则 → review_post_hint
 * Route 层根据 AppRoute.ReviewPost.replyPreview 决定, 通过 [ReviewPostUiEvent.ShowHint] 注入。
 */
class ReviewPostScreenModel : ScreenModel {

    private val _state = MutableStateFlow(ReviewPostUiState())
    val state: StateFlow<ReviewPostUiState> = _state.asStateFlow()

    fun dispatch(event: ReviewPostUiEvent) {
        when (event) {
            is ReviewPostUiEvent.ContentChange -> _state.update {
                it.copy(content = event.content)
            }

            is ReviewPostUiEvent.RatingChange -> _state.update {
                it.copy(rating = event.rating)
            }

            ReviewPostUiEvent.SubmitStart -> _state.update { it.copy(submitting = true) }
            ReviewPostUiEvent.SubmitEnd -> _state.update { it.copy(submitting = false) }
            is ReviewPostUiEvent.ShowHint -> _state.update { it.copy(hint = event.hint) }
        }
    }
}

data class ReviewPostUiState(
    val content: String = "",
    // 0..5 步长 0.5,0 表示未评分
    val rating: Float = 0f,
    val submitting: Boolean = false,
    /** 输入框 placeholder (对照 Activity hint, 默认 review_post_hint) */
    val hint: String = "",
)

sealed interface ReviewPostUiEvent {
    data class ContentChange(val content: String) : ReviewPostUiEvent
    data class RatingChange(val rating: Float) : ReviewPostUiEvent
    object SubmitStart : ReviewPostUiEvent
    object SubmitEnd : ReviewPostUiEvent

    /** 设置输入框 placeholder (对照 Activity onCreate hint 构造) */
    data class ShowHint(val hint: String) : ReviewPostUiEvent
}
