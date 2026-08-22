package io.legado.app.ui.compose.platform

import androidx.compose.ui.input.key.Key

/** iOS: Compose Key 为本平台键值, 与 android keyCode 无恒等关系, 只认具名映射表 */
internal actual fun identityKey(code: Int): Key? = null

internal actual fun identityCode(key: Key): Int? = null
