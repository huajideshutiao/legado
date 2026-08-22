package io.legado.app.ui.compose.platform

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode

/**
 * Android: Compose Key 由 android keyCode 构造 (表外厂商键码, 如蓝牙翻页器)。
 * 必须走 `Key(nativeKeyCode)` 工厂而非 `Key(Long)` 构造器 —— 前者把键码打包进高 32 位
 * (packInts), 直接塞 Long 得到的 Key 与真实按键事件永不相等。
 */
internal actual fun identityKey(code: Int): Key? =
    if (code > 0) Key(nativeKeyCode = code) else null

internal actual fun identityCode(key: Key): Int? = key.nativeKeyCode.takeIf { it > 0 }
