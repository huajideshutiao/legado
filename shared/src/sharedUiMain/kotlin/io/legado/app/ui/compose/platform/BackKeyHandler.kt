package io.legado.app.ui.compose.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 桌面端无系统返回键, ESC/Backspace 等价于返回按钮
 */
fun Modifier.handleBackKey(onBack: () -> Unit): Modifier =
    this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Escape, Key.Backspace -> {
                onBack()
                true
            }
            else -> false
        }
    }
