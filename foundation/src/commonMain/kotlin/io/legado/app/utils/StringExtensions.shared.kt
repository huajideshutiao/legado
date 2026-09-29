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

/**
 * 取首尾非空白字符, 不分配整串副本 (对应判定只需要首尾两个字符, 不必 trim 出中间串)。
 * 全空白或无字符时返回 null。
 */
private fun String.firstLastNonWhitespace(): Pair<Char, Char>? {
    var start = 0
    var end = length - 1
    while (start <= end && this[start].isWhitespace()) start++
    while (end >= start && this[end].isWhitespace()) end--
    return if (start > end) null else this[start] to this[end]
}

fun String?.isJson(): Boolean =
    this?.firstLastNonWhitespace()?.let { (first, last) ->
        (first == '{' && last == '}') || (first == '[' && last == ']')
    } ?: false

fun String?.isXml(): Boolean =
    this?.firstLastNonWhitespace()?.let { (first, last) -> first == '<' && last == '>' } ?: false

fun String.splitNotBlank(vararg delimiter: String, limit: Int = 0): Array<String> = run {
    this.split(*delimiter, limit = limit).map { it.trim() }.filterNot { it.isBlank() }
        .toTypedArray()
}

fun String.splitNotBlank(regex: Regex, limit: Int = 0): Array<String> = run {
    this.split(regex, limit).map { it.trim() }.filterNot { it.isBlank() }.toTypedArray()
}

fun String?.safeTrim() = if (this.isNullOrBlank()) null else this.trim()

fun String?.isContentScheme(): Boolean = this?.startsWith("content://") == true

/**
 * 已是“可直接交给播放器的地址”的判据: 带 `http/https/file/content` 四种 scheme 之一。
 *
 * 两处必用: 视频装载链区分“直链”与“内存 m3u8 文本” ([io.legado.app.ui.book.video]),
 * 以及各端渲染层决定“走普通媒体项”还是“走清单数据源”。上一版各处只判 `startsWith("http")`,
 * 导致本地视频文件 (`file://`) 与 `content://` 被当成 m3u8 清单文本处理而永远播不出。
 */
fun String.hasPlayableScheme(): Boolean =
    startsWith("http://", true) || startsWith("https://", true) ||
            startsWith("file://", true) || isContentScheme()

fun String?.isFilePath(): Boolean = this?.startsWith("/storage") == true

fun String?.isAbsUrl() =
    this?.let {
        it.startsWith("http://", true) || it.startsWith("https://", true)
    } ?: false

fun String?.isJsonObject(): Boolean =
    this?.firstLastNonWhitespace()?.let { (first, last) -> first == '{' && last == '}' } ?: false

fun String?.isJsonArray(): Boolean =
    this?.firstLastNonWhitespace()?.let { (first, last) -> first == '[' && last == ']' } ?: false

// 词表均 ASCII: regionMatches 逐字符大小写折叠与 (?i) 正则对 ASCII 的判定一致
private val falseValues = arrayOf("false", "no", "not", "0")

/**
 * 忽略首尾 Unicode 空白后与 [other] 忽略大小写比较, 全程零分配。
 */
private fun String.contentEqualsTrimmedIgnoreCase(other: String): Boolean {
    var start = 0
    var end = length
    while (start < end && this[start].isWhitespace()) start++
    while (end > start && this[end - 1].isWhitespace()) end--
    return end - start == other.length &&
        regionMatches(start, other, 0, other.length, ignoreCase = true)
}

fun String?.isTrue(nullIsTrue: Boolean = false): Boolean {
    if (this.isNullOrBlank() || this == "null") {
        return nullIsTrue
    }
    for (word in falseValues) {
        if (contentEqualsTrimmedIgnoreCase(word)) return false
    }
    return true
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
