package io.legado.app.ui.book.read.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.rememberSyncedTextFieldState
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.post_review
import legado.shared.generated.resources.review_post_hint
import org.jetbrains.compose.resources.stringResource

/**
 * 发表段评/书评输入面板正文 (对照原版 `activity_review_post.xml` 的 sheet 内容)。
 *
 * 下沉自 app 端 `ReviewPostActivity`: Activity 仅采集文本, 经 setResult 回传给
 * ReviewListDialog 调 viewModel.reply; shared 版同样只采集, 提交经
 * [ReviewPostUiActions.onSubmit] 上抛, 外壳见 [io.legado.app.ui.route.ReviewPostDialogHost]。
 *
 * 尺寸/取色逐条对齐原版 xml (arco_spacing_lg=16dp / md=12dp / default=8dp):
 * sheet paddingHorizontal 16dp + paddingTop 8dp, 输入行 paddingBottom 8dp、垂直居中。
 */
@Composable
fun ReviewPostScreen(
    state: ReviewPostUiState,
    actions: ReviewPostUiActions,
) {
    // 进入即聚焦输入框 + 弹键盘 (对照 etInput.requestFocus() + windowSoftInputMode=stateAlwaysVisible;
    // CMP 无 stateAlwaysVisible, 由 SoftwareKeyboardController.show() 替代, 无软键盘平台为空操作)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
    ) {
        // 输入框 weight=1 在左, 发布按钮 wrap_content 在右 (原版内层水平 LinearLayout)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReviewPostInputField(
                value = state.content,
                onValueChange = actions::onContentChange,
                hint = state.hint.ifBlank { stringResource(Res.string.review_post_hint) },
                onSend = actions::onSubmit,
                focusRequester = focusRequester,
                modifier = Modifier.weight(1f),
            )
            // btn_post: 无填充文本按钮 (原版 TextView + selectableItemBackgroundBorderless),
            // 内容空即禁用 (afterTextChanged → isEnabled = !isNullOrBlank)。textColor 是单色
            // 而非 selector, 故禁用态不变色 —— 与原版一致, 别改成灰色。
            Text(
                text = stringResource(Res.string.post_review),
                color = rememberColor("secondaryText"),
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(DesignTokens.shapeDefault)
                    .clickable(enabled = state.content.isNotBlank()) { actions.onSubmit() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * et_input: 圆角填充输入框 (原版 bg_review_input = background_card + 18dp 圆角, 无下划线),
 * 故不用 MD2 下划线形态的 AppTextField, 走 BasicTextField 自绘容器 (同 AppSearchField 范式)。
 *
 * 13sp / primaryText / hint secondaryText / minHeight 40dp / padding 16dp-12dp / 最多 6 行,
 * 均照原版 xml; 光标取 ThemeStore 强调色 (原版 EditText 光标走主题 colorAccent)。
 */
@Composable
private fun ReviewPostInputField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    onSend: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    ReviewInputCapsule(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        if (value.isEmpty()) {
            ReviewInputHint(hint)
        }
        val state = rememberSyncedTextFieldState(value, onValueChange)
        BasicTextField(
            state = state,
            // inputType=textMultiLine + maxLines=6: 超出 6 行内部滚动
            lineLimits = TextFieldLineLimits.MultiLine(1, 6),
            textStyle = LocalTextStyle.current.copy(
                color = rememberColor("primaryText"),
                fontSize = ReviewInputTextSize,
            ),
            cursorBrush = SolidColor(AppTheme.colors.accent),
            // textCapSentences + imeOptions=actionSend
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            // Send 键直接提交 (原版 setOnEditorActionListener IME_ACTION_SEND → submit())
            onKeyboardAction = { onSend() },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
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
