package io.legado.app.utils

import io.legado.app.constant.AppLog
import io.legado.app.model.analyzeRule.CustomUrl

/** 合法文件后缀: 纯 ASCII 字母数字 (与 `UrlUtil.getSuffix` 原判据逐字一致)。 */
private val fileSuffixRegex = Regex("^[a-z\\d]+$", RegexOption.IGNORE_CASE)

/**
 * 取 URL 中合法的文件后缀 (不含点), 即 jvmAndAndroidMain `UrlUtil.getSuffix` 的纯逻辑部分。
 *
 * 先按 [CustomUrl] 去掉规则尾部的 `,{...}` 请求规格段, 再取路径最后一段 `.` 后的部分
 * (排除 query/fragment); 后缀非纯字母数字或长度超过 5 视为不合法。
 *
 * 不合法或缺失时返回 [default]; [default] 为 null 时返回 "ext" 并记日志
 * (调用方传默认值时由调用方自己决定兜底, 不记日志)。
 *
 * 放在 data/commonMain 而非常用的 foundation/commonMain: 本逻辑依赖 [CustomUrl] (data 模块),
 * foundation 不依赖 data。
 */
fun getUrlSuffix(str: String, default: String? = null): String {
    val suffix = CustomUrl(str).getUrl()
        .substringAfterLast("/")
        .substringBefore("?")
        .substringBefore("#")
        .substringAfterLast(".", "")
    return if (suffix.length > 5 || !suffix.matches(fileSuffixRegex)) {
        if (default == null) {
            AppLog.put("Cannot find legal suffix:\n target: $str\n suffix: $suffix")
        }
        default ?: "ext"
    } else {
        suffix
    }
}
