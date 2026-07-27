package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Android actual: 走 painterResource/stringResource/colorResource + Resources.getIdentifier。
 *
 * shared 模块 androidMain 不能引用 app 模块的 io.legado.app.R (R 属于 app 模块),
 * 故用 [android.content.res.Resources.getIdentifier] 按 key 动态查 id。
 * getIdentifier 在 release 构建会受 R8 资源混淆影响, 但 legado app 不开启 R8 资源混淆
 * (app/build.gradle 未配 `android.enableR8.fullMode=true` + shrinkResources=false),
 * key 名称稳定可查; 后续若启用资源混淆需切换为静态 when(key) 映射表。
 *
 * 未识别的 key: getIdentifier 返回 0, painterResource(0)/stringResource(0)/colorResource(0)
 * 会抛 Resources$NotFoundException, 与 app 端直接写死 R.drawable.xxx 行为一致
 * (开发期暴露缺失资源), 不做静默兜底。
 */
@Composable
actual fun rememberPainter(key: String): Painter {
    val context = LocalContext.current
    val id = context.resources.getIdentifier(key, "drawable", context.packageName)
    return painterResource(id)
}

@Composable
actual fun rememberString(key: String, vararg formatArgs: Any): String {
    val context = LocalContext.current
    val id = context.resources.getIdentifier(key, "string", context.packageName)
    // formatArgs 为空时走 stringResource(id) 不格式化, 与原 app 端 stringResource 行为一致;
    // 非空时走 stringResource(id, *formatArgs), 由 Android Formatter 填充占位符
    return if (formatArgs.isEmpty()) stringResource(id) else stringResource(id, *formatArgs)
}

@Composable
actual fun rememberStringArray(key: String): List<String> {
    val context = LocalContext.current
    val id = context.resources.getIdentifier(key, "array", context.packageName)
    return stringArrayResource(id).toList()
}

/**
 * Android actual: 按 mipmap 资源名查 id, AdaptiveIconDrawable 转 Bitmap 后包 BitmapPainter。
 *
 * 复刻 app 端 ThemeConfigScreen 原图标加载逻辑:
 * - `Resources.getIdentifier(name, "mipmap", packageName)` 查 mipmap 资源 id
 *   (mipmap 不在 drawable 类型下, 需单独查)
 * - `ContextCompat.getDrawable` 取 Drawable (替代 app 模块 Context.getCompatDrawable 扩展)
 * - `Drawable.toBitmap()` + `asImageBitmap()` + `BitmapPainter` 转 Painter
 *   (AdaptiveIconDrawable 不支持 painterResource, 必须先转 Bitmap)
 * - runCatching 兜底, 资源缺失返回 null
 */
@Composable
actual fun rememberLauncherIconPainters(iconValues: List<String>): List<Painter?> {
    val context = LocalContext.current
    return remember(iconValues) {
        iconValues.map { name ->
            @Suppress("DiscouragedApi")
            val resId = context.resources.getIdentifier(name, "mipmap", context.packageName)
            if (resId == 0) return@map null
            runCatching {
                BitmapPainter(ContextCompat.getDrawable(context, resId)!!.toBitmap().asImageBitmap())
            }.getOrNull()
        }
    }
}

@Composable
actual fun rememberColor(key: String): Color {
    val context = LocalContext.current
    val id = context.resources.getIdentifier(key, "color", context.packageName)
    return colorResource(id)
}
