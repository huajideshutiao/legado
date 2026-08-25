package io.legado.app.ui.book.read

import io.legado.app.help.config.ReadStyleConfig

/**
 * 阅读菜单顶/底栏配色（对照原版 app 端 `ReadMenu.upColorConfig` 逐行等价下沉）。
 *
 * # 原版行为（app 端 ReadMenu.upColorConfig）
 *
 * - 沉浸式（阅读背景为纯色，`curBgType() == 0`）：菜单栏背景跟随阅读背景色
 *   （`curBgStr()` 解析，本 helper 额外按 `bgAlpha` 折算透明度，半透明阅读背景时
 *   菜单栏同步半透明，与原版阅读页 `upBg()` 的透明度语义一致）；
 *   文字/图标色用阅读文字色 `curTextColor()`（该色与阅读背景成对设计，对比度有保证）；
 * - 非沉浸式（阅读背景为图片，`curBgType() != 0`）：菜单栏用主题色
 *   （背景 `AppTheme.colors.background` / `bottomBackground`，文字 `primaryText`，
 *   动态主题取色时即为动态主题色。2026-08-06 曾按图片上下区域取代表色，效果不佳已移除）。
 *
 * # 与迁移版 [ReadMenuState] 的映射
 *
 * - [ReadMenuColors.immersive] → `ReadMenuState.immersive`
 * - [ReadMenuColors.bgColor] / [ReadMenuColors.textColor] →
 *   `ReadMenuState.bgColor` / `textColor`（仅 immersive 时被 [ReadMenuOverlay] 消费，
 *   非沉浸式由 Composable 直接取 AppTheme 色）
 * - `hasBgImage` 不在此 helper 输出：语义为「窗口背景图」（app 端
 *   `ThemeConfig.curBgImagePath` 非空，背景图时顶栏透明），由各平台自行判定，
 *   判定收敛见 [hasBgImageByPath]（Android 用 `ThemeConfig.curBgImagePath`，
 *   与 `LocalThemeStoreProvider.current.bgImagePath` 同一数据源；桌面/iOS/鸿蒙
 *   无窗口背景图概念，[ReadMenuState.hasBgImage] 显式传 false）。
 */
data class ReadMenuColors(
    /** 菜单栏是否跟随阅读背景（纯色阅读背景；图片背景为 false，回落主题色）。 */
    val immersive: Boolean,
    /** 顶栏背景色：纯色=阅读背景色（含 bgAlpha 透明度）。 */
    val bgColor: Int,
    /** 顶栏文字/图标色：纯色=阅读文字色。 */
    val textColor: Int,
)

/**
 * 计算阅读菜单顶/底栏配色（对照原版 `ReadMenu.upColorConfig`）。
 *
 * - 纯色阅读背景（[ReadStyleConfig.curBgType] == 0）：顶/底栏统一用阅读背景色 +
 *   阅读文字色（原版沉浸式语义）
 * - 图片阅读背景（curBgType != 0）：返回非沉浸式（immersive=false），控制层由
 *   [ReadMenuOverlay] 直接取 AppTheme 主题色（背景图不再参与控制层取色）
 *
 * @param config 当前生效阅读配置（传 `ReadBookConfigProviders.get().config`，
 *   shareLayout 感知，与阅读页正文 `ReaderDrawStyle` 用同一 config 源）
 * @param fallbackBgColor 沉浸式解析失败时的主题底栏背景色（对照
 *   `ReadMenu.upColorConfig` 的 `getOrDefault(appCtx.bottomBackground)` 兜底语义）：
 *   四端统一传 `AppTheme.colors.bottomBackground.toArgb()`（原 Android 传
 *   `activity.bottomBackground`，桌面/iOS/鸿蒙传 0 的历史差异已对齐）。
 */
fun createReadMenuColors(config: ReadStyleConfig, fallbackBgColor: Int): ReadMenuColors {
    // 图片背景: 控制层回落主题色 (immersive=false, ReadMenuOverlay 走 AppTheme 色分支)
    if (config.curBgType() != 0) {
        return ReadMenuColors(immersive = false, bgColor = 0, textColor = 0)
    }
    // 纯色背景: curBgColor() 已按 bgAlpha 折算透明度; 解析失败回落主题色
    // (对照原版 runCatching { curBgStr().toColorInt() }.getOrDefault)
    val bg = config.curBgColor()
    return ReadMenuColors(
        immersive = true,
        bgColor = if (bg != 0) bg else fallbackBgColor,
        textColor = config.curTextColor(),
    )
}

/**
 * 统一「窗口背景图」判定（[ReadMenuState.hasBgImage] 的共享推导源）：
 * 背景图路径非空即视为设置了窗口背景图（背景图时顶栏透明，让背景图透出）。
 *
 * 四端统一走本推导, 数据源与 LegadoApp 壁纸层同一份 bgImagePath:
 * - app(Android): `ThemeConfig.curBgImagePath`（Android actual 包装的同一持久层）;
 * - 桌面/iOS/鸿蒙: 各端 ThemeStoreProvider actual 的 bgImagePath。
 */
fun hasBgImageByPath(bgImagePath: String?): Boolean = !bgImagePath.isNullOrBlank()
