package io.legado.app.model

import io.legado.app.constant.PreferKey
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.resolveImagePath
import io.legado.app.help.file.AppFilesDirs

/** 源文件名主干 (去扩展名), 烘焙产物按它命名。 */
private fun pathStem(resolvedAbsPath: String): String {
    val name = resolvedAbsPath.substringAfterLast('/').substringAfterLast('\\')
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
}

/** 缓存根下子目录产物路径 (纯派生物, 随时可由源图重烘焙, 不进备份 zip)。 */
private fun bakedCachePath(cacheSubDir: String, fileName: String): String {
    val cacheBase = AppFilesDirs.get().externalCacheDir ?: AppFilesDirs.get().cacheDir
    return FileUtilsCommon.getPath(cacheBase, cacheSubDir, fileName)
}

/**
 * 清晰烘焙产物落盘路径: `{缓存根}/customImg/<源文件名主干>.webp` (与源图同名不同目录,
 * 扩展名固定 webp —— 产物就是平台编码的 webp 字节)。
 *
 * 主题背景图与启动图共用本机制: 原图进图集目录 `customImg/` (内容字节数特征值命名,
 * 启动图与背景图同规则, 均随备份 zip 打包, pref 存裸文件名引用),
 * 一次性按**本端屏幕尺寸**居中裁剪+缩放产出本产物 (缩放到不超出屏幕, 源图小于屏幕不放大);
 * 产物放缓存根的同名 customImg 子目录不进备份 zip; 缓存被系统清掉时渲染端从原图兜底解码/重烘焙。
 */
fun bakedImagePath(resolvedAbsPath: String): String =
    bakedCachePath("customImg", "${pathStem(resolvedAbsPath)}.webp")

/**
 * 模糊产物落盘路径: `{缓存根}/customImg/<源文件名>_blur.webp` (与清晰产物同目录,
 * `_blur` 后缀区分; 扩展名固定 webp, 产物就是 WEBP 编码, 跟着源图扩展名走会得到装着
 * webp 字节的 `.jpg`)。
 *
 * 放**缓存根**而非图集目录是有意的: 它是纯派生物 (随时可由源图重烘焙),
 * 不该进备份 zip。缓存被系统清掉时
 * [io.legado.app.ui.root.WallpaperLayer] 走运行期兜底模糊, 只损失一次 CPU。
 */
fun blurredImageVariantPath(resolvedAbsPath: String): String =
    bakedCachePath("customImg", "${pathStem(resolvedAbsPath)}_blur.webp")

/**
 * 图集文件/烘焙产物的安全删除: 同一张图可能被四个设置键 (启动封面 日/夜、界面背景 日/夜)
 * 复用引用 (内容特征值命名下同图共用极常见), 任一键仍解析到 [absPath] 就跳过删除,
 * 避免清一处时误伤其它引用 (另一处渲染端只剩兜底/成孤儿)。
 *
 * @param withFile true 连原图文件一并删 (换图/清除/残留清理);
 *                 false 只删烘焙产物 (提交换图时原图留给 clearBg 按白名单清)
 * @param excludeKey 调用方正在替换/清除的键 (删除发生在该键写新值之前, 不排除会被"仍引用"误拦)
 */
fun deleteImageIfUnreferenced(absPath: String, withFile: Boolean, excludeKey: String? = null) {
    val prefs = PreferenceProviders.get()
    val keys = listOf(
        PreferKey.welcomeImage, PreferKey.welcomeImageDark,
        PreferKey.bgImage, PreferKey.bgImageN,
    )
    val stillUsed = keys.any {
        it != excludeKey && resolveImagePath(prefs.getString(it)) == absPath
    }
    if (stillUsed) return
    if (withFile) FileUtilsCommon.delete(absPath, deleteRootDir = false)
    FileUtilsCommon.delete(bakedImagePath(absPath))
    FileUtilsCommon.delete(blurredImageVariantPath(absPath))
}

/**
 * 封面原图图集目录: `{文件根}/customImg/covers`。
 *
 * 手动封面与默认封面图集的原图都保留在此 (内容字节数特征值命名 `<size>.<ext>`, 同字节数
 * 即同内容复用), 随备份 zip 打包; 备份恢复后相对引用自动有效。
 */
fun coverOriginalDir(): String {
    val base = AppFilesDirs.get().externalFilesDir ?: AppFilesDirs.get().filesDir
    return FileUtilsCommon.getPath(base, "customImg", "covers")
}

/**
 * 封面烘焙产物缓存目录: `{缓存根}/customImg/covers`。
 *
 * 产物是派生物 (随时可由原图重烘焙), 不进备份 zip, 缓存可被系统清理;
 * 缺产物时渲染端直接回落原图 (封面加载高频, 不做现场重烘焙避免书架批量卡顿)。
 */
fun coverBakedCacheDir(): String {
    val base = AppFilesDirs.get().externalCacheDir ?: AppFilesDirs.get().cacheDir
    return FileUtilsCommon.getPath(base, "customImg", "covers")
}

/**
 * 封面展示路径: 普通图 = 缓存烘焙产物 `<id>_<ratio>.webp`;
 * .9 图 = 图集原图 `<id>.9.png` (九宫格标记框必须读原图, 不烘焙)。
 */
fun defaultCoverDisplayPath(
    entry: BookCoverShared.DefaultCoverEntry,
    ratio: BookCoverShared.CoverRatio,
): String =
    if (entry.ninePatch) {
        FileUtilsCommon.getPath(coverOriginalDir(), "${entry.id}.9.png")
    } else {
        FileUtilsCommon.getPath(coverBakedCacheDir(), "${entry.id}_${ratio.fileTag}.webp")
    }