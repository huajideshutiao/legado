package io.legado.app.ui.book.read.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.platform.rememberColor

/**
 * 段评输入胶囊: 对应原版 `drawable/bg_review_input` (background_card + 18dp 圆角)。
 *
 * 列表底部触发器 (InputBar) 与发帖面板输入框在原版共用这一个 drawable, 故形状与底色收在这里;
 * 内外边距两处不同 (原版分别写在各自 xml 上), 由调用方给。
 */
@Composable
internal fun ReviewInputCapsule(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(rememberColor("background_card"))
            // clickable 须排在 clip 之后, 否则波纹不被胶囊裁剪
            .let { if (onClick == null) it else it.clickable(onClick = onClick) }
            .padding(contentPadding),
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

/** 胶囊内提示文案: 原版 `textColorHint=secondaryText` + `textSize=13sp`。 */
@Composable
internal fun ReviewInputHint(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        color = rememberColor("secondaryText"),
        fontSize = ReviewInputTextSize,
        maxLines = maxLines,
    )
}

/** 原版触发器与 et_input 同为 13sp。 */
internal val ReviewInputTextSize = 13.sp
