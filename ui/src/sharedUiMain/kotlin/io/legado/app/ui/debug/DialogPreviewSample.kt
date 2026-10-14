package io.legado.app.ui.debug

import io.legado.app.constant.AppLog

/**
 * 读一条预览样本数据: 失败写日志并返回 null (调用方自行给空态)。
 *
 * 失败的样本各自降级, 不让整页清单构建失败 —— 样本取自真实库与随包资源, 缺样本时对应弹窗只能
 * 显示空态, 原因看日志。
 */
inline fun <T> previewRead(what: String, block: () -> T): T? =
    runCatching(block)
        .onFailure { AppLog.put("弹窗预览读取失败: $what", it) }
        .getOrNull()
