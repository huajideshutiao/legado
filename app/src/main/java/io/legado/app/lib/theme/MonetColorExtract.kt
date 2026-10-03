package io.legado.app.lib.theme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeContent
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.config.currentEInkMode

/**
 * 莫奈取色 (Android 12+): 取系统壁纸主色作种子, 经 material 库内置的 Google 官方
 * material-color-utilities (com.google.android.material.color.utilities) 派生日/夜两组
 * SchemeContent 色板, 映射写入现有主题色 8 键, 走既有刷新管线生效。
 *
 * 取色即默认跟随壁纸变化: [applyFromWallpaper] 派生后注册 OnColorsChangedListener,
 * 壁纸变化回调内重派生; 手动改色/选预设经 [abandonFollowIfActive] 接管拆除跟随
 * (对照 legadoT WallpaperSeed 的验证过的护栏, 刷新适配本仓库 Compose recreate 重组管线)。
 *
 * 映射依据 (拍板): accent←scheme.primary, background←scheme.surface,
 * bottomBackground←scheme.surfaceContainer, cPrimary←与 background 同值
 * (对齐 ThemeConfig.saveCustomTheme / ThemeCustomizeDialog 的 cPrimary=背景色 现状语义)。
 * 文字色不落盘, 仍由 AppTheme.readAppColors 按背景亮度反推; eInk 读取层强制白底黑字,
 * 与本类写入的键值无关。
 *
 * 壁纸取不到 (无壁纸主色或系统异常) 时: 回调 onWallpaperMissing (调用方 toast),
 * 不写任何 pref、不挂监听、不触发刷新。
 */
@Suppress("RestrictedApi")
object MonetColorExtract {

    private val dynamicColors = MaterialDynamicColors()

    /** 壁纸取色 API (getWallpaperColors / OnColorsChangedListener 注册) 仅 Android 12+ (S=31) 可用。 */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * App 级监听单实例;非 null 表示当前已注册,是防重注册的唯一判据。
     * 类型故意存为 [Any]:[WallpaperManager.OnColorsChangedListener] 本身是 API 27+ 类型,
     * 而本 object 在所有 API 等级 (minSdk 24) 无条件加载——采用标准 Any?-holder 惯例,
     * 不在无条件加载的类里让高版本专属类型出现在字段签名中,只在
     * [registerListener]/[unregisterListener] (均 @RequiresApi(S) 且外层已做 SDK_INT 门)
     * 内部按需强转。
     */
    @Volatile
    private var listener: Any? = null

    /**
     * 防刷新风暴:记录上次成功应用的种子值。壁纸变化回调可能高频触发(系统无去重),
     * 每次触发都是 8 次持久化写 + 全局重组;等值守卫拦截同种子重复回调。
     */
    @Volatile
    private var lastAppliedSeed: Int? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private data class SchemeColors(
        val primary: Int,
        val surface: Int,
        val surfaceContainer: Int,
    )

    /**
     * 取当前壁纸色并默认进入跟随: 派生写 8 键后注册壁纸变化监听, 壁纸一变自动重派生。
     * @param onWallpaperMissing 壁纸主色取不到时的回调 (调用方负责 toast), 此时状态零改动
     */
    fun applyFromWallpaper(context: Context, onWallpaperMissing: () -> Unit) {
        val seed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            readWallpaperSeed(context)
        } else {
            null
        }
        if (seed == null) {
            onWallpaperMissing()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PreferenceProviders.get().putBoolean(PreferKey.monetWallpaperFollow, true)
            applySeed(context, seed)
            lastAppliedSeed = seed
            registerListener(context)
        }
    }

    /**
     * 手动改色/选预设/应用内置主题前拆除跟随机关 (对照 WallpaperSeed.abandonFollowIfActive):
     * 这些路径不经本类,若跟随 pref 仍为 true,壁纸一变残留回调就会把刚选的颜色拽回壁纸色。
     * 幂等:跟随 pref 已为 false 时直接返回,不产生递归写入;不触碰主题色 8 键。
     * 调用点:app ThemeConfig 的 saveCustomTheme/applyConfig/applyBuiltin (Android 手动改色
     * 全部入口,均在用户交互路径上,启动/日夜切换不经过)。
     */
    fun abandonFollowIfActive(context: Context) {
        if (!PreferenceProviders.get().getBoolean(PreferKey.monetWallpaperFollow, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            unregisterListener(context)
        }
        PreferenceProviders.get().putBoolean(PreferKey.monetWallpaperFollow, false)
        lastAppliedSeed = null
    }

    /**
     * 冷启动恢复: 跟随标记 (monetWallpaperFollow) 为 true 则重新挂监听 (App.onCreate 主线程调用)。
     * 只补挂监听,不重复派生 —— 8 键已持久化,重复 applySeed 只会多一次全局重组
     * (对照 WallpaperSeed.restoreListenerIfNeeded)。
     */
    fun restoreListenerIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!PreferenceProviders.get().getBoolean(PreferKey.monetWallpaperFollow, false)) return
        registerListener(context)
    }

    /**
     * 备份恢复完成后按恢复后的偏好重挂/拆监听 (RestoreShared 完成钩子内调用)。
     *
     * 恢复直写 prefs 不经本类, 若不在同轮把进程内监听注册态对齐到新 pref, 残留监听
     * 会在壁纸变化时把恢复后的主题色拽回壁纸色 (pref 已是新值, 回调内重读 pref 拦不住)。
     * 与 [restoreListenerIfNeeded] 同源逻辑, 只是额外处理"新 pref 为 false 而监听仍在"。
     */
    @MainThread
    fun syncFollowAfterRestore(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (PreferenceProviders.get().getBoolean(PreferKey.monetWallpaperFollow, false)) {
            registerListener(context)
        } else {
            unregisterListener(context)
            lastAppliedSeed = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @MainThread
    private fun registerListener(context: Context) {
        // 监听单实例:已有实例直接返回,进程内至多一个监听。
        // @MainThread 约束 + @Volatile 保证 listener 可见性,不需额外同步。
        if (listener != null) return
        val appContext = context.applicationContext
        val l = WallpaperManager.OnColorsChangedListener { colors, which ->
            // 来源守卫:用户已关跟随 (或手动改色/选预设触发 abandonFollowIfActive) 后,
            // 残留回调不得把颜色拽回壁纸色 —— 以持久化跟随 pref 为唯一真源,重读而非缓存。
            if (!PreferenceProviders.get().getBoolean(PreferKey.monetWallpaperFollow, false)) {
                return@OnColorsChangedListener
            }
            if (colors == null) return@OnColorsChangedListener
            if (which and WallpaperManager.FLAG_SYSTEM == 0) return@OnColorsChangedListener
            val seed = colors.primaryColor.toArgbInt()
            // 等值守卫:同种子回调直接返回,防高频回调引发 8 键重复写 + 全局刷新风暴。
            if (seed == lastAppliedSeed) return@OnColorsChangedListener
            // applySeed 内含 applyThemeMode → 全局重组刷新,须在主线程 ——
            // 监听注册时指定 mainHandler 派发,天然满足。
            applySeed(appContext, seed)
            lastAppliedSeed = seed
        }
        // 整体包 try/catch: 本方法在 App.onCreate 冷启动路径上, 系统服务异常不得变成
        // 每次启动必崩的启动失败 (取不到监听只是不跟随壁纸, 不是致命错误)。
        try {
            WallpaperManager.getInstance(appContext)
                .addOnColorsChangedListener(l, mainHandler)
            listener = l
        } catch (e: Exception) {
            AppLog.put("壁纸颜色监听注册失败: ${e.message}", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @MainThread
    private fun unregisterListener(context: Context) {
        val l = listener ?: return
        try {
            WallpaperManager.getInstance(context.applicationContext)
                .removeOnColorsChangedListener(l as WallpaperManager.OnColorsChangedListener)
        } catch (_: Exception) {
        }
        listener = null
    }

    /** 取系统壁纸 (FLAG_SYSTEM) 主色;无壁纸颜色或系统异常回 null。 */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun readWallpaperSeed(context: Context): Int? = try {
        WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor
            ?.toArgbInt()
    } catch (_: Exception) {
        null
    }

    /**
     * 种子 → 日/夜两组派生 → 写 8 键 → 既有刷新管线。唯一色值写入点,
     * 一键取色与壁纸回调共用 (对照 WallpaperSeed→ThemeSeedApplier.applySeed 收口)。
     */
    private fun applySeed(context: Context, @ColorInt seed: Int) {
        // E-Ink 读取层强制白底黑字, 与 8 键无关; 跟随壁纸的 8 键写入 + 全局重建在
        // E-Ink 下是纯浪费且会引发一次可感知重建, 直接早退
        if (currentEInkMode()) return
        val day = deriveScheme(seed, isDark = false)
        val night = deriveScheme(seed, isDark = true)
        val prefs = PreferenceProviders.get()
        prefs.putInt(PreferKey.cPrimary, day.surface)
        prefs.putInt(PreferKey.cAccent, day.primary)
        prefs.putInt(PreferKey.cBackground, day.surface)
        prefs.putInt(PreferKey.cBBackground, day.surfaceContainer)
        prefs.putInt(PreferKey.cNPrimary, night.surface)
        prefs.putInt(PreferKey.cNAccent, night.primary)
        prefs.putInt(PreferKey.cNBackground, night.surface)
        prefs.putInt(PreferKey.cNBBackground, night.surfaceContainer)
        // 8 键日/夜两套都已写好, 与当前 themeMode 无关, 直接按当前模式重算 ThemeStore
        // 并触发全局重组 (同 ThemeCustomizeDialog.saveToPrefs 的刷新管线, 不改 themeMode)
        ThemeConfigProviders.get().applyThemeMode()
    }

    /** 种子 → SchemeContent 派生 (Content 方案保持种子色相, 标准对比度 0.0)。 */
    private fun deriveScheme(@ColorInt seed: Int, isDark: Boolean): SchemeColors {
        val scheme = SchemeContent(Hct.fromInt(seed), isDark, 0.0)
        return SchemeColors(
            primary = dynamicColors.primary().getArgb(scheme),
            surface = dynamicColors.surface().getArgb(scheme),
            surfaceContainer = dynamicColors.surfaceContainer().getArgb(scheme),
        )
    }
}

/** android.graphics.Color 实例 → ARGB Int (core-ktx 无该扩展)。 */
private fun android.graphics.Color.toArgbInt(): Int =
    android.graphics.Color.argb(alpha(), red(), green(), blue())
