package io.legado.app.help.book

import io.legado.app.utils.ChineseUtils

/**
 * BookDisplayBridge actual (iOS / 鸿蒙)。
 */
actual fun chineseT2S(content: String): String = ChineseUtils.t2s(content)

actual fun chineseS2T(content: String): String = ChineseUtils.s2t(content)
