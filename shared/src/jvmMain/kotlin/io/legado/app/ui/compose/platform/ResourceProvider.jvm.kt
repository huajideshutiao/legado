@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import io.legado.app.ui.compose.theme.LocalAppColors
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString

/**
 * 非 @Composable 字符串获取入口 (供 coroutine / 普通函数 / init 块 / throw 异常等场景使用)。
 *
 * 与 [rememberString] 同源 (Compose Resources 映射表), 但取值 API 是 suspend 而调用点多为同步,
 * 故 runBlocking 桥接; 字符串记录读 classpath 资源且 AsyncCache 命中后不再 IO, 阻塞极短。
 *
 * 占位符由 Compose Resources 的 getString(res, *args) 填充, 与 Composable 侧统一。
 * formatArgs 为 `Any?` (可空), 因错误回调常传 `e.message` (String?), null 输出为 "null"。
 */
fun jvmGetString(key: String, vararg formatArgs: Any?): String {
    val resource = findStringResource(key) ?: return key
    return runBlocking {
        if (formatArgs.isEmpty()) getString(resource)
        else getString(resource, *formatArgs.map { it.toString() }.toTypedArray())
    }
}

@Composable
actual fun rememberColor(key: String): Color {
    // 共享色板单一数据源 (ColorPalette.kt): light/dark 按主题背景亮度分支,
    // 对齐 Android values/values-night 资源限定符语义
    return resolvePaletteColor(key, LocalAppColors.current.isDark)
}

/**
 * 桌面端无桌面图标概念 (对照 Android 自适应图标 / iOS 交替图标), 恒返回空列表;
 * 主题设置页"换图标"项已由 launcherIconChangeSupported=false 隐藏 (见 ThemeConfigRoute),
 * 且 ThemeConfigScreen 仅在 iconChangeSupported 时求值 iconPainters, 本函数在桌面端不会被调用。
 */
@Composable
actual fun rememberLauncherIconPainters(iconValues: List<String>): List<Painter?> = emptyList()


