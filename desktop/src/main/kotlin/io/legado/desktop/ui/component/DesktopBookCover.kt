package io.legado.desktop.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.image.PersistentCoverKey
import io.legado.app.help.image.coverDiskCacheKey
import io.legado.app.help.image.sourceOrigin
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import java.io.File

/**
 * 桌面端 BookCover 加载组件 (Coil3 实现)。
 *
 * # 加载策略
 *
 * - 加载走进程级共享 Coil3 ImageLoader (shared BookImageLoader.jvm.kt 装配):
 *   内存/磁盘缓存 + 防盗链 header/封面解密/失败 url 跳过拦截器全套
 * - 本地路径 (`file://` / 绝对路径) 转 [File] model (Coil3 FileMapper/FileUriFetcher 解码)
 * - 网络路径 (`http(s)://`) String model, memoryCacheKey 按 url
 * - 加载失败/进行中: 走原占位视觉 (保持与替换前一致的兜底样式)
 *
 * # 提供的 Composable
 *
 * - [DesktopBookCover.BlurCoverBg]: 详情页顶部模糊封面背景
 * - [DesktopBookCover.InfoCover]: 详情页封面图
 * - [DesktopBookCover.IntroImage]: 简介内整宽插图
 * - [rememberCoverPainter]: 供其他桌面消费点 (ReviewListScreen 等) 复用的 painter 入口
 *
 * # 约束
 *
 * - 不修改 shared 模块 slot 契约
 * - 不擅自修改 ui 样式 (宽高边距与原占位保持一致)
 */
object DesktopBookCover {

    /**
     * 详情页顶部模糊封面背景。
     *
     * - 加载成功: 封面图 [ContentScale.Crop] 铺满 + [blur] 模糊 + accent 半透明遮罩
     * - 加载中/失败: 回退到原纯色占位 `Color(0xFF165DFF).copy(alpha = 0.15f)`
     *
     * @param book 当前书籍 (可能为 null, 取 [Book.getDisplayCover] 得封面 URL)
     * @param modifier 由 shared blurCoverBgSlot 传入的尺寸约束 (保持原占位布局)
     */
    @Composable
    fun BlurCoverBg(book: Book?, modifier: Modifier = Modifier) {
        // I3: 模糊背景按容器 1/8 尺寸采样解码再放大绘制 (blur(24.dp) 后高频细节不可见,
        // 视觉等价, 内存/绘制带宽降 ~64 倍); 容器尺寸测量后经 size 参数触发 1/8 请求
        var sizePx by remember { mutableStateOf(IntSize.Zero) }
        val painter = rememberCoverPainter(
            book?.getDisplayCover(),
            book?.origin,
            // 非书架书的封面落临时缓存区 (对照 app 端 BookInfoActivity 的 inBookshelf 分流)
            persistent = book?.isNotShelf == false,
            widthPx = sizePx.width / 8,
            heightPx = sizePx.height / 8,
        )
        val state by painter.state.collectAsState()
        Box(modifier.onSizeChanged { sizePx = it }) {
            if (state is AsyncImagePainter.State.Success) {
                // 模糊封面铺满背景区域
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(24.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            // accent 半透明遮罩 (加载中/失败时即原纯色占位, 与替换前视觉一致)
            Box(Modifier.fillMaxSize().background(Color(0xFF165DFF).copy(alpha = 0.15f)))
        }
    }

    /**
     * 详情页封面图。
     *
     * - 加载成功: 封面图 [ContentScale.Crop] 填满 + 4dp 圆角裁剪
     * - 加载中/失败: 回退到原占位 (Box + 书名首字 + accent 底, 与替换前视觉一致)
     *
     * @param book 当前书籍 (可能为 null, null 时走占位)
     * @param modifier 由 shared coverSlot 传入的尺寸约束 (保持原占位布局)
     */
    @Composable
    fun InfoCover(book: Book?, modifier: Modifier = Modifier) {
        val painter = rememberCoverPainter(
            book?.getDisplayCover(),
            book?.origin,
            persistent = book?.isNotShelf == false,
        )
        val state by painter.state.collectAsState()
        if (state is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = book?.name,
                modifier = modifier.clip(DesignTokens.shapeSm),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 兜底: 原占位 (Box + 书名首字 + accent 底, 保持替换前视觉)
            Box(
                modifier
                    .clip(DesignTokens.shapeSm)
                    .background(Color(0xFF165DFF)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = book?.name?.firstOrNull()?.toString() ?: "?",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    /**
     * 简介内整宽插图。
     *
     * - 加载成功: 图片 [ContentScale.Crop] 填满 + 4dp 圆角 + 120dp 高 (与原占位尺寸一致)
     * - 加载中/失败: 回退到原占位 (Box + "图片: src" 文本, 保持替换前视觉)
     *
     * @param src 图片 URL/路径 (简介内 <img src="..."> 的值, 通常为网络绝对 URL)
     * @param modifier 外部尺寸约束 (默认 Modifier, 内部仍 fillMaxWidth + 120dp 保持原视觉)
     * @param onClick 点击回调，打开与 Android 一致的大图查看器
     */
    @Composable
    fun IntroImage(src: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
        val imageLabelTemplate = rememberString("image_label_with_src")
        val painter = rememberCoverPainter(src)
        val state by painter.state.collectAsState()
        val baseModifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(DesignTokens.shapeSm)
            .clickable(onClick = onClick)
        if (state is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = baseModifier,
                contentScale = ContentScale.Crop,
            )
        } else {
            // 兜底: 原文本占位 (保持替换前视觉)
            Box(
                baseModifier.background(Color(0xFFEEEEEE)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = imageLabelTemplate.format(src),
                    color = Color(0xFF666666),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 图片 painter (Coil3, 走共享 SingletonImageLoader), 桌面封面/头像/配图统一入口。
 *
 * - `http(s)://`: String model + memoryCacheKey 按 url (缓存键随 url 隔离);
 *   [sourceOrigin] 非空时由 fetcher 层 (SourceOriginHeaderFetcher) 注入书源防盗链 header
 * - `file://` / 绝对路径 (含 Windows 盘符): [File] model (默认 key 含 mtime, 封面文件更新可感知)
 * - 相对路径/未知协议/空白: model 置 null, painter 走 Error 态 (调用方显示占位)
 * - 默认 size ORIGINAL: 原图解码一次全消费点共享 (对齐替换前 LRU 行为, 模糊背景与封面同 Bitmap);
 *   传 [widthPx]/[heightPx] (>0) 时按目标尺寸采样 (FILL + INEXACT, I3 模糊背景 1/8 用),
 *   内存缓存 key 含尺寸, 与 ORIGINAL 项互不冲突 (详情页封面仍全尺寸不受影响)
 *
 * 调用方按 `painter.state` 是否 Success 决定绘制图片还是原占位。
 */
@Composable
fun rememberCoverPainter(
    src: String?,
    sourceOrigin: String? = null,
    persistent: Boolean = false,
    widthPx: Int = 0,
    heightPx: Int = 0,
): AsyncImagePainter {
    val model = remember(src, sourceOrigin, persistent, widthPx, heightPx) {
        val data = src?.takeIf { it.isNotBlank() }?.let { coverModel(it) }
        ImageRequest.Builder(PlatformContext.INSTANCE)
            .data(data)
            .sourceOrigin(sourceOrigin)
            .apply {
                if (data is String) {
                    memoryCacheKey(data)
                    // 书籍封面落持久磁盘分区 (数据目录), 清缓存/系统清理不该抹掉
                    if (persistent) {
                        diskCacheKey(coverDiskCacheKey(data))
                        extras.set(PersistentCoverKey, true)
                    }
                }
                if (widthPx > 0 && heightPx > 0) {
                    // 按目标尺寸采样 (FILL 覆盖消费端 Crop 区域, INEXACT 允许复用更大缓存项)
                    size(Size(widthPx, heightPx))
                    scale(Scale.FILL)
                    precision(Precision.INEXACT)
                } else {
                    size(Size.ORIGINAL)
                }
            }
            .build()
    }
    return rememberAsyncImagePainter(model)
}

/**
 * src → Coil3 model: 网络 url 原样 String, 本地路径转 [File], 其余 null (走占位)。
 * [File.isAbsolute] 判定兼容 Windows 盘符路径 (原实现只认 `/` 前缀会漏)。
 */
private fun coverModel(src: String): Any? = when {
    src.startsWith("http://") || src.startsWith("https://") -> src
    src.startsWith("file://") -> File(src.removePrefix("file://"))
    else -> File(src).takeIf { it.isAbsolute }
}
