package io.legado.app.utils

import io.legado.app.constant.AppLog
import java.io.File

/**
 * 桌面端 (JVM) 用系统默认程序打开文件: [java.awt.Desktop.open]。
 *
 * 对照 app 端 `Context.openFileUri` (ACTION_VIEW + FileProvider): 下载完成后交给系统处理,
 * 安装包由系统安装器接手, 其余类型由默认程序打开。
 * 不支持 OPEN 动作 / 打开失败时记 [AppLog] 返回 false, 由调用方决定是否再降级。
 */
fun openFileWithSystem(path: String): Boolean {
    val file = File(path)
    if (!file.isFile) {
        AppLog.put("打开文件失败 (文件不存在): $path")
        return false
    }
    return try {
        if (!java.awt.Desktop.isDesktopSupported()) {
            AppLog.put("桌面端 Desktop 不支持, 无法打开文件: $path")
            return false
        }
        val desktop = java.awt.Desktop.getDesktop()
        if (!desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
            AppLog.put("桌面端不支持 OPEN 动作, 无法打开文件: $path")
            return false
        }
        desktop.open(file)
        true
    } catch (e: Exception) {
        AppLog.put("打开文件失败: $path\n${e.localizedMessage}", e)
        false
    }
}
