package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_material_help
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * 跨平台资源访问器。
 *
 * 字符串/字符串数组四端统一走 Compose Resources 按 key 索引的映射表 (见 [findStringResource]);
 * Painter/Color 仍是 expect/actual: 图标源在 Android 走 AAPT 原生 drawable、其余端走 Compose
 * Resources (见 shared/build.gradle.kts 的 nonAndroidIconMain customDirectory), 颜色则无
 * Compose Resources 对应 API。
 *
 * 调用方用 [rememberPainter] / [rememberString] / [rememberColor] 替代
 * painterResource/stringResource/colorResource, 由 B 类 Composable 下沉时统一调用。
 *
 * ## 支持的 key (来源: app 端 B 类 Composable 实际使用清单)
 *
 * ### Painter key (drawable)
 * - `ic_arrow_back`     返回箭头 (AppTitleBar / DialogTitleBar)
 * - `ic_search`         搜索图标 (AppTitleBar)
 * - `ic_more_vert`      更多菜单 (AppTitleBar / SelectActionBar)
 * - `ic_baseline_close` 关闭按钮 (AppTitleBar)
 * - `ic_clear_all`      清除全部 (AppAutoCompleteField)
 * - `ic_check`          勾选 (ColorPicker / TocScreen)
 * - `ic_add`            增加 (AppSlider)
 * - `ic_reduce`         减少 (AppSlider)
 * - `ic_sort`           排序/反转 (TocDrawerContent)
 * - `ic_lock_outline`   VIP 锁 (TocScreen)
 * - `ic_expand_more`    展开箭头 (TocScreen 卷折叠)
 * - `ic_expand_less`    折叠箭头 (TocScreen 卷折叠)
 * - `ic_outline_cloud_24` 云朵 (TocScreen 未缓存)
 * - `ic_arrow_drop_up`  顶部箭头 (TocScreen 底栏)
 * - `ic_arrow_drop_down` 底部箭头 (TocScreen 底栏)
 * - `ic_author`         作者图标 (BookshelfComposablesShared 书架列表行)
 * - `ic_history`        历史图标 (BookshelfComposablesShared 阅读进度行)
 * - `ic_book_last`      最新章节图标 (BookshelfComposablesShared 最新章节行)
 * - `ic_review_close`   评论对话框关闭按钮 (ReviewListDialog)
 * - `ic_review_thumb_up` / `ic_review_thumb_up_filled` 评论点赞图标 (ReviewListDialog)
 * - `ic_review_thumb_down` / `ic_review_thumb_down_filled` 评论点踩图标 (ReviewListDialog)
 *
 * ### String key (string)
 * - `ok`                确定
 * - `cancel`            取消
 * - `reduce`            减少 (AppSlider contentDescription)
 * - `plus`              增加 (AppSlider contentDescription)
 * - `clear`             清除 (AppTitleBar contentDescription)
 * - `more_menu`         更多菜单 (AppTitleBar / SelectActionBar contentDescription)
 * - `empty`             空状态文案 (RuleManageScaffold / EffectiveReplacesScreen)
 * - `revert_selection`  反选 (SelectActionBar)
 * - `open`              打开 (ImportListScaffold)
 * - `select_all_count`             全选（%1$d/%2$d）(ImportListScaffold, 带 formatArgs)
 * - `select_cancel_count`          取消全选（%1$d/%2$d）(ImportListScaffold, 带 formatArgs)
 * - 阅读更多设置/朗读设置相关 key: 见 MoreConfigScreen / ReadAloudConfigScreen
 * - TocScreen 相关 key: `search` / `chapter_list` / `bookmark` / `export` / `export_md` /
 *   `txt_toc_rule` / `split_long_chapter` / `reverse_toc` /
 *   `use_replace` / `load_word_count` / `log` / `go_to_top` / `go_to_bottom`
 * - TipConfigScreen 相关 key: `body_title` / `title_left` / `title_center` / `title_hide` /
 *   `title_font_size` / `title_margin_top` / `title_margin_bottom` / `header` / `footer` /
 *   `header_footer` (值含 `&`) / `show_hide` / `left` / `middle` / `right` /
 *   `text_color` / `tip_divider_color` / `show` / `hide` / `hide_when_status_bar_show`
 * - PaddingConfigScreen 相关 key: `main_body` / `showLine` / `padding_top` /
 *   `padding_bottom` / `padding_left` / `padding_right` (复用 `header` / `footer`)
 * - EffectiveReplacesScreen 相关 key: `effective_replaces` / `add` / `close` /
 *   `source_filter_rule_manage` (复用 `empty` / `ic_add` Painter key)
 * - BookshelfComposablesShared 相关 key: `bookshelf` / `bookshelf_empty` / `book_local` /
 *   `add_url` / `add_book_url` / `force_refresh_book` / `select_file` / `add_remote_book`
 *   (复用 `search` / `group_manage` / `bookshelf_management` / `import_bookshelf` / `log`)
 *
 * ### Color key (color)
 * - `primaryText`       主文本色 (= arco_text_1: light #FF212121 / dark #FFF8F8F8) (Preferences)
 * - `tv_text_summary`   次文本色 (= arco_text_3: #FF909090 light/dark 相同) (Preferences)
 * - `btn_bg`            按钮背景色 (= btn_bg: light #100e0e0e / dark #14e0e0e0) (TocScreen 卷名背景)
 *
 * 各平台未识别的 key:
 * - Painter: 返回占位图标 `ic_material_help`
 * - String: 返回 key 本身
 * - Color: 返回 Color.Unspecified (调用方应保证 key 命中, 否则不绘制)
 */
@Composable
fun rememberPainter(key: String): Painter {
    val resource = findDrawableResource(key) ?: Res.drawable.ic_material_help
    return painterResource(resource)
}

/**
 * 取字符串资源; 四端统一走 Compose Resources 按 key 索引的映射表 ([findStringResource])。
 * - formatArgs 为空时不做格式化, 保留原始 %1$d 等占位符
 * - formatArgs 非空时由 Compose Resources 填充, 但它只认索引式 %1$s/%1$d;
 *   无索引的 %s/%d 会原样留下, 故带参调用的文案必须写成索引式
 * - key 缺失返回 key 本身
 */
@Composable
fun rememberString(key: String, vararg formatArgs: Any): String {
    val resource = findStringResource(key) ?: return key
    return if (formatArgs.isEmpty()) stringResource(resource)
    else stringResource(resource, *formatArgs)
}

/**
 * 跨平台 string-array 资源访问; 四端统一走 [findStringArrayResource]。
 * key 缺失返回空 List (调用方按需处理空态)。
 *
 * ## 支持的 key (来源: app 端 B 类 Composable 实际使用清单)
 *
 * ### StringArray key (string-array)
 * - `language` / `language_value`                语言选项 (OtherConfigScreen)
 * - `default_home_page` / `default_home_page_value`  默认主页 (OtherConfigScreen)
 * - `default_app_variant` / `default_app_variant_value`  默认变体 (OtherConfigScreen)
 * - `screen_direction_title` / `screen_direction_value`  屏幕方向 (MoreConfigScreen)
 * - `screen_time_out` / `screen_time_out_value`          屏幕超时 (MoreConfigScreen)
 * - `double_page_title` / `double_page_value`            双页模式 (MoreConfigScreen)
 * - `progress_bar_behavior_title` / `progress_bar_behavior_value`  进度条行为 (MoreConfigScreen)
 * - `read_tip`              tip 信息位名称 (TipConfigScreen: 无/书名/标题/时间/电量/...)
 * - `tip_color`             tip 颜色名称 (TipConfigScreen: 跟随内容/自定义)
 * - `tip_divider_color`     tip 分隔线颜色名称 (TipConfigScreen: 默认/跟随内容/自定义)
 */
@Composable
fun rememberStringArray(key: String): List<String> {
    val resource = findStringArrayResource(key) ?: return emptyList()
    return stringArrayResource(resource)
}

/**
 * 加载 Launcher 图标预览 Painter 列表 (ThemeConfigScreen 换图标用)。
 *
 * 替代 app 端 `LocalContext.current + getIdentifier("mipmap") + getCompatDrawable + toBitmap + BitmapPainter` 链。
 * 各平台 actual 行为:
 * - Android: 按 mipmap 资源名查 id, AdaptiveIconDrawable 转 Bitmap 后包 BitmapPainter
 *   (painterResource 不支持 AdaptiveIconDrawable); 找不到返回 null
 * - 桌面 JVM / iOS: 无 launcher 图标概念, 返回空 List (调用方按需处理空态)
 *
 * @param iconValues mipmap 资源名列表 (如 ["ic_launcher", "ic_launcher_book", ...])
 */
@Composable
expect fun rememberLauncherIconPainters(iconValues: List<String>): List<Painter?>

@Composable
expect fun rememberColor(key: String): Color
