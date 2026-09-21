package io.legado.app.utils

/*
 * 自定义导出纯逻辑下沉 (shared/commonMain)。
 *
 * `verificationField` + `regexEpisode` 为纯字符串正则验证, 无 Android 依赖,
 * 下沉至此供 app/desktop/iOS/鸿蒙 各端复用。
 *
 * app 端 `enableCustomExport()` 依赖 AppConfig (SharedPreferences), 留 app 端
 * (见 app/.../utils/CustomExportUtils.kt)。
 *
 * 跨模块同包名同签名 top-level fun 自动合并, 消费方 import
 * `io.legado.app.utils.verificationField` 零改动。
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
