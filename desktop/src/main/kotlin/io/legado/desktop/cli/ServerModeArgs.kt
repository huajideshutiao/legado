package io.legado.desktop.cli

import java.io.File

/**
 * 纯命令行模式参数解析 (桌面端与 GUI 共用同一个产物与主类)。
 *
 * 模式由 argv 表达而不是 `-D` 系统属性: 重启链 ([io.legado.desktop.help.launchRestartProcess])
 * 复现的只有主类名 + 启动参数, JVM 参数不在其中 —— 用系统属性承载模式会在切换语言、
 * 正则回溯失控等重启场景下静默落回 GUI。
 */
const val SERVER_MODE_FLAG = "--server"

/** 数据目录覆盖参数 (仅命令行模式); GUI 的数据根由便携标记 / 安装类型决定。 */
private const val DATA_DIR_ARG_PREFIX = "--data-dir="

/** 启动参数是否要求纯命令行模式。 */
fun isServerMode(args: Array<String>): Boolean = args.any { it == SERVER_MODE_FLAG }

/**
 * 命令行模式的显式数据目录: `--data-dir=<path>` 优先, 其次 `-Dlegado.data.dir`。
 *
 * 返回 null 表示不覆盖 —— 交由 [io.legado.desktop.DesktopCore.initRuntimeEnvironment] 按
 * 便携标记 (`portable.txt`) 与安装类型解析, 与桌面 GUI 走同一条路径。
 */
fun resolveExplicitDataDir(args: Array<String>): File? {
    args.firstOrNull { it.startsWith(DATA_DIR_ARG_PREFIX) }
        ?.substringAfter(DATA_DIR_ARG_PREFIX)
        ?.takeIf { it.isNotBlank() }
        ?.let { return File(it) }
    return System.getProperty("legado.data.dir")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
}
