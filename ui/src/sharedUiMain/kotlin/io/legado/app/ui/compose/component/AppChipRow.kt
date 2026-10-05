package io.legado.app.ui.compose.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 胶囊 chip 行 (标题 chip + 选项 chip 横排, 超宽横向滚动, 鼠标滚轮转横向)。
 *
 * 收拢各屏同款 UI (搜索选项/发现参数/漫画搜索筛选/插件语言筛选): 行边距
 * spacingDefault/spacingXs, chip 间距 spacingXs ([AppFilletTextButton] 自带 spacingXs inset),
 * 选中态 alpha 0.8(标题)/1.0(选中)/0.5(未选中) — 对齐原版 setUpExploreOptions 语义。
 * chip 一律用 [AppChipRowTitle]/[AppChipRowOption], 不直接排 [AppFilletTextButton]。
 *
 * 鼠标滚轮转横向滚动内建 ([horizontalMouseWheel]); [scrollState] 供调用点共用,
 * [modifier] 追加在横向滚动之后、行内边距之前 (如整行点击)。
 */
@Composable
fun AppChipRow(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .horizontalMouseWheel(scrollState)
            .then(modifier)
            .padding(horizontal = DesignTokens.spacingDefault, vertical = DesignTokens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** chip 行标题 chip: 粗体 + 0.8 透明, 点按通常为重置/打开选择器。 */
@Composable
fun AppChipRowTitle(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    AppFilletTextButton(
        text = text,
        modifier = modifier,
        alpha = 0.8f,
        bold = true,
        onClick = onClick,
    )
}

/**
 * chip 行选项 chip: 自带前置 spacingXs 间距; 选中全亮、未选中半透明
 * (alpha 作用于整颗 chip 含背景, 对齐原版 setUpExploreOptions 语义)。
 */
@Composable
fun AppChipRowOption(
    text: String,
    selected: Boolean = true,
    onClick: () -> Unit,
) {
    Spacer(Modifier.width(DesignTokens.spacingXs))
    AppFilletTextButton(
        text = text,
        alpha = if (selected) 1f else 0.5f,
        onClick = onClick,
    )
}
