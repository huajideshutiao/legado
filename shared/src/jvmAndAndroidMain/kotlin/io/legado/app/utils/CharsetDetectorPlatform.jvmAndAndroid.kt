package io.legado.app.utils

import io.legado.app.lib.icu4j.CharsetDetector

/**
 * 字符集检测 actual (jvmAndAndroid)。
 *
 * 详见 commonMain/utils/CharsetDetectorPlatform.kt expect 注释。
 * 实现逻辑与原 jvmAndAndroidMain 端 EncodingDetect.getEncode(ByteArray) 一致, 行为不变。
 */
internal actual fun detectCharsetName(bytes: ByteArray): String? =
    CharsetDetector().setText(bytes).detect()?.name
