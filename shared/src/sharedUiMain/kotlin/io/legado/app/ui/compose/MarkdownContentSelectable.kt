package io.legado.app.ui.compose

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// 跨平台 Markdown 渲染: sharedUiMain 走 mikepenz+Coil3, ohosMain stub 用 Text
@Composable
expect fun MarkdownContent(content: String, modifier: Modifier = Modifier)

// 包裹 SelectionContainer 支持文本选择, 各平台共用 (MarkdownContent actual 自动适配渲染后端)
@Composable
fun MarkdownContentSelectable(content: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        MarkdownContent(content = content, modifier = modifier)
    }
}
