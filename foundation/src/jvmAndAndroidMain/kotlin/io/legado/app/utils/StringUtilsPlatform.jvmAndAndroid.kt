package io.legado.app.utils

import java.io.ByteArrayOutputStream
import java.text.DecimalFormat
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64

/**
 * StringUtils 平台相关 actual (jvmAndAndroid)。
 *
 * 详见 commonMain/utils/StringUtilsPlatform.kt expect 注释。
 */
internal actual fun createWordCountFormatter(): (Double) -> String {
    val df = DecimalFormat("#.#")
    return { value -> df.format(value) }
}

internal actual fun gzipAndBase64Encode(str: String): String {
    val out = ByteArrayOutputStream()
    var gzip: GZIPOutputStream? = null
    return try {
        gzip = GZIPOutputStream(out)
        gzip.write(str.toByteArray())
        // kotlin.io.encoding.Base64.Default = 标准字母表无换行, 语义同原 android NO_WRAP
        Base64.Default.encode(out.toByteArray())
    } finally {
        gzip?.runCatching {
            close()
        }
        out.runCatching {
            close()
        }
    }
}
