package io.legado.app.ui.book.read.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.post_review
import legado.shared.generated.resources.review_post_hint
import org.jetbrains.compose.resources.stringResource

/**
 * 发表段评/书评输入面板正文 (弹窗形态, 对照原版 ReviewPostActivity 底部输入面板)。
 *
 * 下沉自 app 端 `ReviewPostActivity` 的 BottomSheet 输入面板:
 * - Activity 版仅采集文本, 通过 setResult 回传给 ReviewListDialog 调用 viewModel.reply/post
 * - shared 版为纯输入面板 (输入框 + 提交按钮), 外壳由 [io.legado.app.ui.route.ReviewPostDialogHost]
 *   提供 (AppDialog + DialogTitleBar), 状态托管于 [ReviewPostScreenModel]
 * - 提交动作通过 [ReviewPostUiActions.onSubmit] 回调上抛, 实际网络提交仍由上层
 *   (ReviewListDialog / ReviewViewModel) 处理
 */
@Composable
fun ReviewPostScreen(
    state: ReviewPostUiState,
    actions: ReviewPostUiActions,
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        // 内容输入框: 多行, placeholder 由 replyPreview 决定 (对照 Activity onCreate hint 构造)
        // state.hint 由弹窗 Host 注入 (回复评论时为 "回复 xxx…", 否则 review_post_hint)
        AppTextField(
            value = state.content,
            onValueChange = { actions.onContentChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            placeholder = state.hint.ifBlank { stringResource(Res.string.review_post_hint) },
            maxLines = 8,
        )
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

/**
 * 弹窗 Host 注入的 UI 动作集合, 与 [BookInfoUiActions] 模式一致。
 */
interface ReviewPostUiActions {
    /** 返回 (关闭弹窗) */
    fun onBack()

    /** 内容变更 -> dispatch ContentChange */
    fun onContentChange(content: String)

    /** 提交 -> 回传 content 并关闭 (对照 Activity submit = setResult + finish) */
    fun onSubmit()
}
