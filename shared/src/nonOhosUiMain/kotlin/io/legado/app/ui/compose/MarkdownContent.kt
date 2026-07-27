package io.legado.app.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import io.legado.app.ui.compose.theme.AppTheme

@Composable
actual fun MarkdownContent(content: String, modifier: Modifier) {
    val colors = AppTheme.colors
    Markdown(
        content = content,
        modifier = modifier,
        colors = markdownColor(
            text = colors.primaryText,
            codeBackground = colors.primaryText.copy(alpha = 0.1f),
            inlineCodeBackground = colors.primaryText.copy(alpha = 0.1f),
            dividerColor = colors.secondaryText,
            tableBackground = colors.primaryText.copy(alpha = 0.02f),
        ),
        typography = markdownTypography(),
        imageTransformer = Coil3ImageTransformerImpl,
    )
}
