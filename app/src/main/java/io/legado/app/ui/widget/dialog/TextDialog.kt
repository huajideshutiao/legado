package io.legado.app.ui.widget.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.IntentData
import io.legado.app.lib.theme.space
import io.legado.app.ui.compose.MarkdownContentSelectable
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme


class TextDialog() : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    enum class Mode {
        MD, HTML, TEXT
    }

    constructor(
        title: String,
        content: String?,
        mode: Mode = Mode.TEXT
    ) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putString("content", IntentData.put(content))
            putString("mode", mode.name)
        }
        isCancelable = false
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        val content = IntentData.get(arguments?.getString("content")) ?: ""
        val mode = arguments?.getString("mode")
        Column(Modifier.fillMaxSize()) {
            DialogTitleBar(
                title = arguments?.getString("title") ?: "",
                onBack = { dismissAllowingStateLoss() },
            )
            when (mode) {
                Mode.MD.name -> MarkdownContentSelectable(
                    content = content,
                    // LazyMarkdown 自带 LazyColumn 滚动, 外层不再套 verticalScroll
                    // (嵌套滚动会让 LazyColumn 在无限高约束下失去虚拟化)
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(colorsSpaceMd())
                )

                Mode.HTML.name -> SelectionContainer(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(colorsSpaceMd())
                ) {
                    Text(AnnotatedString.fromHtml(content), color = colors.secondaryText)
                }

                else -> SelectionContainer(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(colorsSpaceMd())
                ) {
                    val text = if (content.length >= 32 * 1024) {
                        content.take(32 * 1024) + "\n\n数据太大，无法全部显示…"
                    } else {
                        content
                    }
                    Text(text, color = colors.secondaryText)
                }
            }
        }
        LaunchedEffect(Unit) {
            view?.post { dialog?.setCancelable(true) }
        }
    }

    /** space.md 是像素值，转 dp 供 Compose padding 使用。 */
    @Composable
    private fun colorsSpaceMd() = with(androidx.compose.ui.platform.LocalDensity.current) {
        requireContext().space.md.toDp()
    }

}
