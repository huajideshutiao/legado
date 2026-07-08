package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.view.View
import android.view.textclassifier.TextClassifier
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogTextViewBinding
import io.legado.app.help.IntentData
import io.legado.app.utils.setHtml
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class TextDialog() : BaseDialogFragment(R.layout.dialog_text_view) {

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

    private val binding by viewBinding(DialogTextViewBinding::bind)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setupTitleBar(
            title = arguments?.getString("title"),
            menuRes = R.menu.dialog_text
        ) {
            when (it?.itemId) {
                R.id.menu_close -> dismissAllowingStateLoss()
            }
            true
        }
        arguments?.let {
            val content = IntentData.get(it.getString("content")) ?: ""
            when (it.getString("mode")) {
                Mode.MD.name -> viewLifecycleOwner.lifecycleScope.launch {
                    binding.textView.setTextClassifier(TextClassifier.NO_OP)
                    val markwon: Markwon
                    val markdown = withContext(IO) {
                        markwon = Markwon.builder(requireContext())
                            .usePlugin(GlideImagesPlugin.create(requireContext()))
                            .usePlugin(HtmlPlugin.create())
                            .usePlugin(TablePlugin.create(requireContext()))
                            .build()
                        markwon.toMarkdown(content)
                    }
                    markwon.setParsedMarkdown(binding.textView, markdown)
                }

                Mode.HTML.name -> binding.textView.setHtml(content)
                else -> {
                    if (content.length >= 32 * 1024) {
                        val truncatedContent =
                            content.take(32 * 1024) + "\n\n数据太大，无法全部显示…"
                        binding.textView.text = truncatedContent
                    } else {
                        binding.textView.text = content
                    }
                }
            }
        }
        view.post {
            dialog?.setCancelable(true)
        }
    }

}
