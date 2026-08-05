package io.legado.app.ui.book.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isVideo
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.ui.bookshelf.LocalBookCoverSlot
import io.legado.app.ui.compose.theme.AppTheme

/*
 * BookInfoScreen L3 slot 的 CompositionLocal: 模糊封面背景 + 简介内整宽图。
 *
 * 宿主端 (app `BookInfoBlurCoverBg`/`BookInfoIntroImage` / desktop `SharedBlurCoverBgCoil`/
 * `SharedIntroImageCoil` / iOS 同 desktop) 用 [CompositionLocalProvider] 覆盖注入平台实现,
 * 供 shared 路由 [io.legado.app.ui.route.BookInfoRoute] 渲染, 避免 shared 路由硬编码平台组件。
 *
 * 未注入时走 default 兜底实现:
 * - [SharedBlurCoverBgPlaceholder]: accent 半透明纯色背景 (非 Android 平台兜底)
 * - [SharedIntroImage]: 走 [BookImageLoaders] 加载 (ohos 未注册 loader 时回退占位)
 *
 * 模式参考 [io.legado.app.ui.bookshelf.LocalBookCoverSlot] / [io.legado.app.ui.browser.LocalWebViewSlot]。
 */

/**
 * 模糊封面背景 slot。
 *
 * 签名 `(Book?, Int, Boolean, Boolean, Modifier, Boolean) -> Unit` 对齐 app 端 [BookInfoBlurCoverBg] 入参:
 * - [Book]: 当前书籍 (可能为 null)
 * - Int: coverTick, 封面重载 key (对照 activity.coverTick)
 * - Boolean: inBookshelf, 是否在书架 (对照 activity.viewModel.inBookshelf)
 * - Boolean: isEInkMode, E-Ink 模式跳过模糊加载
 * - [Modifier]: 调用方传入的尺寸约束 (fillMaxSize 或 fillMaxWidth+height(300.dp))
 * - Boolean: land, 横屏布局 (左半列铺满, 渐变改水平; 竖屏顶部条垂直渐变)。
 *   2026-08 用户反馈: 横屏整列套垂直渐变 → 从上到下越来越黑, 与原版行为不符
 */
val LocalBlurCoverBgSlot =
    staticCompositionLocalOf<@Composable (Book?, Int, Boolean, Boolean, Modifier, Boolean) -> Unit> {
        @Composable { book, coverTick, inBookshelf, isEInkMode, modifier, land ->
            SharedBlurCoverBgPlaceholder(book, modifier)
        }
    }

/**
 * 书籍详情封面 slot。
 *
 * 保留详情页的 [coverTick] 强制刷新语义, 渲染直接委托书架通用封面 slot
 * ([LocalBookCoverSlot], CoverImageView 的迁移组件), 不再平行实现一套。
 */
val LocalBookInfoCoverSlot =
    staticCompositionLocalOf<@Composable (Book?, Int, Boolean, Modifier) -> Unit> {
        @Composable { book, coverTick, _, modifier ->
            if (book != null) {
                val coverSlot = LocalBookCoverSlot.current
                key(book.bookUrl, coverTick) {
                    coverSlot(book, modifier, book.isVideo, 0)
                }
            }
        }
    }

/**
 * 简介内整宽图 slot。
 *
 * 签名 `(String, () -> Unit) -> Unit` 对齐 [BookInfoScreen] 的 introImageSlot:
 * - String: 图片 URL/路径
 * - () -> Unit: 点击查看大图回调
 */
val LocalIntroImageSlot =
    staticCompositionLocalOf<@Composable (String, () -> Unit) -> Unit> {
        @Composable { src, onClick -> SharedIntroImage(src, onClick) }
    }

/**
 * 模糊封面背景默认占位: accent 半透明纯色 (非 Android 平台兜底)。
 *
 * 视觉: accent 色 15% 透明 + 底色叠加, 与 desktop 替换前纯色占位一致。
 * ohos 等未注册 [BookImageLoaders] 的平台恒走本占位。
 */
@Composable
fun SharedBlurCoverBgPlaceholder(
    book: Book?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(AppTheme.colors.accent.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        // 加深底色, 保证标题栏文字可读
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
    }
}

/**
 * 简介内整宽图默认实现: 走 [BookImageLoaders] 加载, 失败/未注册时回退文本占位。
 *
 * desktop/iOS 已注册 Coil3 版 [BookImageLoader], 可正常加载;
 * ohos 未注册时走占位 (与替换前行为一致)。
 */
@Composable
fun SharedIntroImage(
    src: String,
    onClick: () -> Unit,
) {
    val loader = remember { BookImageLoaders.getOrNull() }
    var bitmap by remember(src) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(src, loader) {
        if (src.isBlank() || loader == null) return@LaunchedEffect
        loader.loadImage(
            url = src,
            sourceOrigin = null,
            onSuccess = { bitmap = it },
            onError = { bitmap = null },
        )
    }
    val bmp = bitmap
    if (bmp != null) {
        DisableSelection {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(onClick = onClick),
            )
        }
    } else {
        // 占位: 加载中/失败/无 loader 时显示浅灰底
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(Color(0xFFEEEEEE))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // 不引入新 string key, 直接用 src 截断显示
            val display = src.takeIf { it.isNotBlank() }
                ?.let { if (it.length > 40) it.take(40) + "..." else it } ?: ""
            androidx.compose.material.Text(
                text = display,
                color = Color(0xFF666666),
            )
        }
    }
}
