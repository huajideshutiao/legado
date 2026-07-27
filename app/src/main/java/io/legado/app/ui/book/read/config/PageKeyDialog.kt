package io.legado.app.ui.book.read.config

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.utils.hideSoftInput

/** 自定义翻页按键：物理键按下时把 keyCode 追加进聚焦的输入框 */
class PageKeyDialog : BaseComposeDialogFragment() {

    private var prev by mutableStateOf("")
    private var next by mutableStateOf("")

    /** 0 无聚焦 1 上一页框 2 下一页框 */
    private val focusedField = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prev = AppConfig.prevKeys.orEmpty()
        next = AppConfig.nextKeys.orEmpty()
    }

    override fun onStart() {
        super.onStart()
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DEL) {
                return@setOnKeyListener false
            }
            when (focusedField.intValue) {
                1 -> {
                    prev = appendKeyCode(prev, keyCode)
                    true
                }

                2 -> {
                    next = appendKeyCode(next, keyCode)
                    true
                }

                else -> false
            }
        }
    }

    private fun appendKeyCode(text: String, keyCode: Int): String {
        return if (text.isEmpty() || text.endsWith(",")) {
            text + keyCode
        } else {
            "$text,$keyCode"
        }
    }

    @Composable
    override fun Content() {
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = stringResource(R.string.custom_page_key),
                onBack = { dismissAllowingStateLoss() },
            )
            AppOutlinedTextField(
                value = prev,
                onValueChange = { prev = it },
                label = stringResource(R.string.prev_page_key),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .onFocusChanged {
                        if (it.isFocused) focusedField.intValue = 1
                        else if (focusedField.intValue == 1) focusedField.intValue = 0
                    },
            )
            AppOutlinedTextField(
                value = next,
                onValueChange = { next = it },
                label = stringResource(R.string.next_page_key),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .onFocusChanged {
                        if (it.isFocused) focusedField.intValue = 2
                        else if (focusedField.intValue == 2) focusedField.intValue = 0
                    },
            )
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                Spacer(Modifier.weight(1f))
                AppTextButton(stringResource(R.string.reset)) {
                    prev = ""
                    next = ""
                }
                AppTextButton(stringResource(R.string.ok)) {
                    AppConfig.prevKeys = prev
                    AppConfig.nextKeys = next
                    dismiss()
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (dialog as? Dialog)?.currentFocus?.hideSoftInput()
    }
}
