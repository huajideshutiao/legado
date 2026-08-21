package io.legado.app.ui.compose.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Colors
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.ThemeStoreProvider
import io.legado.app.ui.compose.platform.platformTextStyleNoFontPadding

/**
 * E-Ink 模式标记，供组件去动画/黑白化。
 *
 * default=false：commonMain 无法访问 [io.legado.app.help.config.AppConfig]，
 * 实际值在 [AppTheme] 内通过 [LocalAppConfigProvider] provides；未包在 AppTheme
 * 内的子树用 false 兜底（与 Android 端非 E-Ink 模式行为一致）。
 */
val LocalEInk = compositionLocalOf { false }

val LocalAppColors = staticCompositionLocalOf<AppColors> {
    error("AppColors not provided, wrap content in AppTheme")
}

object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current

    /**
     * 主题静态设计变量集中地: 与主题色无关的全局常量。各端 (app/desktop/ios/ohos)
     * 直接复用, 严禁在文件内重复 private val 定义同名常量。
     *
     * 取值对齐 Arco Design 规范 + app/res/values/dimens.xml (arco_radius_*)。
     * 动态主题色走 [AppTheme.colors], 此处仅放静态兜底值。
     */
    object DesignTokens {
        // Arco 圆角三档 (对齐 dimens.xml arco_radius_sm/default/lg)
        val radiusSm: Dp = 4.dp
        val radiusDefault: Dp = 8.dp
        val radiusLg: Dp = 16.dp

        // 预建形状三档, 调用方直接复用, 不再各处 new RoundedCornerShape
        val shapeSm: RoundedCornerShape = RoundedCornerShape(radiusSm)
        val shapeDefault: RoundedCornerShape = RoundedCornerShape(radiusDefault)
        val shapeLg: RoundedCornerShape = RoundedCornerShape(radiusLg)

        // 语义别名: 整屏对话框 16dp / 卡片弹层 8dp / 按钮 8dp / 输入框 4dp
        val dialogShape: RoundedCornerShape = shapeLg
        val cardShape: RoundedCornerShape = shapeDefault
        val buttonShape: RoundedCornerShape = shapeDefault
        val inputShape: RoundedCornerShape = shapeSm

        // 对话框尺寸占锚点 (桌面=主窗口, 移动端=屏幕) 的比例; 消费方见 AppDialogSizes。
        // 高度 0.7 为全局统一值 (用户 2026-08-20 拍板), 新弹窗一律取这里而非自行乘系数
        const val dialogWidthFraction: Float = 0.9f
        const val dialogHeightFraction: Float = 0.7f

        // Arco 描边: thin/medium 对齐 arco_stroke_width_*, hairline 为极细描边
        val strokeHairline: Dp = 0.5.dp
        val strokeThin: Dp = 1.dp
        val strokeMedium: Dp = 2.dp

        // Arco 视图高度六档 (对齐 dimens.xml arco_view_height_mini/small/default/large/xl/max)
        val viewHeightMini: Dp = 24.dp
        val viewHeightSmall: Dp = 28.dp
        val viewHeightDefault: Dp = 32.dp
        val viewHeightLarge: Dp = 40.dp
        val viewHeightXl: Dp = 48.dp
        val viewHeightMax: Dp = 56.dp

        // 响应式布局断点 (对齐 Android 官方 Window size class / Material 3 断点:
        // Compact <600dp / Medium 600-839dp / Expanded ≥840dp / Large ≥1200dp)。
        // 全项目"宽屏/大屏"判断统一引用此处, 禁止各处另写字面量。
        val wideScreenMinWidth: Dp = 600.dp // 宽屏/平板分界 (官方 Compact/Medium 分界):
        // 主界面 NavRail 方位切换、音频页右侧面板/封面歌词并排、视频播放页手机 vs 平板布局

        val expandedScreenMinWidth: Dp = 840.dp // 大屏分界 (官方 Medium/Expanded 分界), 备用

        val responsiveColumnsReferenceWidth: Dp = 400.dp // 自动分列折算起点 (用户列数 N 定义在
        // 此宽度下; 容器更宽按比例加列, 低于此宽度严格按用户列数) —— 换算基准, 非宽屏分界

        val sidePanelMaxWidth: Dp = 400.dp // 宽屏右侧面板宽度上限 (音频页评论/目录面板:
        // 宽度 = 容器宽 × 0.35, 封顶避免大窗口面板过宽)

        val audioCoverAreaMaxWidth: Dp = 600.dp // 音频页并排封面区宽度上限 (宽度 = 容器宽 × 0.35,
        // 封顶 600dp, 用户拍板: 封面区比右侧面板更宽, 容纳 300dp 大封面)

        // Arco arcoblue-6 主色 (#165DFF), 仅作无主题色场景的兜底强调色
        val arcoBlue6: Color = Color(0xFF165DFF)

        // Arco red-6 危险色 (= app @color/arco_danger)
        val arcoDanger: Color = Color(0xFFF53F3F)

        /**
         * 默认文本对齐 View TextView: 14sp/零字距。压制 MaterialTheme body 默认
         * (16sp/行高/0.5 字距)——它会把所有只设 fontSize 的 Text 撑高。
         * includeFontPadding=false 同原 XML 各处显式声明。
         *
         * platformStyle 走 [platformTextStyleNoFontPadding] expect/actual。
         */
        val defaultTextStyle: TextStyle = TextStyle(
            fontSize = 14.sp,
            platformStyle = platformTextStyleNoFontPadding(),
        )

        /**
         * 风格锁定 MD2: 全档小圆角。普通控件 4dp 系, dialog/按钮对齐 filletBackground 实际半径 8dp。
         * 调用方应使用 [AppTheme.DesignTokens.shapes] 而非自行 new Shapes。
         */
        val shapes: Shapes = Shapes(
            small = shapeSm,
            medium = shapeDefault,
            large = shapeDefault,
        )
    }
}

/**
 * 从 [ThemeStoreProvider] 读当前主题色。primaryText/secondaryText/isDark 复刻
 * MaterialValueHelper「背景色亮度反推」语义(旧 isDarkTheme 命名反转，实为背景是浅色)。
 *
 * 用 [Color.luminance] 替代 Android 专属 [io.legado.app.utils.ColorUtils.isColorLight]
 * (后者依赖 androidx.core.graphics.ColorUtils)，commonMain 直接消费 Compose Color。
 */
private fun readAppColors(themeStore: ThemeStoreProvider): AppColors {
    val bg = themeStore.backgroundColor
    val bgIsLight = bg.luminance() >= 0.5f
    val bottomBg = themeStore.bottomBackground
    return AppColors(
        accent = themeStore.accentColor,
        background = bg,
        bottomBackground = bottomBg,
        // 对齐 origin/quickjs arco_text_1: light #212121 / dark #F8F8F8
        primaryText = if (bgIsLight) Color(0xFF212121) else Color(0xFFF8F8F8),
        // 对齐 origin/quickjs arco_text_2: light #595959 / dark #CDCDCD
        secondaryText = if (bgIsLight) Color(0xFF595959) else Color(0xFFCDCDCD),
        // 主要菜单正文沿用 primaryText；摘要对齐 tv_text_summary/arco_text_3
        menuText = if (bgIsLight) Color(0xFF212121) else Color(0xFFF8F8F8),
        summaryText = Color(0xFF909090),
        // 对齐 ate_control_normal_light(#8A000000)/dark(#B3FFFFFF)
        controlNormal = if (bgIsLight) Color(0x8A000000) else Color(0xB3FFFFFF),
        // 对齐 ate_text_disabled_light(#61000000)/dark(#4DFFFFFF)
        textDisabled = if (bgIsLight) Color(0x61000000) else Color(0x4DFFFFFF),
        statusBar = themeStore.statusBarColor,
        navigationBar = themeStore.navigationBarColor,
        fillet = bottomBg,
        isDark = !bgIsLight,
    )
}

/** AppColors → M2 Colors，供 material 内置组件取色 */
private fun AppColors.toColors(): Colors {
    val base = if (isDark) darkColors() else lightColors()
    return base.copy(
        primary = accent,
        onPrimary = if (accent.luminance() >= 0.5f) Color(0xDE000000) else Color(0xFFFFFFFF),
        secondary = accent,
        background = background,
        onBackground = primaryText,
        surface = background,
        onSurface = primaryText,
    )
}

/**
 * Compose 主题入口：提供 AppTheme.colors / LocalEInk，并映射 MaterialTheme。
 *
 * 依赖通过 CompositionLocal 注入（[LocalThemeStoreProvider] / [LocalAppConfigProvider]
 * / [LocalEventBusProvider]），各平台在 Compose 入口用 CompositionLocalProvider
 * 注入 actual 实例。观察 [EventBusProvider.recreateEvent] 即时刷新颜色 State——变色即
 * 重组，不依赖 Activity recreate。
 *
 * @see io.legado.app.ui.compose.platform
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val themeStore = LocalThemeStoreProvider.current
    val appConfig = LocalAppConfigProvider.current
    val eventBus = LocalEventBusProvider.current

    var recreateTick by remember(themeStore, eventBus) { mutableIntStateOf(0) }
    // Native providers 从持久层读取普通值，事件到达时触发重组；Desktop 的 State getter 可直接触发。
    LaunchedEffect(themeStore, eventBus) {
        eventBus.recreateEvent.collect {
            recreateTick++
        }
    }
    recreateTick
    val colors = readAppColors(themeStore)
    val textToolbar = remember { ComposeTextToolbar() }
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalEInk provides appConfig.isEInkMode,
        LocalTextToolbar provides textToolbar,
    ) {
        MaterialTheme(
            colors = colors.toColors(),
            shapes = AppTheme.DesignTokens.shapes,
        ) {
            // 直接 provides 整体替换(ProvideTextStyle 是 merge, 压不掉默认 body 样式的行高/字距)
            CompositionLocalProvider(LocalTextStyle provides AppTheme.DesignTokens.defaultTextStyle) {
                content()
                // 自绘文本选择菜单宿主：全局接管系统 ActionMode，逐界面零改动
                textToolbar.Host()
            }
        }
    }
}
