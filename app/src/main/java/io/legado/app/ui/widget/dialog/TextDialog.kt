package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.IntentData
import io.legado.app.lib.theme.space
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


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
                // MD 模式内容是 AndroidView(NestedScrollView) 互操作层，独立合成；抬高顶栏 z 序，
                // 避免滑动/过度滚动时该原生层重绘越过互操作边界盖住不透明顶栏（表现为顶栏“变透明”）。
                modifier = Modifier.zIndex(1f)
            )
            when (mode) {
                // Markdown 仍走 Markwon(表格/内联 HTML/Glide 图片)，Compose 端无等价能力，登记为遗留债务
                Mode.MD.name -> AndroidView(
                    factory = { ctx ->
                        val pad = ctx.space.md
                        val textView = TextView(ctx).apply {
                            setTextIsSelectable(true)
                            setPadding(pad, pad, pad, pad)
                        }
                        applyMarkdown(textView, content)
                        NestedScrollView(ctx).apply {
                            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                            isVerticalScrollBarEnabled = true
                            addView(
                                textView,
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                            )
                        }
                    },
                    update = { (it.getChildAt(0) as TextView).setTextColor(colors.secondaryText.toArgb()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
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

    private fun applyMarkdown(textView: TextView, content: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            textView.setTextClassifier(TextClassifier.NO_OP)
            val markwon: Markwon
            val markdown = withContext(IO) {
                markwon = Markwon.builder(requireContext())
                    .usePlugin(GlideImagesPlugin.create(requireContext()))
                    .usePlugin(HtmlPlugin.create())
                    .usePlugin(TablePlugin.create(requireContext()))
                    .build()
                markwon.toMarkdown(content)
            }
            markwon.setParsedMarkdown(textView, markdown)
        }
    }

}
