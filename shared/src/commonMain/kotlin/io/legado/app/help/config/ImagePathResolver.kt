package io.legado.app.help.config

import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.file.AppFilesDirs

/**
 * 图集相对引用 → 绝对路径。
 *
 * # 约定 (2026 图集化拍板)
 * 设置点 (主题背景图/启动图/阅读背景图) 一律存**图集相对引用**: 裸文件名
 * （如 `<字节数>.webp`，不含目录前缀，文件落 `{externalFiles|files}/customImg`
 * 图集目录；目录前缀不进入引用，目录整体随备份打包），读取端经本函数解析为绝对路径 ——
 * 跨机/跨端恢复备份时相对引用自动有效，无需按旧绝对路径重写/迁移文件。
 *
 * 解析规则:
 * - 裸文件名 (无分隔符) → `{externalFiles|files}/customImg/<name>` (与阅读背景 novelBg 的
 *   「裸名→图集子目录」拼接规则一致, 见 [io.legado.app.help.config.ReadBookConfigShared])
 * - 图集内部相对路径 (如 `covers/<name>`) → `{externalFiles|files}/customImg/<covers>/<name>`,
 *   首段为图集子目录自动补 customImg/ 根 (引用不带目录前缀, 目录整体随备份打包)
 * - 旧数据绝对路径兼容: 以分隔符开头（unix `/`、win `\`）或含盘符（`C:`）视为绝对路径原样返回
 *   与原版 pref/字段的绝对路径格式兼容
 * - 带 scheme 的值（`http(s)://`、`file://`、`content://`）原样返回 —— 封面等键里
 *   网络地址与图集相对引用混存，本函数必须对前者透明
 *
 * 相对引用按平台分隔符逐段拼接（不保留引用里的 `/`），保证结果与
 * `listFiles` 给的 absolutePath 可直接字符串比较（Windows 上混用 `/` 与 `\` 比不相等）。
 */
fun resolveImagePath(ref: String?): String? {
    if (ref.isNullOrBlank()) return null
    if (ref.startsWith('/') || ref.startsWith('\\') || (ref.length > 1 && ref[1] == ':')) {
        return ref
    }
    if (ref.contains("://")) return ref
    val base = AppFilesDirs.get().externalFilesDir ?: AppFilesDirs.get().filesDir
    val segments = ref.split('/', '\\').filter { it.isNotEmpty() }
    return when {
        segments.isEmpty() -> null
        // 裸文件名: 图集根目录 customImg 下
        segments.size == 1 -> FileUtilsCommon.getPath(base, "customImg", segments[0])
        // 图集内部相对路径 (covers/... 等): 首段是图集子目录, 自动补 customImg 根
        else -> FileUtilsCommon.getPath(base, "customImg", *segments.toTypedArray())
    }
}
