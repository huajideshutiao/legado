package io.legado.app.help.config

const val MIN_FONT_SCALE = 0.8f
const val MAX_FONT_SCALE = 1.6f
private const val FONT_SCALE_LEVEL_DIVISOR = 10f

/** 将偏好中的整数档位换算为有效字体倍率；无效档位不决定平台回退策略。 */
fun fontScaleFromLevel(level: Int): Float? =
    (level / FONT_SCALE_LEVEL_DIVISOR).takeIf { it in MIN_FONT_SCALE..MAX_FONT_SCALE }
