package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import io.legado.shared.R
import java.util.concurrent.ConcurrentHashMap

/**
 * 资源名→id 缓存: [android.content.res.Resources.getIdentifier] 是反射式 O(n) 扫资源表,
 * 列表滚动时每个可见项的 rememberString/rememberColor 都会触发, 累计开销可观。
 * 进程级缓存, 首次 miss 才查, 后续 O(1) 命中 (跨 Composable 实例共享)。
 */
private val resourceIdCache = ConcurrentHashMap<String, Int>()

@Suppress("DiscouragedApi")
private fun resolveResourceId(context: android.content.Context, name: String, type: String): Int {
    val cacheKey = "$type:$name"
    return resourceIdCache.getOrPut(cacheKey) {
        context.resources.getIdentifier(name, type, context.packageName)
    }
}

/**
 * Android 直接使用 shared/src/sharedIconResources/drawable 中由 AAPT 编译的 vector XML。
 * 同一目录由 JVM/iOS/OHOS 作为 Compose Resources 使用，因此图标仍只有一份源码；
 * Android 不再把整套图标复制到 assets/composeResources。
 */
@Composable
actual fun rememberPainter(key: String): Painter {
    val context = LocalContext.current
    val id = resolveResourceId(context, key, "drawable")
    return painterResource(if (id != 0) id else R.drawable.ic_material_help)
}

@Composable
actual fun rememberString(key: String, vararg formatArgs: Any): String {
    val context = LocalContext.current
    val id = resolveResourceId(context, key, "string")
    // 资源缺失兜底: getIdentifier 找不到返回 0, stringResource(0) 会抛 NotFoundException;
    // 与 jvmMain/iosMain 的 sharedStringTable[key] ?: key 兜底行为对齐
    if (id == 0) return key
    // formatArgs 为空时走 stringResource(id) 不格式化, 与原 app 端 stringResource 行为一致;
    // 非空时走 stringResource(id, *formatArgs), 由 Android Formatter 填充占位符
    return if (formatArgs.isEmpty()) stringResource(id) else stringResource(id, *formatArgs)
}

@Composable
actual fun rememberStringArray(key: String): List<String> {
    val context = LocalContext.current
    val id = resolveResourceId(context, key, "array")
    // 资源缺失兜底: getIdentifier 返回 0 时 stringArrayResource(0) 会抛 NotFoundException
    if (id == 0) return emptyList()
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
    val id = resolveResourceId(context, key, "color")
    return colorResource(id)
}
