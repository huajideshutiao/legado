package io.legado.app.ui.about

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.update.AppUpdateShared
import io.legado.app.lib.theme.space
import io.legado.app.model.Download
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.toastOnUi
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 更新弹窗（迁 dialog_text_view → Compose）。
 * Markdown 正文沿用 TextDialog Mode.MD 同款 Markwon+AndroidView 渲染路径（附录 D 登记债）。
 */
class UpdateDialog() : BaseComposeDialogFragment() {


    constructor(updateInfo: AppUpdateShared.UpdateInfo) : this() {
        arguments = Bundle().apply {
            putString("newVersion", updateInfo.tagName)
            putString("updateBody", updateInfo.updateLog)
            putString("url", updateInfo.downloadUrl)
            putString("name", updateInfo.fileName)
        }
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        val updateBody = arguments?.getString("updateBody")
        if (updateBody == null) {
            LaunchedEffect(Unit) {
                toastOnUi("没有数据")
                dismissAllowingStateLoss()
            }
            return
        }
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = arguments?.getString("newVersion") ?: "",
                onBack = { dismissAllowingStateLoss() },
                actions = {
                    AppTextButton(text = stringResource(R.string.action_download)) {
                        val url = arguments?.getString("url")
                        val name = arguments?.getString("name")
                        if (url != null && name != null) {
                            Download.start(url, name)
                            toastOnUi(R.string.download_start)
                        }
                    }
                },
            )
            AndroidView(
                factory = { ctx ->
                    val pad = ctx.space.md
                    val textView = TextView(ctx).apply {
                        setTextIsSelectable(true)
                        setTextClassifier(TextClassifier.NO_OP)
                        setPadding(pad, pad, pad, pad)
                    }
                    applyMarkdown(textView, updateBody)
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
                    .weight(1f, fill = false)
            )
        }
    }

    private fun applyMarkdown(textView: TextView, content: String) {
        viewLifecycleOwner.lifecycleScope.launch {
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
