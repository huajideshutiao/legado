package io.legado.app.ui.book.read.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.post_review
import legado.shared.generated.resources.rating
import legado.shared.generated.resources.review_post_hint
import org.jetbrains.compose.resources.stringResource

/**
 * 发表段评/书评 shared Screen。
 *
 * 下沉自 app 端 `ReviewPostActivity` 的 BottomSheet 输入面板:
 * - Activity 版仅采集文本, 通过 setResult 回传给 ReviewListDialog 调用 viewModel.reply/post
 * - shared 版结构化为标题栏 + 输入框 + 评分条 + 提交按钮, 状态托管于 [ReviewPostScreenModel]
 * - 提交动作通过 [ReviewPostUiActions.onSubmit] 由 Route 层 navigator.pop(payload) 回传,
 *   实际网络提交仍由上层 (ReviewListDialog / ReviewViewModel) 处理
 *
 * 评分条为 shared 端新增 (app BottomSheet 不含), 用 compose.material [Slider] 实现 0..5 步长 0.5。
 */
@Composable
fun ReviewPostScreen(
    state: ReviewPostUiState,
    actions: ReviewPostUiActions,
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxSize()) {
        DialogTitleBar(
            title = stringResource(Res.string.post_review),
            onBack = { actions.onBack() },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // 内容输入框: 多行, placeholder 由 replyPreview 决定 (对照 Activity onCreate hint 构造)
            // state.hint 由 Route 注入 (回复评论时为 "回复 xxx…", 否则 review_post_hint)
            AppTextField(
                value = state.content,
                onValueChange = { actions.onContentChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                placeholder = state.hint.ifBlank { stringResource(Res.string.review_post_hint) },
                maxLines = 8,
            )
            // 评分条: 0..5 步长 0.5, steps=9 表示 9 个中间点共 11 个值
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.rating),
                    color = colors.primaryText,
                    fontSize = 14.sp,
                )
                Slider(
                    value = state.rating,
                    onValueChange = { actions.onRatingChange(it) },
                    valueRange = 0f..5f,
                    steps = 9,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.accent.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "%.1f".format(state.rating),
                    color = colors.primaryText,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(40.dp),
                )
            }
            // 提交按钮: 内容空或提交中禁用, 提交中显示加载指示
            Button(
                onClick = { actions.onSubmit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                enabled = state.content.isNotBlank() && !state.submitting,
                shape = AppTheme.DesignTokens.buttonShape,
                colors = ButtonDefaults.buttonColors(backgroundColor = colors.accent),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        color = colors.bottomBackground,
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(
                    text = stringResource(Res.string.post_review),
                    color = colors.bottomBackground,
                )
            }
        }
    }
}

/**
 * Route 层注入的 UI 动作集合, 与 [BookInfoUiActions] 模式一致。
 */
interface ReviewPostUiActions {
    /** 返回 (navigator.pop) */
    fun onBack()

    /** 内容变更 -> dispatch ContentChange */
    fun onContentChange(content: String)

    /** 评分变更 -> dispatch RatingChange */
    fun onRatingChange(rating: Float)

    /** 提交 -> 切 submitting 态 + navigator.pop(payload) 回传结果 */
    fun onSubmit()
}
