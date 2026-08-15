package io.legado.app.ui.compose

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit

/**
 * 只读可滚动可选择的文本 (官方新版 TextFieldState API)。
 *
 * 替代 `SelectionContainer + Text + verticalScroll` 组合: Compose 的 [SelectionContainer]
 * 本身不实现选区自动滚动 (拖选/拖手柄越界时文本不跟随滚动), 只有文本输入框实现了该行为。
 * 本组件走 BasicTextField 新版 state 式 API (TextFieldState), 相比 legacy TextFieldValue
 * 路径 (TextFieldSelectionManager):
 * - 工具栏由 derivedStateOf 单值观察器驱动, 无 legacy 的 show→hide→show 抖动
 *   (移动端点按别处消失不再闪烁)
 * - 长按选词后拖选越界 / 拖手柄越出可视区自动滚动 (TextFieldSelectionState layout 阶段滚动)
 * - 触摸拖拽直接滚动内容; 选择后弹 ComposeTextToolbar (AppTheme 注入的 LocalTextToolbar)
 * - readOnly 字段点按不弹键盘; 桌面/鼠标输入按框架设计不弹浮动工具栏 (复制走 Ctrl+C)
 * - 测量语义与 `verticalScroll` 一致 (字段高 = min(内容高, 约束高)):
 *   内容短时收缩贴合内容, 内容超长时封顶并在内部滚动, 可直接替换现有自适应对话框布局
 *
 * 富文本 (HTML 渲染 / 语法高亮) 走 [AnnotatedString] 重载: TextFieldState 构造只收 String,
 * span 样式经 `state.edit + replace(CharSequence)` 注入 (TextFieldCharSequence 原样保留
 * AnnotatedString, 布局层直接传给 TextLayoutInput 渲染, 颜色/字体 span 正常生效)。
 */
@Composable
fun SelectableText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null,
) {
    // 静态只读展示: 字段内容只在 text 变化时写入, 状态常驻 (滚动/选区在重组间保留)
    val state = remember { TextFieldState() }
    LaunchedEffect(state, text) {
        state.edit { replace(0, length, text) }
    }
    BasicTextField(
        state = state,
        readOnly = true,
        textStyle = TextStyle(
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamily = fontFamily,
        ),
        modifier = modifier,
        // 只读展示不画光标 (readOnly 字段点按会获焦, 透明光标避免出现插入符)
        cursorBrush = SolidColor(Color.Unspecified),
    )
}

/** [String] 便捷重载: 纯文本直接传字符串。 */
@Composable
fun SelectableText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null,
) {
    SelectableText(
        text = AnnotatedString(text),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontFamily = fontFamily,
    )
}
