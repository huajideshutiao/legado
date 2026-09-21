package io.legado.app.ui.compose.platform

import androidx.compose.ui.input.key.Key

/** 桌面: Compose Key 为 AWT VK 码/合成负数, 与 android keyCode 无恒等关系, 只认具名映射表 */
internal actual fun identityKey(code: Int): Key? = null

internal actual fun identityCode(key: Key): Int? = null
