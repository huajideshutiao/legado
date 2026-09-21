package io.legado.app.ui.root

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.decodeWallpaper
import io.legado.app.model.ensureBakedBlurredImage
import io.legado.app.model.ensureBakedImage
import io.legado.app.utils.ScreenInfoProviders
import kotlinx.coroutines.withContext

/** 解码尺寸的量化台阶 (px): 拖窗口每变一像素都重解一张满屏图太贵, 对齐到台阶上向上取整。 */
private const val WALLPAPER_SIZE_STEP = 256

/**
 * 壁纸位图: 全应用只解码一份, 在 [LegadoApp] 顶层 remember 后传给每个路由页面底部的
 * [WallpaperLayer] 共用 —— 栈内页面全部处于组合中 (隐藏页只是 alpha=0 平移出屏),
 * 逐页各解一份会按栈深度堆满屏尺寸位图。
 *
 * 背景图按内容特征值命名 (<字节数>.<ext>, 同字节数覆盖同名), path 因此不足以标识内容, 用**源图 mtime**
 * 当内容指纹: 提交背景图设置必然重写源文件, 恢复备份换掉源文件也算。
 * [themeTick] 只是重新 stat 的时机 (recreateEvent 计数) —— 主题色/底栏这类无关的 recreate
 * 拿到同一个指纹, 不会白重解码一遍。
 */
@Composable
fun rememberWallpaperBitmap(path: String, blurPx: Int, themeTick: Int): ImageBitmap? {
    val containerSize = LocalWindowInfo.current.containerSize
    val stamp = remember(path, themeTick) { FileUtilsCommon.lastModified(path) }
    val targetW = containerSize.width.ceilToSizeStep()
    val targetH = containerSize.height.ceilToSizeStep()
    val bitmap by produceState<ImageBitmap?>(null, path, blurPx, stamp, targetW, targetH) {
        value = if (targetW > 0 && targetH > 0) {
            // 解码 (必要时兜底模糊) 是阻塞 CPU 操作, 低端设备上明显, 必须切 IO 线程
            withContext(IoDispatcher) { loadWallpaper(path, targetW, targetH, blurPx) }
        } else {
            null
        }
    }
    return bitmap
}

/**
 * 主题背景图 (壁纸) 绘制层, 挂载在**每个路由页面底部** (对齐原版页面=Activity、背景随页面
 * 转场移动的语义; 窗口级背景是静止的, 与原版不等价)。位图由 [rememberWallpaperBitmap] 供给。
 */
@Composable
fun WallpaperLayer(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // Medium = 双线性 + mipmap。位图按台阶向上取整后比窗口略大, 拖窗变小时更大, 都是缩小
        // 绘制; 默认的 Low 无 mipmap, 缩小到 0.5 倍附近就起锯齿
        filterQuality = FilterQuality.Medium,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * 壁纸解码: 产物优先 (清晰 [io.legado.app.model.ensureBakedImage] / 模糊
 * [io.legado.app.model.ensureBakedBlurredImage]): 产物在直接读零处理; 清缓存/备份恢复首启
 * 缺失时按**本端真实全屏像素** (与选图导入同基准, 全端对齐) 现场重烘焙落盘再读;
 * 重烘焙失败才回落按窗口尺寸解码原图 (无模糊) / 运行期兜底模糊 (有模糊)。
 *
 * 不走 [io.legado.app.help.image.ImageBitmapLoader]: 壁纸是本地文件, 不需要网络/书源/cbz
 * 那一层, 而它按"长边上限"采样的尺寸契约与 [ContentScale.Crop] 的覆盖语义不是一回事。
 */
private suspend fun loadWallpaper(path: String, w: Int, h: Int, blurPx: Int): ImageBitmap? {
    // 真实全屏像素 (iOS nativeBounds / 鸿蒙显示物理像素 / 桌面 Toolkit.screenSize / 安卓 getRealMetrics);
    // 取不到时回落当前窗口尺寸 (移动端窗口即全屏, 桌面窗口可调但为兜底场景)
    val screen = runCatching { ScreenInfoProviders.get() }.getOrNull()
    val bakeW = screen?.screenWidthPx?.takeIf { it > 0 } ?: w
    val bakeH = screen?.screenHeightPx?.takeIf { it > 0 } ?: h
    if (blurPx <= 0) {
        val baked = ensureBakedImage(path, bakeW, bakeH)
            ?: return decodeWallpaper(path, w, h, 0)
        return decodeWallpaper(baked, w, h, 0) ?: decodeWallpaper(path, w, h, 0)
    }
    val blurred = ensureBakedBlurredImage(path, blurPx)
        ?: return decodeWallpaper(path, w, h, blurPx)
    return decodeWallpaper(blurred, w, h, 0) ?: decodeWallpaper(path, w, h, blurPx)
}

/** 向上取整到 [WALLPAPER_SIZE_STEP] 台阶; 非正数 (窗口尺寸未就绪) 保持 0。 */
private fun Int.ceilToSizeStep(): Int =
    if (this <= 0) 0 else (this + WALLPAPER_SIZE_STEP - 1) / WALLPAPER_SIZE_STEP * WALLPAPER_SIZE_STEP
