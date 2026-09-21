package io.legado.app.ui.book.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.help.image.BookImageLoaders

/**
 * 模糊封面背景 Coil3 实现 (desktop/iOS 共享)。
 *
 * 走 [BookImageLoaders] (各端注入 Coil3 实现) 加载封面 Bitmap, 再用 [Modifier.blur] 做高斯模糊。
 * I3: 按容器尺寸 1/8 采样解码 (size(w/8,h/8) + FILL + INEXACT), blur 后放大绘制视觉等价,
 * 内存/绘制带宽降 ~64 倍。
 * 加载中/失败/E-Ink 模式不绘制 (对照原版 bgBook 空 drawable), 不铺色块以免换图时闪一下。
 *
 * 渐变蒙版对照 app 端 [io.legado.app.ui.book.info.BookInfoBgTransformation]:
 * 先叠压暗 (原版 argb(50,0,0,0) SRC_ATOP ≈ 压暗 20%), 再按 DstIn alpha 蒙版收尾
 * (顶部 30% 清晰 → 30%-65% 柔和过渡 → 底部透明)。用 Compose 绘制层实现, 免 Android 位图 API。
 *
 * @param book 当前书籍 (可能为 null)
 * @param coverTick 封面重载 key (变更时重新加载)
 * @param inBookshelf 是否在书架 (本简化实现未使用, 保留签名对齐 slot 契约)
 * @param isEInkMode E-Ink 模式跳过图片加载 (不绘制)
 * @param modifier 调用方传入的尺寸约束
 */
@Composable
fun SharedBlurCoverBgCoil(
    book: Book?,
    coverTick: Int,
    inBookshelf: Boolean,
    isEInkMode: Boolean,
    modifier: Modifier,
    land: Boolean = false,
) {
    val cover = book?.getDisplayCover()
    val loader = remember { BookImageLoaders.getOrNull() }
    // bitmap 只随封面 url 重置; coverTick 触发的重载期间保留旧图, 失败也不清空 ——
    // 否则重载被失败跳过表拦截时 (url 曾 403) 会闪回空白
    var bitmap by remember(cover) { mutableStateOf<ImageBitmap?>(null) }
    // 封面取色回调 (详情页宿主提供, 见 BookCoverPalette); 失败不回调, 取色保留旧值/回退
    val onCoverLoaded = LocalCoverLoaded.current
    // I3: 模糊背景按 1/8 显示尺寸采样解码再放大绘制 (blur 后高频细节不可见, 视觉等价),
    // 内存/绘制带宽降 ~64 倍; 容器尺寸测量后触发加载 (窗口 resize 后按新尺寸重解)
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(cover, coverTick, loader, isEInkMode, containerSize) {
        if (isEInkMode || cover.isNullOrBlank() || loader == null) return@LaunchedEffect
        if (containerSize == IntSize.Zero) return@LaunchedEffect
        val w = (containerSize.width / 8).coerceAtLeast(1)
        val h = (containerSize.height / 8).coerceAtLeast(1)
        val loaded = loader.loadImageOrNull(cover, book.origin, w, h) ?: return@LaunchedEffect
        bitmap = loaded
        // 原图 (未经 blur/渐变绘制处理) 就绪, 上报给封面取色
        onCoverLoaded?.invoke(loaded)
    }
    Box(modifier.onSizeChanged { containerSize = it }) {
        // 模糊封面铺满 + 渐变蒙版 + 压暗 (对照原版 BookInfoBgTransformation)
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // 链序关键: drawWithContent 在 blur 外层, 否则渐变蒙版/压暗也会被高斯模糊
                modifier = Modifier
                    .fillMaxSize()
                    // DstIn 蒙版必须有自己的离屏缓冲, 否则作用对象是父画布 (含身后页面底色),
                    // 底部会被抹成透明, 露出窗口黑底
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // 压暗蒙层 (对照原版 argb(50,0,0,0) SRC_ATOP: 只压图自身不改 alpha),
                        // 故须在蒙版之前叠, 否则会把蒙成透明的底部又涂回一层 20% 黑
                        drawRect(color = Color(0x32000000))
                        // 渐变蒙版 (顶部 30% 清晰 → 底部透明, 曲线同原版) 仅竖屏顶部条使用;
                        // 横屏左半列整列铺满时任何方向的"一边黑"渐变都不合适 (用户反馈),
                        // 横屏只保留均匀压暗, 整列均匀模糊
                        if (!land) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.00f to Color.Black,
                                        0.30f to Color.Black,
                                        0.48f to Color(0xD2000000),
                                        0.63f to Color(0xA5000000),
                                        0.77f to Color(0x6E000000),
                                        0.90f to Color(0x1E000000),
                                        1.00f to Color.Transparent,
                                    ),
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    }
                    .blur(24.dp),
            )
        }
        // 加载中/失败/E-Ink 一律不画东西 (对照原版 bgBook: drawable 为空即空白, 不铺色块),
        // 铺深色占位再换成模糊图会闪一下
    }
}
