package io.legado.app.ui.widget.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme

@Suppress("unused")
class TextListDialog() : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    constructor(title: String, values: ArrayList<String>) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putStringArrayList("values", values)
        }
    }

    @Composable
    override fun Content() {
        val values = arguments?.getStringArrayList("values") ?: arrayListOf()
        val colors = AppTheme.colors
        Column(Modifier.fillMaxSize()) {
            DialogTitleBar(
                title = arguments?.getString("title") ?: "",
                onBack = { dismissAllowingStateLoss() }
            )
            // 复刻原 TextAdapter：可选中文本 + WEB_URLS 自动链接
            SelectionContainer(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(values) { item ->
                        Text(
                            text = autoLinkText(item, colors.accent),
                            color = colors.primaryText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 复刻 Linkify.WEB_URLS：把纯文本中的网址片段标注为链接样式。
 *
 * 仅保留视觉样式（颜色 + 下划线），不使用 withLink/LinkAnnotation。
 * 在 SelectionContainer 中可点击链接会拦截长按手势导致无法选中文本，
 * 去掉点击行为以保证链接区域同样可长按选择。
 */
internal fun autoLinkText(text: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        val matcher = android.util.Patterns.WEB_URL.matcher(text)
        var last = 0
        while (matcher.find()) {
            append(text.substring(last, matcher.start()))
            val url = matcher.group()
            // 仅保留链接视觉样式，不注册点击以避免拦截 SelectionContainer 长按选择
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                append(url)
            }
            last = matcher.end()
        }
        append(text.substring(last))
    }
