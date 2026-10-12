package io.legado.app.help.config

import kotlin.concurrent.Volatile

/**
 * "用户手动改色"的通知出口 (commonMain)。
 *
 * 主题定制对话框在 `MODE_EDIT_PREFS` 模式下按下确定 (`ThemeCustomizeDialog.saveToPrefs`) 是
 * "用户自己定了这套颜色"这条语义的唯一提交点: 当前模式 (日/夜) 的一组自定义色 pref 落盘, 随后按
 * 当前主题模式刷新界面。
 * 选预设/应用内置主题走另一条路 (`ThemeConfigProvider` 的 `applyConfig`/`applyBuiltin`), 不发本通知。
 *
 * # 为什么需要抽象
 *
 * 安卓端的"跟随壁纸"机制 (`io.legado.app.lib.theme.MonetColorExtract`) 在跟随时挂着系统壁纸
 * 颜色监听, 壁纸一变就重派生并重写同一批颜色 pref; 用户手动改色必须同时拆掉该机关, 否则刚定的
 * 颜色会被下一次壁纸变化拽回壁纸色。该机制依赖 Android 专属的 WallpaperManager, 进不了
 * commonMain, 故把"用户手动改色"这一条通知抽成契约, 由有该机制的端注册实现。
 *
 * # 未注册 = 该端无跟随壁纸机制
 *
 * 取实现走 [ManualThemeColorChangeProviders.getOrNull]: 返回 null 表示该端不存在需要拆除的跟随
 * 机关, 调用方直接跳过。不给默认空实现兜底 —— 空实现会把"宿主忘了注册"和"该端本来没有该机制"
 * 表现成同一件事。
 *
 * 模式参考 [io.legado.app.api.controller.ReadBookStateProviders] (允许不注册的 provider)。
 */
interface ManualThemeColorChangeProvider {

    /**
     * 用户已手动改色 (当前模式的自定义色 pref 即将落盘)。
     *
     * 实现应在颜色落盘前拆掉本端的跟随机关 (注销壁纸监听 + 跟随开关落 false); 必须幂等 ——
     * 未处于跟随时调用不产生任何效果, 也不改动颜色 pref。
     */
    fun onManualColorChange()
}

/**
 * [ManualThemeColorChangeProvider] 容器 (provider 注入模式, 宿主启动早期注册一次)。
 *
 * 只有安卓端注册 (实现调 `MonetColorExtract.abandonFollowIfActive`); 桌面/iOS/鸿蒙没有跟随
 * 壁纸机制, 不注册, [getOrNull] 返回 null。
 *
 * 模式参考 [io.legado.app.api.controller.ReadBookStateProviders]。
 */
object ManualThemeColorChangeProviders {

    @Volatile
    private var impl: ManualThemeColorChangeProvider? = null

    /** 宿主启动早期注册一次 (可选, 无跟随壁纸机制的端不注册)。 */
    fun register(impl: ManualThemeColorChangeProvider) {
        this.impl = impl
    }

    /** 获取已注册实现; 未注册返回 null (该端无跟随壁纸机制, 调用方跳过)。 */
    fun getOrNull(): ManualThemeColorChangeProvider? = impl
}
