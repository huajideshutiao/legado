package io.legado.app.ui.book.read.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 *   提供 (AppBottomSheetDialog 贴底面板), 状态托管于 [ReviewPostScreenModel]
 * - 提交动作通过 [ReviewPostUiActions.onSubmit] 回调上抛, 实际网络提交仍由上层
 *   (ReviewListDialog / ReviewViewModel) 处理
 */
@Composable
fun ReviewPostScreen(
    state: ReviewPostUiState,
    actions: ReviewPostUiActions,
) {
    val colors = AppTheme.colors
    // 进入即聚焦输入框 + 强制弹键盘 (对照原版 etInput.requestFocus() +
    // windowSoftInputMode=stateAlwaysVisible; CMP 无 stateAlwaysVisible, 由
    // SoftwareKeyboardController.show() 替代, Android 生效, 桌面/iOS 无副作用)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        // 输入框与提交按钮同行 (对照原版水平 LinearLayout: 输入框 weight=1 在左,
        // 按钮 wrap_content 在右, gravity=center_vertical); 输入框多行 maxLines=6
        // (原版 maxLines=6) 但 IME action 仍是 Send (原版 imeOptions=actionSend)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextField(
                value = state.content,
                onValueChange = { actions.onContentChange(it) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp),
                placeholder = state.hint.ifBlank { stringResource(Res.string.review_post_hint) },
                maxLines = 6,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                // 键盘 Send 键直接提交 (原版 imeOptions=actionSend + setOnEditorActionListener)
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { actions.onSubmit() }),
                focusRequester = focusRequester,
            )
            // 提交按钮: 内容空或提交中禁用, 提交中显示加载指示; 文本 14sp (原版 btn_post)
            Button(
                onClick = { actions.onSubmit() },
                modifier = Modifier.padding(start = 8.dp),
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
                    fontSize = 14.sp,
                    color = colors.bottomBackground,
                )
            }
        }
    }
}

/**
 * 弹窗 Host 注入的 UI 动作集合, 与 [BookInfoUiActions] 模式一致。
 */
interface ReviewPostUiActions {
    /** 内容变更 -> dispatch ContentChange */
    fun onContentChange(content: String)

    /** 提交 -> 回传 content 并关闭 (对照 Activity submit = setResult + finish) */
    fun onSubmit()
}
