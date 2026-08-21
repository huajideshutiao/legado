package io.legado.app.ui.compose.component

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/**
 * 把 String 形态的受控字段桥到 [TextFieldState]: 用户编辑回推 [onValueChange],
 * 外部 [value] 变化写回 state (覆盖清除按钮等程序化修改)。
 *
 * state 必须用初始值构造: 用空串构造会让 snapshotFlow 首帧把空串推给 [onValueChange],
 * 清空调用方的初始值。
 */
@Composable
fun rememberSyncedTextFieldState(
    value: String,
    onValueChange: (String) -> Unit,
): TextFieldState {
    val state = remember { TextFieldState(value) }
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .collect { if (it != currentValue) currentOnValueChange(it) }
    }
    LaunchedEffect(state, value) {
        if (state.text.toString() != value) {
            state.edit { replace(0, length, value) }
        }
    }
    return state
}
