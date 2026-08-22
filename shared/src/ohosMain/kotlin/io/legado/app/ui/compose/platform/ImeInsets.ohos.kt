package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity

/**
 * [rememberImeVisible] 的鸿蒙 actual: 读 CPF 的 ime inset 底边是否 > 0。
 *
 * 逐帧数值读取关在 snapshotFlow 的独立观察域内, 只有布尔翻转才写回 state ——
 * 调用方不会在键盘动画期间每帧重组, 与 Android/iOS actual 的事件语义一致。
 */
@Composable
actual fun rememberImeVisible(): Boolean {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    var imeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(ime, density) {
        snapshotFlow { ime.getBottom(density) > 0 }.collect { imeVisible = it }
    }
    return imeVisible
}
