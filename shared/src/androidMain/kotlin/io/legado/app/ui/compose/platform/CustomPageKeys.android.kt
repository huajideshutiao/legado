package io.legado.app.ui.compose.platform

import androidx.compose.ui.input.key.Key

/** Android: Compose Key 与 android keyCode 恒等, 表外厂商键码 (蓝牙翻页器) 直接构造 */
internal actual fun identityKey(code: Int): Key? =
    if (code > 0) Key(code.toLong()) else null

internal actual fun identityCode(key: Key): Int? {
    val code = key.keyCode
    return if (code in 1..Int.MAX_VALUE.toLong()) code.toInt() else null
}
