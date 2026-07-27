package io.legado.app.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import io.legado.app.data.entities.Book
import io.legado.app.help.image.sourceOrigin
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * iOS 端 BookCover 加载组件 (对齐桌面端 [io.legado.desktop.ui.component.DesktopBookCover] API)。
 *
 * # 加载策略 (Coil3 统一管线, 与 Android/desktop 三端一致)
 *
 * 内部走 [rememberAsyncImagePainter] + 共享 ImageLoader
 * (装配见 [io.legado.app.help.image.registerIosBookImageLoader]):
 * - **本地路径** (`file://` / 绝对路径 `/...`): Coil3 内置 FileUriFetcher 读文件, Skia 解码
 * - **网络路径** (`http://`/`https://`): Ktor3 网络后端 (复用 IosHttpProvider 的 Ktor client),
 *   书源防盗链 header 由 SourceOriginHeaderInterceptor 按 [Book.origin] 自动注入
 * - **缓存**: Coil3 memoryCache (memoryCacheKey 按 url, 防常量键) + diskCache
 *   ({cacheDir}/image_cache), 替代原自写全局 Map 缓存
 * - **加载失败/进行中**: 走占位视觉 (与迁移前/桌面端 DesktopBookCover 完全一致:
 *   BlurCoverBg 用 accent 半透明底, InfoCover 用书名首字 + accent 底, IntroImage 用文本占位)
 *
 * # 提供的 Composable (与桌面端 DesktopBookCover 一一对应, 对外签名不变)
 *
 * - [IosBlurCoverBg]: 详情页顶部模糊封面背景 (对应 DesktopBookCover.BlurCoverBg)
 * - [IosInfoCover]: 详情页封面图 (对应 DesktopBookCover.InfoCover)
 * - [IosIntroImage]: 简介内整宽插图 (对应 DesktopBookCover.IntroImage)
 *
 * # macOS 编译命令 (Windows 无法编译 iOS target)
 *
 * ```
 * ./gradlew :shared:compileKotlinIosArm64
 * ./gradlew :shared:compileKotlinIosSimulatorArm64
 * ```
 */

/**
 * 详情页顶部模糊封面背景 (对应桌面端 DesktopBookCover.BlurCoverBg)。
 *
 * - 加载成功: 封面图 [ContentScale.Crop] 铺满 + [blur] 模糊 + accent 半透明遮罩
 * - 加载中/失败: 仅绘遮罩层即原纯色占位 `Color(0xFF165DFF).copy(alpha = 0.15f)` (视觉与迁移前一致)
 *
 * @param book 当前书籍 (可能为 null, 取 [Book.getDisplayCover] 得封面 URL)
 * @param modifier 由 shared blurCoverBgSlot 传入的尺寸约束 (保持原占位布局)
 */
@Composable
fun IosBlurCoverBg(book: Book?, modifier: Modifier = Modifier) {
    val coverUrl = book?.getDisplayCover()
    Box(modifier) {
        if (!coverUrl.isNullOrEmpty()) {
            val painter = rememberCoverPainter(coverUrl, book)
            val state by painter.state.collectAsState()
            if (state is AsyncImagePainter.State.Success) {
                // 模糊封面铺满背景区域
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(24.dp),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        // accent 半透明遮罩: 成功时降低封面饱和度, 加载中/失败时即为原占位色
        Box(Modifier.fillMaxSize().background(Color(0xFF165DFF).copy(alpha = 0.15f)))
    }
}

/**
 * 详情页封面图 (对应桌面端 DesktopBookCover.InfoCover)。
 *
 * - 加载成功: 封面图 [ContentScale.Crop] 填满 + 4dp 圆角裁剪
 * - 加载中/失败: 回退到原占位 (Box + 书名首字 + accent 底, 与桌面端视觉一致)
 *
 * @param book 当前书籍 (可能为 null, null 时走占位)
 * @param modifier 由 shared coverSlot 传入的尺寸约束 (保持原占位布局)
 */
@Composable
fun IosInfoCover(book: Book?, modifier: Modifier = Modifier) {
    val coverUrl = book?.getDisplayCover()
    if (!coverUrl.isNullOrEmpty()) {
        val painter = rememberCoverPainter(coverUrl, book)
        val state by painter.state.collectAsState()
        if (state is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = book?.name,
                modifier = modifier.clip(DesignTokens.shapeSm),
                contentScale = ContentScale.Crop,
            )
            return
        }
    }
    // 兜底: 原占位 (Box + 书名首字 + accent 底, 保持与桌面端视觉一致)
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

/**
 * 简介内整宽插图 (对应桌面端 DesktopBookCover.IntroImage)。
 *
 * - 加载成功: 图片 [ContentScale.Crop] 填满 + 4dp 圆角 + 120dp 高 (与桌面端占位尺寸一致)
 * - 加载中/失败: 回退到原占位 (Box + "图片: src" 文本, 与桌面端视觉一致)
 *
 * @param src 图片 URL/路径 (简介内 `<img src="...">` 的值, 通常为网络绝对 URL)
 * @param modifier 外部尺寸约束 (默认 Modifier, 内部仍 fillMaxWidth + 120dp 保持原视觉)
 * @param onClick 点击回调 (供后续接入大图查看器, 当前暂未挂到节点上)
 */
@Composable
fun IosIntroImage(src: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val baseModifier = modifier
        .fillMaxWidth()
        .height(120.dp)
        .clip(DesignTokens.shapeSm)
    if (src.isNotBlank()) {
        val painter = rememberCoverPainter(src, book = null)
        val state by painter.state.collectAsState()
        if (state is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = baseModifier,
                contentScale = ContentScale.Crop,
            )
            return
        }
    }
    // 兜底: 原文本占位 (保持与桌面端视觉一致)
    Box(
        baseModifier.background(Color(0xFFEEEEEE)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "图片: $src",
            color = Color(0xFF666666),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
    // onClick 暂未挂到节点 (后续接入大图查看器时挂 combinedClickable)
    @Suppress("UNUSED_PARAMETER")
    val _unused = onClick
}

/**
 * 构造封面 [AsyncImagePainter] (走共享 ImageLoader 的防盗链 Interceptor + Ktor3 网络后端)。
 *
 * - [io.legado.app.help.image.sourceOrigin]: 传 [Book.origin] 供 Interceptor 解析书源 header
 *   (本地书 origin 非 http, SourceHelp.getSource 返回 null, 自动跳过注入)
 * - memoryCacheKey 按 url 显式设置 (防常量键, 避免不同封面共享缓存条目)
 */
@Composable
private fun rememberCoverPainter(url: String, book: Book?): AsyncImagePainter {
    val context = LocalPlatformContext.current
    val request = remember(url, book?.origin) {
        ImageRequest.Builder(context)
            .data(url)
            .sourceOrigin(book?.origin)
            .memoryCacheKey(url)
            .build()
    }
    return rememberAsyncImagePainter(request)
}
