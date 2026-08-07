package io.legado.app.ui.dict

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.viewModels
import io.legado.app.help.i18n.androidAppString
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.utils.toastOnUi

/**
 * 词典
 */
class DictDialog() : BaseComposeDialogFragment() {

    constructor(word: String) : this() {
        arguments = Bundle().apply {
            putString("word", word)
        }
    }

    private val viewModel by viewModels<DictViewModel>()

    @Composable
    override fun Content() {
        val word = arguments?.getString("word")
        if (word.isNullOrEmpty()) {
            toastOnUi(androidAppString("cannot_empty"))
            dismiss()
            return
        }
        // UI 下沉到 shared/sharedUiMain 的 DictDialogContent, 注入 Android ViewModel 的 shared
        // (viewModelScope 已由 DictViewModel 内部注入 shared, 这里仅透传)
        DictDialogContent(
            word = word,
            viewModel = viewModel.shared,
            modifier = Modifier,
        )
    }
}
