package io.legado.app.utils

import io.legado.app.help.config.AppConfig

/**
 * 是否启用自定义导出
 *
 * @author Discut
 */
fun enableCustomExport(): Boolean {
    return AppConfig.enableCustomExport && AppConfig.exportType == 1
}

// verificationField + regexEpisode (纯字符串正则验证) 已下沉到
// shared/commonMain (CustomExportUtils.shared.kt), 跨模块同包名同签名扩展
// 自动合并, 消费方 import `io.legado.app.utils.verificationField` 零改动。