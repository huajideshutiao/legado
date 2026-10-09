package io.legado.app.utils

/*
 * 自定义导出纯逻辑下沉 (foundation/commonMain)。
 *
 * `verificationField` + `regexEpisode` 为纯字符串正则验证, 无平台依赖,
 * 下沉至此供 app/desktop/iOS/鸿蒙 各端复用。
 *
 * 同包下的 `enableCustomExport()` 要读偏好键, 下沉在 :data
 * (data/.../utils/CustomExportUtils.kt)。
 */

// 匹配待"输入的章节"字符串
private val regexEpisode = Regex("\\d+(-\\d+)?(,\\d+(-\\d+)?)*")

/**
 * 验证 输入的范围 是否正确
 *
 * @since 1.0.0
 * @author Discut
 * @param text 输入的范围 字符串
 * @return 是否正确
 */
fun verificationField(text: String): Boolean {
    return text.matches(regexEpisode)
}
