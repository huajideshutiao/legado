@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package io.legado.app.ui.compose.platform

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.ic_arrow_back
import legado.shared.generated.resources.ic_arrow_down
import legado.shared.generated.resources.ic_arrow_drop_down
import legado.shared.generated.resources.ic_arrow_drop_up
import legado.shared.generated.resources.ic_arrow_right
import legado.shared.generated.resources.ic_auto_page
import legado.shared.generated.resources.ic_auto_page_stop
import legado.shared.generated.resources.ic_author
import legado.shared.generated.resources.ic_baseline_close
import legado.shared.generated.resources.ic_baseline_sort_24
import legado.shared.generated.resources.ic_book_has
import legado.shared.generated.resources.ic_book_last
import legado.shared.generated.resources.ic_bookmark
import legado.shared.generated.resources.ic_bottom_books_e
import legado.shared.generated.resources.ic_bottom_books_s
import legado.shared.generated.resources.ic_bottom_explore_e
import legado.shared.generated.resources.ic_bottom_explore_s
import legado.shared.generated.resources.ic_bottom_home_e
import legado.shared.generated.resources.ic_bottom_home_s
import legado.shared.generated.resources.ic_bottom_person_e
import legado.shared.generated.resources.ic_bottom_person_s
import legado.shared.generated.resources.ic_brightness
import legado.shared.generated.resources.ic_bug_report
import legado.shared.generated.resources.ic_cfg_about
import legado.shared.generated.resources.ic_cfg_backup
import legado.shared.generated.resources.ic_cfg_other
import legado.shared.generated.resources.ic_cfg_replace
import legado.shared.generated.resources.ic_cfg_source
import legado.shared.generated.resources.ic_cfg_theme
import legado.shared.generated.resources.ic_cfg_web
import legado.shared.generated.resources.ic_check
import legado.shared.generated.resources.ic_clear_all
import legado.shared.generated.resources.ic_download_line
import legado.shared.generated.resources.ic_edit
import legado.shared.generated.resources.ic_exchange
import legado.shared.generated.resources.ic_expand_less
import legado.shared.generated.resources.ic_expand_more
import legado.shared.generated.resources.ic_export
import legado.shared.generated.resources.ic_find_replace
import legado.shared.generated.resources.ic_folder
import legado.shared.generated.resources.ic_folder_open
import legado.shared.generated.resources.ic_groups
import legado.shared.generated.resources.ic_help
import legado.shared.generated.resources.ic_history
import legado.shared.generated.resources.ic_image
import legado.shared.generated.resources.ic_import
import legado.shared.generated.resources.ic_interface_setting
import legado.shared.generated.resources.ic_layout_list
import legado.shared.generated.resources.ic_layout_video
import legado.shared.generated.resources.ic_lock_outline
import legado.shared.generated.resources.ic_menu
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.ic_outline_cloud_24
import legado.shared.generated.resources.ic_pause_24dp
import legado.shared.generated.resources.ic_play_24dp
import legado.shared.generated.resources.ic_play_mode_list_end_stop
import legado.shared.generated.resources.ic_play_mode_list_loop
import legado.shared.generated.resources.ic_play_mode_random
import legado.shared.generated.resources.ic_play_mode_single_loop
import legado.shared.generated.resources.ic_praise
import legado.shared.generated.resources.ic_read_aloud
import legado.shared.generated.resources.ic_reduce
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.ic_review_close
import legado.shared.generated.resources.ic_review_thumb_down
import legado.shared.generated.resources.ic_review_thumb_down_filled
import legado.shared.generated.resources.ic_review_thumb_up
import legado.shared.generated.resources.ic_review_thumb_up_filled
import legado.shared.generated.resources.ic_save
import legado.shared.generated.resources.ic_search
import legado.shared.generated.resources.ic_settings
import legado.shared.generated.resources.ic_share
import legado.shared.generated.resources.ic_skip_next
import legado.shared.generated.resources.ic_skip_previous
import legado.shared.generated.resources.ic_sort
import legado.shared.generated.resources.ic_star
import legado.shared.generated.resources.ic_star_border
import legado.shared.generated.resources.ic_stop_black_24dp
import legado.shared.generated.resources.ic_time_add_24dp
import legado.shared.generated.resources.ic_toc
import legado.shared.generated.resources.ic_translate
import legado.shared.generated.resources.ic_visibility_off
import legado.shared.generated.resources.ic_volume_up
import legado.shared.generated.resources.ic_web_outline
import legado.shared.generated.resources.outline_filter_alt_24
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

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
 * Android actual: 优先加载 shared/commonMain/composeResources/drawable/ 下 SVG (与 iOS/JVM/OHOS 三端一致, 单一数据源)。
 *
 * 原 Resources.getIdentifier + painterResource(Int) 模式依赖 app 模块 R.drawable XML,
 * 资源删除后运行时抛 Resources$NotFoundException; 改为 CMP SVG 加载方式后与三端对齐。
 * 未识别 key 走 Material Icons 兜底 (androidMain 继承 nonOhosUiMain, material-icons-extended 可用)。
 */
@Composable
actual fun rememberPainter(key: String): Painter {
    // 优先加载下沉到 shared/commonMain/composeResources/drawable/ 的 SVG 图标
    // (与 iOS/JVM/OHOS 三端一致, 单一数据源)
    val svgRes: DrawableResource? = when (key) {
        // 导航栏图标
        "ic_arrow_back" -> Res.drawable.ic_arrow_back
        "ic_menu" -> Res.drawable.ic_menu
        "ic_bottom_home_s" -> Res.drawable.ic_bottom_home_s
        "ic_bottom_home_e" -> Res.drawable.ic_bottom_home_e
        "ic_bottom_books_s" -> Res.drawable.ic_bottom_books_s
        "ic_bottom_books_e" -> Res.drawable.ic_bottom_books_e
        "ic_bottom_explore_s" -> Res.drawable.ic_bottom_explore_s
        "ic_bottom_explore_e" -> Res.drawable.ic_bottom_explore_e
        "ic_bottom_person_s" -> Res.drawable.ic_bottom_person_s
        "ic_bottom_person_e" -> Res.drawable.ic_bottom_person_e
        // 基础操作图标
        "ic_search" -> Res.drawable.ic_search
        "ic_more_vert" -> Res.drawable.ic_more_vert
        "ic_baseline_close" -> Res.drawable.ic_baseline_close
        "ic_clear_all" -> Res.drawable.ic_clear_all
        "ic_check" -> Res.drawable.ic_check
        "ic_add" -> Res.drawable.ic_add
        "ic_reduce" -> Res.drawable.ic_reduce
        "ic_sort" -> Res.drawable.ic_sort
        "ic_baseline_sort_24" -> Res.drawable.ic_baseline_sort_24
        "ic_groups" -> Res.drawable.ic_groups
        "ic_edit" -> Res.drawable.ic_edit
        // TocScreen 下沉带入 (TocScreen / TocDrawerContent 用到)
        "ic_lock_outline" -> Res.drawable.ic_lock_outline
        "ic_expand_more" -> Res.drawable.ic_expand_more
        "ic_expand_less" -> Res.drawable.ic_expand_less
        "ic_outline_cloud_24" -> Res.drawable.ic_outline_cloud_24
        "ic_arrow_drop_up" -> Res.drawable.ic_arrow_drop_up
        "ic_arrow_drop_down" -> Res.drawable.ic_arrow_drop_down
        "ic_arrow_down" -> Res.drawable.ic_arrow_down
        "ic_arrow_right" -> Res.drawable.ic_arrow_right
        // BookshelfComposablesShared 下沉带入 (书架列表行图标)
        "ic_author" -> Res.drawable.ic_author
        "ic_history" -> Res.drawable.ic_history
        "ic_book_last" -> Res.drawable.ic_book_last
        "ic_book_has" -> Res.drawable.ic_book_has
        "ic_bookmark" -> Res.drawable.ic_bookmark
        // 文件夹/帮助/导入 (BookshelfManageScreen / RemoteBookScreen / ReplaceEditScreen)
        "ic_folder" -> Res.drawable.ic_folder
        "ic_folder_open" -> Res.drawable.ic_folder_open
        "ic_help" -> Res.drawable.ic_help
        "ic_image" -> Res.drawable.ic_image
        "ic_export" -> Res.drawable.ic_export
        "ic_import" -> Res.drawable.ic_import
        // 书架/发现布局切换 (BookshelfScreen / ExploreScreen)
        "ic_layout_list" -> Res.drawable.ic_layout_list
        "ic_layout_video" -> Res.drawable.ic_layout_video
        // 播放/停止/刷新/筛选 (SearchScreen / ChangeSourceScreen)
        "ic_play_24dp" -> Res.drawable.ic_play_24dp
        "ic_stop_black_24dp" -> Res.drawable.ic_stop_black_24dp
        "ic_refresh_black_24dp" -> Res.drawable.ic_refresh_black_24dp
        "outline_filter_alt_24" -> Res.drawable.outline_filter_alt_24
        // Bug 反馈 (AboutScreen)
        "ic_bug_report" -> Res.drawable.ic_bug_report
        // 配置入口图标 (MyConfigScreen)
        "ic_cfg_about" -> Res.drawable.ic_cfg_about
        "ic_cfg_backup" -> Res.drawable.ic_cfg_backup
        "ic_cfg_other" -> Res.drawable.ic_cfg_other
        "ic_cfg_replace" -> Res.drawable.ic_cfg_replace
        "ic_cfg_source" -> Res.drawable.ic_cfg_source
        "ic_cfg_theme" -> Res.drawable.ic_cfg_theme
        "ic_cfg_web" -> Res.drawable.ic_cfg_web
        // 书信息 (BookInfoScreen)
        "ic_praise" -> Res.drawable.ic_praise
        "ic_save" -> Res.drawable.ic_save
        "ic_share" -> Res.drawable.ic_share
        "ic_star" -> Res.drawable.ic_star
        "ic_star_border" -> Res.drawable.ic_star_border
        "ic_translate" -> Res.drawable.ic_translate
        "ic_web_outline" -> Res.drawable.ic_web_outline
        // ReadMenu 下沉带入 (阅读页菜单图标, 对应 app 端 R.drawable.ic_*)
        "ic_exchange" -> Res.drawable.ic_exchange
        "ic_download_line" -> Res.drawable.ic_download_line
        "ic_auto_page" -> Res.drawable.ic_auto_page
        "ic_auto_page_stop" -> Res.drawable.ic_auto_page_stop
        "ic_find_replace" -> Res.drawable.ic_find_replace
        "ic_brightness" -> Res.drawable.ic_brightness
        "ic_toc" -> Res.drawable.ic_toc
        "ic_read_aloud" -> Res.drawable.ic_read_aloud
        "ic_interface_setting" -> Res.drawable.ic_interface_setting
        "ic_settings" -> Res.drawable.ic_settings
        // TtsControlPanel 下沉带入 (TTS 控制面板)
        "ic_skip_previous" -> Res.drawable.ic_skip_previous
        "ic_skip_next" -> Res.drawable.ic_skip_next
        "ic_pause_24dp" -> Res.drawable.ic_pause_24dp
        "ic_play_mode_list_end_stop" -> Res.drawable.ic_play_mode_list_end_stop
        "ic_play_mode_list_loop" -> Res.drawable.ic_play_mode_list_loop
        "ic_play_mode_random" -> Res.drawable.ic_play_mode_random
        "ic_play_mode_single_loop" -> Res.drawable.ic_play_mode_single_loop
        "ic_volume_up" -> Res.drawable.ic_volume_up
        "ic_visibility_off" -> Res.drawable.ic_visibility_off
        "ic_time_add_24dp" -> Res.drawable.ic_time_add_24dp
        // ReviewListDialog 下沉带入 (对应 app 端 R.drawable.ic_review_*)
        "ic_review_close" -> Res.drawable.ic_review_close
        "ic_review_thumb_up" -> Res.drawable.ic_review_thumb_up
        "ic_review_thumb_up_filled" -> Res.drawable.ic_review_thumb_up_filled
        "ic_review_thumb_down" -> Res.drawable.ic_review_thumb_down
        "ic_review_thumb_down_filled" -> Res.drawable.ic_review_thumb_down_filled
        else -> null
    }
    if (svgRes != null) {
        return painterResource(svgRes)
    }
    // 兜底: 仅有以下 key 无 app 端 drawable xml 对应, 仍走 Material Icons
    val imageVector = when (key) {
        "ic_arrow_forward" -> Icons.AutoMirrored.Filled.ArrowForward
        "ic_speed" -> Icons.Filled.Speed
        else -> Icons.Filled.Help
    }
    return rememberVectorPainter(imageVector)
}

@Composable
actual fun rememberString(key: String, vararg formatArgs: Any): String {
    val context = LocalContext.current
    val id = resolveResourceId(context, key, "string")
    // formatArgs 为空时走 stringResource(id) 不格式化, 与原 app 端 stringResource 行为一致;
    // 非空时走 stringResource(id, *formatArgs), 由 Android Formatter 填充占位符
    return if (formatArgs.isEmpty()) stringResource(id) else stringResource(id, *formatArgs)
}

@Composable
actual fun rememberStringArray(key: String): List<String> {
    val context = LocalContext.current
    val id = resolveResourceId(context, key, "array")
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
