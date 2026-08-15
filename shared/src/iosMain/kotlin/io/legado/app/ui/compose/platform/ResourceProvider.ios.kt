@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.legado.app.ui.compose.theme.LocalAppColors
import io.legado.app.utils.File
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import platform.Foundation.NSBundle

/**
 * iOS actual: 从 main bundle 读交替图标 PNG (AppIcon/Icon1/Icon4/Icon5, 与
 * Info.plist CFBundleAlternateIcons 同名) 经 skia 解码转 [BitmapPainter]。
 *
 * 映射与 [io.legado.app.ui.IosPlatformCapabilities.changeLauncherIcon] 一致:
 * ic_launcher -> AppIcon, launcher1 -> Icon1, launcher4 -> Icon4, launcher5 -> Icon5。
 * 资源缺失时返回 null (与 Android 端资源缺失兜底一致)。
 */
@Composable
actual fun rememberLauncherIconPainters(iconValues: List<String>): List<Painter?> {
    return remember(iconValues) {
        iconValues.map { value ->
            val bundleName = when (value) {
                "ic_launcher" -> "AppIcon"
                "launcher1" -> "Icon1"
                "launcher4" -> "Icon4"
                "launcher5" -> "Icon5"
                else -> null
            } ?: return@map null
            val path = NSBundle.mainBundle.pathForResource(bundleName, "png") ?: return@map null
            runCatching {
                BitmapPainter(
                    org.jetbrains.skia.Image.makeFromEncoded(File(path).readBytes())
                        .toComposeImageBitmap()
                )
            }.getOrNull()
        }
    }
}

@Composable
actual fun rememberColor(key: String): Color {
    // 共享色板单一数据源 (ColorPalette.kt): light/dark 按主题背景亮度分支,
    // 对齐 Android values/values-night 资源限定符语义
    return resolvePaletteColor(key, LocalAppColors.current.isDark)
}
