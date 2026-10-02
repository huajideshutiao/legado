package io.legado.app.ui.compose.component.code

import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.root.PlatformCapabilityProviders
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.copied_to_clipboard
import legado.ui.generated.resources.copy_css_selector
import legado.ui.generated.resources.copy_inner_html
import legado.ui.generated.resources.copy_outer_html
import legado.ui.generated.resources.copy_text
import legado.ui.generated.resources.view_value
import org.jetbrains.compose.resources.stringResource

/**
 * HTML 树条目菜单内容: 查看 / 复制 outerHTML / 复制 innerHTML / 复制文本 / 复制 CSS 选择器,
 * 长按与右键共用。形态对齐 [JsonPathMenu]。
 *
 * 仅元素节点有子树与选择器; 文本节点给"复制文本"; 注释/Doctype 只给"复制 outerHTML"。
 */
@Composable
fun HtmlNodeMenu(
    item: HtmlNode,
    onDismiss: () -> Unit,
    onViewElement: (HtmlNode.Element) -> Unit,
) {
    val copiedText = stringResource(Res.string.copied_to_clipboard)

    fun copy(text: String) {
        PlatformCapabilityProviders.get().copyToClipboard(text)
        Toasters.get().toast(copiedText)
        onDismiss()
    }

    val element = item as? HtmlNode.Element

    // "查看" = 聚焦子树, void/空/内联元素无子行可看 (DevTools 对 void 无下钻)
    DropdownMenuItem(
        enabled = element?.isCollapsible == true,
        onClick = {
            element?.let(onViewElement)
            onDismiss()
        },
    ) {
        Text(stringResource(Res.string.view_value))
    }
    DropdownMenuItem(
        enabled = item !is HtmlNode.Text,
        onClick = {
            copy(
                when (item) {
                    is HtmlNode.Element -> item.ksoupElement.outerHtml()
                    is HtmlNode.Comment -> "<!--${item.full}-->"
                    is HtmlNode.Doctype -> item.text
                    is HtmlNode.EndTag -> "</${item.element.tagName}>"
                    is HtmlNode.Text -> item.fullText
                },
            )
        },
    ) {
        Text(stringResource(Res.string.copy_outer_html))
    }
    DropdownMenuItem(
        // 内联元素 (<p>text</p>) 的 children 在建树时被清空, 判据改用 ksoup 真实子节点数,
        // 否则这类元素明明有 innerHTML 却被置灰
        enabled = element != null && !element.isVoid && element.ksoupElement.childNodeSize() > 0,
        onClick = {
            element?.let { copy(it.ksoupElement.html()) }
        },
    ) {
        Text(stringResource(Res.string.copy_inner_html))
    }
    DropdownMenuItem(
        enabled = when (item) {
            is HtmlNode.Element -> item.ksoupElement.text().isNotEmpty()
            is HtmlNode.Text -> item.fullText.isNotEmpty()
            else -> false
        },
        onClick = {
            copy(
                when (item) {
                    is HtmlNode.Element -> item.ksoupElement.text()
                    is HtmlNode.Text -> item.fullText
                    else -> ""
                },
            )
        },
    ) {
        Text(stringResource(Res.string.copy_text))
    }
    DropdownMenuItem(
        enabled = element != null,
        onClick = {
            element?.let { copy(it.cssSelector()) }
        },
    ) {
        Text(stringResource(Res.string.copy_css_selector))
    }
}
