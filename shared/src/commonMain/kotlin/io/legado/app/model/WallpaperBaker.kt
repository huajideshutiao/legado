package io.legado.app.model

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntRect
import io.legado.app.constant.PreferKey
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.config.PreferenceProvider
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.resolveImagePath
import io.legado.app.help.file.AppFilesDirs
import kotlin.math.roundToInt

/** 移动端模糊工作图的短边上限 (纯 CPU/大图模糊会卡)。 */
internal const val BAKE_MAX_SHORT_SIDE = 800

/**
 * 模糊工作图的短边上限: `<=0` 表示按原图尺寸模糊 (桌面 CPU/内存充裕, 产物分辨率不低于源图,
 * 显示端不必放大); `>0` 表示先降采样到该短边再模糊。
 *
 * 移动端 (Android/iOS/鸿蒙) 取 [BAKE_MAX_SHORT_SIDE]: 高斯模糊代价随像素数线性涨, 按原图
 * 尺寸模糊会卡; 且屏幕尺寸固定, 产物被拉伸的倍数是定值, 分辨率不足没有感知。
 */
internal expect val blurWorkMaxShortSide: Int

/**
 * 解码壁纸位图: 按 [androidx.compose.ui.layout.ContentScale.Crop] 覆盖 (widthPx, heightPx)
 * 所需的最小尺寸解码 (只缩不放), [radiusPx] > 0 时顺带高斯模糊 (模糊只是运行期兜底,
 * 正常路径读 [bakeBlurredImageFile] 的落盘产物)。
 *
 * 尺寸按"覆盖"算而非"长边上限": Crop 的缩放比是 `max(W/sw, H/sh)`, 按长边上限采出来的图
 * 在窗口与原图宽高比正交时必有一维不够, 显示端只能放大 → 糊; 反之采过头则要缩小绘制,
 * 位图上的采样锯齿被原样呈现。
 *
 * actual 只有两份 (同 [bakeDefaultCoverBytes] 布局): androidMain (BitmapFactory 采样 +
 * StackBlur 下沉实现) + skikoUiMain 单份 Skiko (jvm/iOS/鸿蒙共用; Android 无 skiko)。
 *
 * expect 放 commonMain 而非 sharedUiMain 的理由同 bakeDefaultCoverBytes:
 * actual↔expect 跨自定义中间源集 (skikoUiMain) 配对在 IDE 有误报史。
 *
 * @param path 壁纸绝对路径 (调用方已经 resolveImagePath 解析)
 * @param widthPx 目标宽 (Crop 覆盖尺寸)
 * @param heightPx 目标高 (Crop 覆盖尺寸)
 * @param radiusPx 模糊半径 (px, 对照原版 bgImageBlurring 键的 stackBlur 半径); <=0 不模糊
 * @return 解码后的位图 (原图小于覆盖尺寸时即原图尺寸, 显示端 Crop 放大); 失败返回 null
 */
internal expect fun decodeWallpaper(
    path: String,
    widthPx: Int,
    heightPx: Int,
    radiusPx: Int,
): ImageBitmap?

/**
 * 把源图离线烘焙成模糊图并写盘: 解码 → 模糊 → WEBP q80。设置保存时一次性产出 `_blur` 文件,
 * 运行期直接加载现成文件零处理。
 *
 * 移动端 ([blurWorkMaxShortSide] > 0) 先按**屏幕宽高比**居中裁剪再把裁剪区短边压到该上限:
 * 显示端 Crop 的构图以屏幕比例为基准, 不预裁剪时产物比例跟原图走 (横图配竖屏),
 * Crop 只能取中间一条且放大倍数失控; 桌面 (= 0) 保持原图尺寸模糊, 窗口比例随时可变。
 * [radiusPx] 是像素半径, 不随工作图尺寸归一化 —— 同一个滑块值在大图上视觉模糊度更轻。
 *
 * @return 写盘成功 true; 解码/编码/写盘失败 false
 */
internal expect fun bakeBlurredImageFile(
    srcPath: String,
    destPath: String,
    radiusPx: Int,
): Boolean

/**
 * 试解码校验: 字节能否被本端解码器解出位图。系统选择器会放行本端解不开的格式
 * (iOS 相册 HEIC/HEIF 最常见), 放进图集就是「导入成功、显示永远空白」的静默坑,
 * 导入 ([io.legado.app.ui.root.PlatformServices.importBackgroundImage]) 前先拦一道。
 */
internal expect fun probeDecodeImage(bytes: ByteArray): Boolean

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
 * 把源图按目标框 [maxW]×[maxH] 烘焙成清晰 WEBP 产物并写盘: 按目标宽高比居中裁剪 →
 * 等比缩放到**不超出**目标框 (min(1,·): 源图/裁剪区小于目标框时保持原尺寸不放大,
 * 放大交给显示端 Crop) → WEBP q80。选图导入时一次性产出, 运行期直接加载现成文件
 * 零处理 (启动图与主题背景图共用)。
 *
 * @return 写盘成功 true; 解码/编码/写盘失败 false
 */
expect fun bakeCoverImageFile(
    srcPath: String,
    destPath: String,
    maxW: Int,
    maxH: Int,
): Boolean

/**
 * 渲染端确保清晰烘焙产物可用: 产物存在直接返回其路径; 缺失从原图现场重烘焙落盘后返回;
 * 失败返回 null, 调用方回落直解原图。阻塞 IO。
 */
fun ensureBakedImage(srcAbs: String, maxW: Int, maxH: Int): String? {
    val baked = bakedImagePath(srcAbs)
    if (FileUtilsCommon.exist(baked)) return baked
    return if (bakeCoverImageFile(srcAbs, baked, maxW, maxH)) baked else null
}

/**
 * 渲染端确保模糊烘焙产物可用: 产物存在直接返回其路径; 缺失从原图现场重烘焙落盘后返回;
 * 失败返回 null, 调用方回落直解原图/运行期兜底模糊。阻塞 IO。
 */
fun ensureBakedBlurredImage(srcAbs: String, radiusPx: Int): String? {
    val blurred = blurredImageVariantPath(srcAbs)
    if (FileUtilsCommon.exist(blurred)) return blurred
    return if (bakeBlurredImageFile(srcAbs, blurred, radiusPx)) blurred else null
}

/**
 * 按 [aspect] (w/h) 从 (srcW × srcH) 源图算居中裁剪区: 源更宽裁两边, 源更高裁上下。
 *
 * 启动图烘焙 ([bakedImagePath] 机制) 与移动端壁纸模糊烘焙共用 —— 显示端都是 Crop,
 * 构图以目标宽高比为基准, 不预裁剪时产物比例跟原图走, 横图配竖屏只能取中间一条。
 */
fun centerCropRect(srcW: Int, srcH: Int, aspect: Float): IntRect {
    val w = srcW.coerceAtLeast(1)
    val h = srcH.coerceAtLeast(1)
    return if (w.toFloat() / h > aspect) {
        val cw = (h * aspect).roundToInt().coerceIn(1, w)
        IntRect((w - cw) / 2, 0, (w - cw) / 2 + cw, h)
    } else {
        val ch = (w / aspect).roundToInt().coerceIn(1, h)
        IntRect(0, (h - ch) / 2, w, (h - ch) / 2 + ch)
    }
}

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
 * 提交主题背景图设置 (选图即生效与点保存共用): 删旧产物 → 写 bgImage(N)/模糊键 →
 * 按新半径重烘焙模糊产物。[ref] 为 null/空表示清除背景图。
 *
 * 单一提交点保证"pref / 图集文件 / 产物"三者永不错位 —— 选图当场就以内容特征值落图集,
 * 若只在保存时写 pref, 取消对话框会留下"文件已换、设置未换"的错位态。
 * 目标态与现状一致时直接返回 (选图已提交过、紧接着点保存不该再烘焙一遍)。
 * 旧图集原图不在此删 (原版语义: 文件按内容特征值命名, 换图旧文件留给 clearBg 白名单清;
 * 特征值下日/夜可能引用同一文件, 提交侧删会误伤另一模式)。
 *
 * 阻塞 IO, 必须在 IO 线程调用; 烘焙失败抛出由调用方记录 (不静默留旧产物)。
 */
fun commitBackgroundImage(
    prefs: PreferenceProvider,
    isNight: Boolean,
    ref: String?,
    radiusPx: Int,
) {
    val imageKey = if (isNight) PreferKey.bgImageN else PreferKey.bgImage
    val blurKey = if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring
    val oldRef = prefs.getString(imageKey)
    val newRef = ref?.takeUnless { it.isBlank() }
    val newAbs = resolveImagePath(newRef)
    // 幂等短路: 键值与模糊产物都已是目标态, 不重复删+烘焙 (烘焙是整张图解码+模糊+编码)
    if (oldRef == newRef.orEmpty() && prefs.getInt(blurKey, 0) == radiusPx &&
        (newAbs == null || radiusPx <= 0 || FileUtilsCommon.exist(blurredImageVariantPath(newAbs)))
    ) {
        return
    }
    // 旧产物按旧引用删除 (换图/换半径/清除都要清, 否则渲染端会读到上一张图的模糊图;
    // 旧 pref 可能是旧机制的绝对路径/处理图, 其产物名不与当前引用派生名相同, 一并清)。
    // 四键引用保护: 当前键即将写新值 (排除), 其余键 (另一模式/启动封面日/夜) 仍引用同文件
    // 时保留其产物, 避免误删其它引用的缓存
    val oldAbs = resolveImagePath(oldRef)
    if (oldAbs != null) {
        deleteImageIfUnreferenced(oldAbs, withFile = false, excludeKey = imageKey)
    }
    if (newRef == null) {
        prefs.remove(imageKey)
        prefs.putInt(blurKey, radiusPx)
        return
    }
    prefs.putString(imageKey, newRef)
    prefs.putInt(blurKey, radiusPx)
    if (radiusPx <= 0 || newAbs == null) return
    if (!bakeBlurredImageFile(newAbs, blurredImageVariantPath(newAbs), radiusPx)) {
        // 留着上一张图的模糊产物比没有更糟 (会显示旧图), 上面已删, 这里只需让调用方知道
        throw IllegalStateException("烘焙背景模糊图失败: $newAbs")
    }
}
