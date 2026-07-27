package io.legado.app.help.config

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.NinePatch
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.util.DisplayMetrics
import androidx.annotation.ColorRes
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.BookCover
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.centerCrop
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.stackBlur
import splitties.init.appCtx
import java.io.File

@Keep
object ThemeConfig {
    const val configFileName = "themeConfig.json"
    val configFilePath = FileUtils.getPath(appCtx.filesDir, configFileName)

    private var bgDrawableCache: Drawable? = null
    private var bgCacheKey: String? = null

    // 存档库：仅存储用户自定义/导入的主题
    val configList: ArrayList<Config> by lazy {
        ArrayList(getConfigsFromDisk() ?: emptyList())
    }

    fun getTheme() = when {
        AppConfig.isEInkMode -> Theme.EInk
        AppConfig.isNightTheme -> Theme.Dark
        else -> Theme.Light
    }

    /** 当前日/夜模式的背景图路径,未设置为 null */
    val curBgImagePath: String?
        get() = appCtx.getPrefString(
            if (AppConfig.isNightTheme) PreferKey.bgImageN else PreferKey.bgImage
        )

    fun applyDayNight(context: Context) {
        initNightMode()
        applyTheme(context)
        BookCover.upDefaultCover()
        postEvent(EventBus.RECREATE, "")
    }

    fun applyDayNightInit(context: Context) {
        initNightMode()
        applyTheme(context)
    }

    private fun initNightMode(): Boolean {
        val targetMode = if (AppConfig.isNightTheme) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode)
            return true
        }
        return false
    }

    /**
     * pref 全 0 时按 XML 值实时读取，用户改过就用用户的。不写回 pref。
     */
    fun applyTheme(context: Context) = with(context) {
        if (AppConfig.isEInkMode) {
            ThemeStore.saveTheme(Color.WHITE, Color.BLACK, Color.WHITE, Color.WHITE)
            return@with
        }

        val isNight = AppConfig.isNightTheme
        val accentKey = if (isNight) PreferKey.cNAccent else PreferKey.cAccent
        val bgKey = if (isNight) PreferKey.cNBackground else PreferKey.cBackground
        val bbgKey = if (isNight) PreferKey.cNBBackground else PreferKey.cBBackground

        val (defAccent, defBg, defBbg) = readDefaultColors(context, isNight)
        val accent = getPrefInt(accentKey).let { if (it == 0) defAccent else it }
        val background = getPrefInt(bgKey).let { if (it == 0) defBg else it }
        val bBackground = getPrefInt(bbgKey).let { if (it == 0) defBbg else it }

        ThemeStore.saveTheme(background, accent, background, bBackground)
    }

    /**
     * 实时读取 arco 默认色，供内置主题条目、对话框种子色、applyTheme 复用
     */
    fun readDefaultColors(context: Context, isNight: Boolean): Triple<Int, Int, Int> =
        with(context) {
            Triple(
                getCompatColorForMode(R.color.arco_default_accent, isNight),
                getCompatColorForMode(R.color.arco_default_bg, isNight),
                getCompatColorForMode(R.color.arco_default_bbg, isNight)
            )
        }

    /** 自定义主题编辑页的 pref 快照 */
    data class CustomTheme(
        val accent: Int,
        val background: Int,
        val bottomBackground: Int,
        val bgImage: String?,
        val bgImageBlur: Int,
    )

    /** 读取指定模式的自定义主题,色值 0 视为未设置回落默认色 */
    fun readCustomTheme(context: Context, isNight: Boolean): CustomTheme = with(context) {
        val (defAccent, defBg, defBbg) = readDefaultColors(context, isNight)
        val accentKey = if (isNight) PreferKey.cNAccent else PreferKey.cAccent
        val bgKey = if (isNight) PreferKey.cNBackground else PreferKey.cBackground
        val bbgKey = if (isNight) PreferKey.cNBBackground else PreferKey.cBBackground
        val bgImageKey = if (isNight) PreferKey.bgImageN else PreferKey.bgImage
        val blurKey = if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring
        CustomTheme(
            accent = getPrefInt(accentKey).let { if (it == 0) defAccent else it },
            background = getPrefInt(bgKey).let { if (it == 0) defBg else it },
            bottomBackground = getPrefInt(bbgKey).let { if (it == 0) defBbg else it },
            bgImage = getPrefString(bgImageKey),
            bgImageBlur = getPrefInt(blurKey, 0),
        )
    }

    /** 保存指定模式的自定义主题;bgImage 为空清除背景图 */
    fun saveCustomTheme(context: Context, isNight: Boolean, theme: CustomTheme) = with(context) {
        val primaryKey = if (isNight) PreferKey.cNPrimary else PreferKey.cPrimary
        val accentKey = if (isNight) PreferKey.cNAccent else PreferKey.cAccent
        val bgKey = if (isNight) PreferKey.cNBackground else PreferKey.cBackground
        val bbgKey = if (isNight) PreferKey.cNBBackground else PreferKey.cBBackground
        val bgImageKey = if (isNight) PreferKey.bgImageN else PreferKey.bgImage
        val blurKey = if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring
        putPrefInt(primaryKey, theme.background)
        putPrefInt(accentKey, theme.accent)
        putPrefInt(bgKey, theme.background)
        putPrefInt(bbgKey, theme.bottomBackground)
        if (theme.bgImage.isNullOrBlank()) {
            removePref(bgImageKey)
        } else {
            putPrefString(bgImageKey, theme.bgImage)
        }
        putPrefInt(blurKey, theme.bgImageBlur)
    }

    /**
     * 前置到主题列表最前的两个虚拟条目，不写盘、不进 configList
     */
    fun getBuiltinConfigs(context: Context): List<Config> {
        val (dayAccent, dayBg, dayBbg) = readDefaultColors(context, false)
        val (nightAccent, nightBg, nightBbg) = readDefaultColors(context, true)
        return listOf(
            Config(
                themeName = context.getString(R.string.default_day_theme),
                isNightTheme = false,
                primaryColor = "#${dayBg.hexString}",
                accentColor = "#${dayAccent.hexString}",
                backgroundColor = "#${dayBg.hexString}",
                bottomBackground = "#${dayBbg.hexString}"
            ).apply { isBuiltin = true },
            Config(
                themeName = context.getString(R.string.default_night_theme),
                isNightTheme = true,
                primaryColor = "#${nightBg.hexString}",
                accentColor = "#${nightAccent.hexString}",
                backgroundColor = "#${nightBg.hexString}",
                bottomBackground = "#${nightBbg.hexString}"
            ).apply { isBuiltin = true }
        )
    }

    /**
     * 应用内置默认主题：清 6 个 pref → applyTheme 走 XML 实时读取
     */
    fun applyBuiltin(context: Context, isNight: Boolean) {
        if (isNight) {
            context.removePref(PreferKey.cNAccent)
            context.removePref(PreferKey.cNBackground)
            context.removePref(PreferKey.cNBBackground)
            context.removePref(PreferKey.cNPrimary)
            context.removePref(PreferKey.bgImageN)
            context.removePref(PreferKey.bgImageNBlurring)
        } else {
            context.removePref(PreferKey.cAccent)
            context.removePref(PreferKey.cBackground)
            context.removePref(PreferKey.cBBackground)
            context.removePref(PreferKey.cPrimary)
            context.removePref(PreferKey.bgImage)
            context.removePref(PreferKey.bgImageBlurring)
        }
        AppConfig.isNightTheme = isNight
        applyDayNight(context)
    }

    /**
     * 核心：强制从指定模式的资源中提取色值
     */
    private fun Context.getCompatColorForMode(@ColorRes id: Int, isNight: Boolean): Int {
        val configuration = Configuration(resources.configuration)
        configuration.uiMode = if (isNight) {
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        } else {
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        return createConfigurationContext(configuration).getCompatColor(id)
    }

    fun getBgDrawable(context: Context, metrics: DisplayMetrics): Drawable? {
        val theme = getTheme()
        val bgCfg = when (theme) {
            Theme.Light -> Pair(
                context.getPrefString(PreferKey.bgImage),
                context.getPrefInt(PreferKey.bgImageBlurring, 0)
            )

            Theme.Dark -> Pair(
                context.getPrefString(PreferKey.bgImageN),
                context.getPrefInt(PreferKey.bgImageNBlurring, 0)
            )

            else -> null
        } ?: return null
        if (bgCfg.first.isNullOrBlank()) {
            bgDrawableCache = null
            bgCacheKey = null
            return null
        }
        val path = bgCfg.first!!
        val blurRadius = bgCfg.second
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val cacheKey = "$path-$blurRadius-$width-$height-$theme"
        if (cacheKey == bgCacheKey) {
            bgDrawableCache?.let {
                return it.constantState?.newDrawable(context.resources) ?: it
            }
        }

        var bitmap = BitmapUtils.decodeBitmap(path, width, height) ?: return null

        val chunk = bitmap.ninePatchChunk
        if (chunk != null && NinePatch.isNinePatchChunk(chunk)) {
            if (blurRadius > 0) {
                bitmap = bitmap.stackBlur(blurRadius)
            }
            val drawable = NinePatchDrawable(context.resources, bitmap, chunk, Rect(), null)
            bgDrawableCache = drawable
            bgCacheKey = cacheKey
            return drawable.constantState?.newDrawable(context.resources) ?: drawable
        }

        if (blurRadius > 0) {
            bitmap = bitmap.stackBlur(blurRadius)
        }

        val resultBitmap = bitmap.centerCrop(width, height)
        if (resultBitmap != bitmap) bitmap.recycle()
        val drawable = resultBitmap.toDrawable(context.resources)
        bgDrawableCache = drawable
        bgCacheKey = cacheKey
        return drawable.constantState?.newDrawable(context.resources) ?: drawable
    }

    fun save() {
        val json = GSON.toJson(configList)
        FileUtils.delete(configFilePath)
        FileUtils.createFileIfNotExist(configFilePath).writeText(json)
    }

    fun delConfig(index: Int) {
        configList.removeAt(index)
        save()
        applyTheme(appCtx)
    }

    fun upConfig() {
        configList.clear()
        getConfigsFromDisk()?.let {
            configList.addAll(it)
        }
    }

    fun clearBg() {
        bgDrawableCache = null
        bgCacheKey = null
        val bgImagePath = appCtx.getPrefString(PreferKey.bgImage)
        appCtx.externalFiles.getFile(PreferKey.bgImage).listFiles()?.forEach {
            if (it.absolutePath != bgImagePath) {
                it.delete()
            }
        }
        val bgImageNPath = appCtx.getPrefString(PreferKey.bgImageN)
        appCtx.externalFiles.getFile(PreferKey.bgImageN).listFiles()?.forEach {
            if (it.absolutePath != bgImageNPath) {
                it.delete()
            }
        }
    }

    fun addConfig(json: String): Boolean {
        GSON.fromJsonObject<Config>(json.trim { it < ' ' }).getOrNull()
            ?.let {
                if (validateConfig(it)) {
                    addConfig(it)
                    return true
                }
            }
        return false
    }

    fun addConfig(newConfig: Config) {
        if (!validateConfig(newConfig)) {
            return
        }
        configList.forEachIndexed { index, config ->
            if (newConfig.themeName == config.themeName) {
                configList[index] = newConfig
                return
            }
        }
        configList.add(newConfig)
        save()
    }

    private fun validateConfig(config: Config): Boolean {
        try {
            config.primaryColor.toColorInt()
            config.accentColor.toColorInt()
            config.backgroundColor.toColorInt()
            config.bottomBackground.toColorInt()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun getConfigsFromDisk(): List<Config>? {
        val configFile = File(configFilePath)
        if (configFile.exists()) {
            kotlin.runCatching {
                val json = configFile.readText()
                return GSON.fromJsonArray<Config>(json).getOrThrow()
            }.onFailure {
                it.printOnDebug()
            }
        }
        return null
    }

    private fun applyConfigToPrefs(context: Context, config: Config) {
        val accent = config.accentColor.toColorInt()
        val background = config.backgroundColor.toColorInt()
        val bBackground = config.bottomBackground.toColorInt()
        if (config.isNightTheme) {
            context.putPrefInt(PreferKey.cNPrimary, background)
            context.putPrefInt(PreferKey.cNAccent, accent)
            context.putPrefInt(PreferKey.cNBackground, background)
            context.putPrefInt(PreferKey.cNBBackground, bBackground)
        } else {
            context.putPrefInt(PreferKey.cPrimary, background)
            context.putPrefInt(PreferKey.cAccent, accent)
            context.putPrefInt(PreferKey.cBackground, background)
            context.putPrefInt(PreferKey.cBBackground, bBackground)
        }
    }

    fun applyConfig(context: Context, config: Config) {
        try {
            applyConfigToPrefs(context, config)
            AppConfig.isNightTheme = config.isNightTheme
            applyDayNight(context)
        } catch (e: Exception) {
            AppLog.put("设置主题出错\n$e", e, true)
        }
    }

    @Keep
    data class Config(
        var themeName: String,
        var isNightTheme: Boolean,
        var primaryColor: String,
        var accentColor: String,
        var backgroundColor: String,
        var bottomBackground: String
    ) {

        @Transient
        var isBuiltin: Boolean = false

        override fun hashCode(): Int {
            return GSON.toJson(this).hashCode()
        }

        override fun equals(other: Any?): Boolean {
            other ?: return false
            if (other is Config) {
                return other.themeName == themeName
                        && other.isNightTheme == isNightTheme
                        && other.primaryColor == primaryColor
                        && other.accentColor == accentColor
                        && other.backgroundColor == backgroundColor
                        && other.bottomBackground == bottomBackground
            }
            return false
        }

    }

}
