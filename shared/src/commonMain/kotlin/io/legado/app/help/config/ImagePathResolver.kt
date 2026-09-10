package io.legado.app.help.config

import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.file.AppFilesDirs

/** 封面缓存相对引用首段 (`coverCache/<md5>.jpg`, 见 [resolveImagePath] 解析规则)。 */
const val COVER_CACHE_REF_SEGMENT = "coverCache"

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
 * - **封面缓存相对引用** (`coverCache/<name>`, [COVER_CACHE_REF_SEGMENT]): 正常书籍封面缓存
 *   (`FileBook.getCoverPath` 桌面端存储格式, 物理落盘平台 `coversDir`), **不入** customImg 图集
 *   (那是自定义封面 `covers/<字节数>.<ext>` 的保留段, 两者同为 Book 封面字段但目录语义不同);
 *   平台未注册 `coversDir` 时原样返回 (加载失败走占位, 与旧绝对路径跨端行为一致)
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
    val rawSegments = ref.split('/', '\\').filter { it.isNotEmpty() }
    if (rawSegments.isEmpty()) return null

    // 封面缓存引用: 对准平台封面缓存物理目录, 不经 customImg 图集
    if (rawSegments[0] == COVER_CACHE_REF_SEGMENT) {
        val subRaw = rawSegments.drop(1)
        if (subRaw.isEmpty()) return null
        val subSegments = mutableListOf<String>()
        for (seg in subRaw) {
            when (seg) {
                "." -> continue
                ".." -> {
                    if (subSegments.isEmpty()) return null
                    subSegments.removeAt(subSegments.size - 1)
                }

                else -> subSegments.add(seg)
            }
        }
        if (subSegments.isEmpty()) return null
        val coversDir = AppFilesDirs.get().coversDir ?: return ref
        return FileUtilsCommon.getPath(coversDir, *subSegments.toTypedArray())
    }

    val normalized = mutableListOf<String>()
    for (seg in rawSegments) {
        when (seg) {
            "." -> continue
            ".." -> {
                if (normalized.isEmpty()) return null
                normalized.removeAt(normalized.size - 1)
            }

            else -> normalized.add(seg)
        }
    }
    if (normalized.isEmpty()) return null

    val base = AppFilesDirs.get().externalFilesDir ?: AppFilesDirs.get().filesDir
    return when {
        // 裸文件名: 图集根目录 customImg 下
        normalized.size == 1 -> FileUtilsCommon.getPath(base, "customImg", normalized[0])
        // 图集内部相对路径 (covers/... 等): 首段是图集子目录, 自动补 customImg 根
        else -> FileUtilsCommon.getPath(base, "customImg", *normalized.toTypedArray())
    }
}
