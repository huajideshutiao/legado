package io.legado.app.model

import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.file.AppFilesDirs

/**
 * 把用户选中的默认封面原图烘焙成指定比例的小图字节 (写盘由调用方负责)。
 *
 * 对照原 app 端 `BookCover.bakeAndWrite`: NOVEL 300×400 居中裁剪 / VIDEO 720×405 顶部裁剪
 * (视频封面保顶部信息), webp q85 编码。actual 只有两份: androidMain (Bitmap) +
 * skikoUiMain 单份 Skiko (jvm/iOS/鸿蒙共用; ImageIO 生态无 webp writer, Skiko 由
 * Compose 各端必带, 同 DesktopImageOps 的 webp 编码路径)。
 *
 * expect 刻意放 commonMain 而非 sharedUiMain (本符号无 UI 依赖): actual↔expect 跨
 * 自定义中间源集配对在 IDE 有误报史 (见 shared/build.gradle.kts androidMain 源集处
 * jvmAndAndroidMain 注释), androidMain↔commonMain 是模板源集间的标准配对, 无此问题。
 *
 * @return 烘焙字节; 解码/编码失败返回 null, 调用方中止添加并提示
 * (解码不了的输入对各端渲染同样是不可解码的, 无回落意义)
 */
internal expect fun bakeDefaultCoverBytes(
    sourceBytes: ByteArray,
    ratio: BookCoverShared.CoverRatio,
): ByteArray?

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

/**
 * 烘焙两 ratio 产物并写缓存目录 (手动封面导入与默认封面图集添加共用)。
 * 任一 ratio 解码/编码失败返回 false, 调用方按各自语义处理 (图集: 中止添加;
 * 手动封面: 只记日志, 原图已入图集渲染端回落原图)。.9 图由调用方直落原图, 不烘焙。
 */
fun bakeCoverVariantsToCache(bytes: ByteArray, id: String): Boolean {
    val dir = coverBakedCacheDir()
    FileUtilsCommon.createFolderIfNotExist(dir)
    return BookCoverShared.CoverRatio.entries.all { ratio ->
        val baked = bakeDefaultCoverBytes(bytes, ratio) ?: return false
        FileUtilsCommon.writeBytes(
            FileUtilsCommon.getPath(dir, "${id}_${ratio.fileTag}.webp"),
            baked,
        )
    }
}
