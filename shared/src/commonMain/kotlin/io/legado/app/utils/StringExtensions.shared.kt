package io.legado.app.utils

import io.legado.app.constant.AppPattern

/*
 * StringExtensions 纯区：analyzeRule/webBook 消费的零平台依赖字符串判定与扩展。
 *
 * 安卓区（Uri/Editable/icu 等）仍留 app 侧同包 StringExtensions.kt，按包合并;
 * encodeURI 走 commonMain 端 PercentCodec.encode(str, toBytes) + String.toByteArray()
 * (默认 UTF_8, kotlin.text 标准 API, commonMain 可用), 不再依赖 jvmAndAndroidMain 半区。
 */

fun String?.isDataUrl() =
    this?.startsWith("data:") ?: false

fun String?.isJson(): Boolean =
    this?.run {
        val str = this.trim()
        when {
            str.startsWith("{") && str.endsWith("}") -> true
            str.startsWith("[") && str.endsWith("]") -> true
            else -> false
        }
    } ?: false

fun String?.isXml(): Boolean =
    this?.run {
        val str = this.trim()
        str.startsWith("<") && str.endsWith(">")
    } ?: false

fun String.splitNotBlank(vararg delimiter: String, limit: Int = 0): Array<String> = run {
    this.split(*delimiter, limit = limit).map { it.trim() }.filterNot { it.isBlank() }
        .toTypedArray()
}

fun String.splitNotBlank(regex: Regex, limit: Int = 0): Array<String> = run {
    this.split(regex, limit).map { it.trim() }.filterNot { it.isBlank() }.toTypedArray()
}

fun String?.safeTrim() = if (this.isNullOrBlank()) null else this.trim()

fun String?.isContentScheme(): Boolean = this?.startsWith("content://") == true

fun String?.isFilePath(): Boolean = this?.startsWith("/storage") == true

fun String?.isAbsUrl() =
    this?.let {
        it.startsWith("http://", true) || it.startsWith("https://", true)
    } ?: false

fun String?.isJsonObject(): Boolean =
    this?.run {
        val str = this.trim()
        str.startsWith("{") && str.endsWith("}")
    } ?: false

fun String?.isJsonArray(): Boolean =
    this?.run {
        val str = this.trim()
        str.startsWith("[") && str.endsWith("]")
    } ?: false

fun String?.isTrue(nullIsTrue: Boolean = false): Boolean {
    if (this.isNullOrBlank() || this == "null") {
        return nullIsTrue
    }
    return !this.trim().matches("(?i)^(false|no|not|0)$".toRegex())
}

fun String.isHex(): Boolean {
    return all {c ->
        c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'
    }
}

/**
 * 提取分类的纯文本：去掉 "::url" 后缀和 "group:" 前缀。
 * 例: "玄幻:都市::http://x" -> "都市", "玄幻" -> "玄幻", "100万字" -> "100万字"。
 */
fun String.pureKindText(): String {
    val tagContent = substringBefore("::").trim()
    val parts = tagContent.split(":", limit = 2)
    return if (parts.size > 1 && parts.all { it.isNotBlank() }) {
        parts[1].trim()
    } else {
        tagContent
    }
}

/**
 * 字符串所占内存大小
 */
fun String?.memorySize(): Int {
    this ?: return 0
    return 40 + 2 * length
}

/**
 * 是否中文
 */
fun String.isChinese(): Boolean {
    // Pattern.compile("[\u4e00-\u9fa5]").matcher(this).find() 与 Regex.find 行为一致
    val p = Regex("[\u4e00-\u9fa5]")
    val m = p.find(this)
    return m != null
}

fun String.escapeRegex(): String {
    return replace(AppPattern.regexCharRegex, "\\\\$0")
}

fun String.normalizeFileName(): String {
    return replace(AppPattern.fileNameRegex2, "_")
}

/**
 * 将字符串拆分为单个字符,包含emoji
 *
 * 原实现走 [java.lang.Character.codePointCount]/[offsetByCodePoints] (JVM 专属);
 * commonMain 端用纯 Kotlin 遍历代理对, 行为等价: 一个代理对 (高代理 + 低代理) 算一个码点。
 * 异常回退与原实现一致 (split("").toTypedArray())。
 *
 * 注: [Char.isHighSurrogate]/[Char.isLowSurrogate] 是 JVM 专属扩展,
 * commonMain 端用 code 区间判断 (高代理 D800..DBFF, 低代理 DC00..DFFF)。
 */
fun CharSequence.toStringArray(): Array<String> {
    return try {
        buildList<String> {
            var i = 0
            while (i < length) {
                val c = this@toStringArray[i]
                // 高代理 + 低代理 = 一个码点 (emoji 等), 否则单 Char 即一个码点
                // 高代理 D800..DBFF, 低代理 DC00..DFFF (Char.isHighSurrogate/isLowSurrogate 是 JVM 专属)
                val isHighSurrogate = c.code >= 0xD800 && c.code <= 0xDBFF
                val isSurrogatePair = isHighSurrogate
                    && i + 1 < length
                    && this@toStringArray[i + 1].code.let { it >= 0xDC00 && it <= 0xDFFF }
                val end = if (isSurrogatePair) i + 2 else i + 1
                add(substring(i, end))
                i = end
            }
        }.toTypedArray()
    } catch (e: Exception) {
        split("").toTypedArray()
    }
}

/**
 * URI 编码: 走 [PercentCodec.QUERY] (RFC3986 query), 字节化用 [String.encodeToByteArray]
 * (默认 UTF_8, kotlin.text 标准 API, commonMain 可用)。
 *
 * 与原 jvmAndAndroidMain 端 `PercentCodec.QUERY.encode(this, Charsets.UTF_8)` 行为一致
 * (后者 actual 内部亦委托 `it.toByteArray(charset)`, charset = UTF_8 时与默认参数等价)。
 */
fun String.encodeURI(): String = PercentCodec.QUERY.encode(this) { it.encodeToByteArray() }
