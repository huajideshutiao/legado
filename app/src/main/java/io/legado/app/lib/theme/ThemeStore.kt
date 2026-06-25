package io.legado.app.lib.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.ColorInt
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import splitties.init.appCtx

/**
 * 主题存储，基于 SharedPreferences 的全局主题管理。
 * 读取时使用 [ThemeValues] 缓存，主题变更（[saveTheme]）时自动失效。
 *
 * @author Aidan Follestad (afollestad), Karim Abou Zeid (kabouzeid)
 */
object ThemeStore {

    /** 缓存的主题值，主题变更时置 null */
    @Volatile
    private var cache: ThemeValues? = null

    /**
     * 一次性保存主题颜色到 SharedPreferences，同时清除缓存。
     *
     * @param primaryColor    主色（当前与 backgroundColor 相同，保留以兼容已有 PrefKey）
     * @param accentColor     强调色
     * @param backgroundColor 背景色
     * @param bottomBackground 底栏背景色
     */
    fun saveTheme(
        @ColorInt primaryColor: Int,
        @ColorInt accentColor: Int,
        @ColorInt backgroundColor: Int,
        @ColorInt bottomBackground: Int
    ) {
        prefs().edit {
            putInt(ThemeStorePrefKeys.KEY_PRIMARY_COLOR, primaryColor)
            putInt(ThemeStorePrefKeys.KEY_ACCENT_COLOR, accentColor)
            putInt(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR, backgroundColor)
            putInt(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, bottomBackground)
        }
        cache = null
    }

    // ===== 颜色读取（带缓存） =====

    val accentColor: Int
        get() = values().accentColor

    val backgroundColor: Int
        get() = values().backgroundColor

    val bottomBackground: Int
        get() = values().bottomBackground

    val statusBarColor: Int
        get() = values().statusBarColor

    val navigationBarColor: Int
        get() = values().navigationBarColor

    val textColorPrimary: Int
        get() = values().textColorPrimary

    val textColorSecondary: Int
        get() = values().textColorSecondary

    // ===== 内部实现 =====

    /** 获取缓存的或从 SharedPreferences 加载的全部主题值 */
    private fun values(): ThemeValues {
        cache?.let { return it }
        return synchronized(this) {
            cache?.let { return it }
            val v = loadValues()
            cache = v
            v
        }
    }

    /** 从 SharedPreferences 读取全部主题值（仅缓存未命中时调用） */
    private fun loadValues(): ThemeValues {
        val p = prefs()
        val bgColor = p.getInt(
            ThemeStorePrefKeys.KEY_BACKGROUND_COLOR,
            ThemeUtils.resolveColor(appCtx, android.R.attr.colorBackground)
        )
        val bottomBgColor = p.getInt(
            ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND,
            ThemeUtils.resolveColor(appCtx, android.R.attr.colorBackground)
        )
        return ThemeValues(
            primaryColor = bgColor,
            accentColor = p.getInt(
                ThemeStorePrefKeys.KEY_ACCENT_COLOR,
                ThemeUtils.resolveColor(
                    appCtx,
                    androidx.appcompat.R.attr.colorAccent,
                    "#263238".toColorInt()
                )
            ),
            backgroundColor = bgColor,
            bottomBackground = bottomBgColor,
            statusBarColor = p.getInt(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR, bgColor),
            navigationBarColor = p.getInt(
                ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR, bottomBgColor
            ),
            textColorPrimary = p.getInt(
                ThemeStorePrefKeys.KEY_TEXT_COLOR_PRIMARY,
                ThemeUtils.resolveColor(appCtx, android.R.attr.textColorPrimary)
            ),
            textColorSecondary = p.getInt(
                ThemeStorePrefKeys.KEY_TEXT_COLOR_SECONDARY,
                ThemeUtils.resolveColor(appCtx, android.R.attr.textColorSecondary)
            )
        )
    }

    internal fun prefs(context: Context = appCtx): SharedPreferences {
        return context.getSharedPreferences(
            ThemeStorePrefKeys.CONFIG_PREFS_KEY_DEFAULT,
            Context.MODE_PRIVATE
        )
    }

    /** 一次加载的完整主题值集合，供缓存使用 */
    private data class ThemeValues(
        @ColorInt val primaryColor: Int,
        @ColorInt val accentColor: Int,
        @ColorInt val backgroundColor: Int,
        @ColorInt val bottomBackground: Int,
        @ColorInt val statusBarColor: Int,
        @ColorInt val navigationBarColor: Int,
        @ColorInt val textColorPrimary: Int,
        @ColorInt val textColorSecondary: Int
    )
}
