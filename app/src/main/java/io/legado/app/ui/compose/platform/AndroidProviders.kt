package io.legado.app.ui.compose.platform

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.legado.app.App
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.utils.FlowBus
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.hexString
import io.legado.app.utils.postEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Android 端 Provider 实现：包装 app 模块的 ThemeStore / AppConfig / FlowBus +
 * ThemeConfig.curBgImagePath，作为 [io.legado.app.ui.compose.theme.AppTheme]（已下沉
 * commonMain）在 Android 端运行时的依赖注入源。
 *
 * 设计：shared androidMain 不能反向 import app 模块类（app 依赖 shared），故 actual
 * 实现下沉到 app 模块；在 Compose 入口用 CompositionLocalProvider 注入到
 * LocalThemeStoreProvider / LocalAppConfigProvider / LocalEventBusProvider。
 *
 * 颜色 Int→Color 转换在此完成，commonMain 侧消费 Color 不再依赖 Android ColorInt。
 */

/** 包装 [ThemeStore] 颜色字段 + [ThemeConfig.curBgImagePath] */
class AndroidThemeStoreProvider : ThemeStoreProvider {
    override val accentColor: Color
        get() = Color(ThemeStore.accentColor)
    override val backgroundColor: Color
        get() = Color(ThemeStore.backgroundColor)
    override val bottomBackground: Color
        get() = Color(ThemeStore.bottomBackground)
    override val statusBarColor: Color
        get() = Color(ThemeStore.statusBarColor)
    override val navigationBarColor: Color
        get() = Color(ThemeStore.navigationBarColor)
    override val bgImagePath: String?
        get() = ThemeConfig.curBgImagePath

    /** 按日/夜读背景图模糊键 (对照原版 bgImageBlurring, 页面级壁纸层共用) */
    override val bgImageBlur: Int
        get() = App.instance.getPrefInt(
            if (AppConfig.isNightTheme) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring,
            0,
        )

    /** 包装 ThemeConfig.applyConfig + postEvent(RECREATE) (Color→Config 转换在此完成) */
    override fun applyColors(accent: Color, bg: Color, bbg: Color, isNight: Boolean) {
        val config = ThemeConfig.Config(
            themeName = "",
            isNightTheme = isNight,
            primaryColor = "#${bg.toArgb().hexString}",
            accentColor = "#${accent.toArgb().hexString}",
            backgroundColor = "#${bg.toArgb().hexString}",
            bottomBackground = "#${bbg.toArgb().hexString}",
        )
        ThemeConfig.applyConfig(App.instance, config)
    }
}

/** 包装 [AppConfig.isEInkMode] */
class AndroidAppConfigProvider : AppConfigProvider {
    override val isEInkMode: Boolean
        get() = AppConfig.isEInkMode
    override val isNightTheme: Boolean
        get() = AppConfig.isNightTheme
}

/** 包装 [FlowBus.with]`[EventBus.RECREATE]`，把 SharedFlow<Any> 投影为 Flow<Unit> */
class AndroidEventBusProvider : EventBusProvider {
    override val recreateEvent: Flow<Unit> =
        FlowBus.with(EventBus.RECREATE).map { Unit }

    /** 包装 postEvent(EventBus.RECREATE, "") */
    override fun emitRecreate() {
        postEvent(EventBus.RECREATE, "")
    }
}

