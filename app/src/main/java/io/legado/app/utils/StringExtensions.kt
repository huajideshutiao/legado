package io.legado.app.utils

import android.net.Uri
import androidx.core.net.toUri
import java.io.File

/**
 * String 扩展的安卓绑定面。纯 JVM 扩展 (isAbsUrl/isJsonObject/isJsonArray/isTrue/isHex/
 * pureKindText/memorySize/isChinese/toStringArray/escapeRegex/encodeURI/normalizeFileName/
 * safeTrim/isContentScheme/isFilePath) 已下沉 shared jvmAndAndroidMain
 * (见 modules/shared/src/jvmAndAndroidMain/kotlin/io/legado/app/utils/StringExtensions.shared.kt),
 * 跨模块同包名同签名扩展自动合并, 消费方 import 零改动。
 *
 * cnCompare 已下沉 shared commonMain (StringCnCompare.kt, expect/actual 分派 ICU),
 * 本文件仅保留安卓绑定方法 (parseToUri/isUri)。
 */

fun String.parseToUri(): Uri {
    return if (isUri()) this.toUri() else {
        Uri.fromFile(File(this))
    }
}

fun String?.isUri(): Boolean {
    this ?: return false
    return this.startsWith("file://", true) || isContentScheme()
}
