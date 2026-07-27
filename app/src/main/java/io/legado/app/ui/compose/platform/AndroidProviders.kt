package io.legado.app.ui.compose.platform

import androidx.compose.ui.graphics.Color
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.utils.FlowBus
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
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
}

/** 包装 [AppConfig.isEInkMode] */
class AndroidAppConfigProvider : AppConfigProvider {
    override val isEInkMode: Boolean
        get() = AppConfig.isEInkMode
}

/** 包装 [FlowBus.with]`[EventBus.RECREATE]`，把 SharedFlow<Any> 投影为 Flow<Unit> */
class AndroidEventBusProvider : EventBusProvider {
    override val recreateEvent: Flow<Unit> =
        FlowBus.with(EventBus.RECREATE).map { Unit }
}

/**
 * Android 端 PreferenceStoreProvider：包装 app 模块的 Context.getPrefX/putPrefX
 * (底层 defaultSharedPreferences)。用 splitties 的 [appCtx] 取 Application Context，
 * 无参构造，与 [AndroidThemeStoreProvider] 一致模式。
 *
 * 供已下沉 commonMain 的 Preferences/ColorPicker 等 B 类 Composable 调用
 * (替代原 LocalContext.current + context.getPrefX)。
 */
class AndroidPreferenceStoreProvider : PreferenceStoreProvider {
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        appCtx.getPrefBoolean(key, defValue)

    override fun putBoolean(key: String, value: Boolean) =
        appCtx.putPrefBoolean(key, value)

    override fun getInt(key: String, defValue: Int): Int =
        appCtx.getPrefInt(key, defValue)

    override fun putInt(key: String, value: Int) =
        appCtx.putPrefInt(key, value)

    override fun getString(key: String, defValue: String?): String? =
        appCtx.getPrefString(key, defValue)

    override fun putString(key: String, value: String?) =
        appCtx.putPrefString(key, value)
}
