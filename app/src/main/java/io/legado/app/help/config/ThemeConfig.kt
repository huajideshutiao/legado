package io.legado.app.help.config

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.util.DisplayMetrics
import androidx.annotation.ColorRes
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.toColorInt
import io.legado.app.App
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.help.i18n.androidAppString
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.deleteImageIfUnreferenced
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.toJson
import kotlinx.serialization.Serializable
import java.io.File

@Keep
object ThemeConfig {
    const val configFileName = ThemeConfigStore.configFileName

    /** 与 shared 同一路径实现, 保证四端 themeConfig.json 互读 */
    val configFilePath: String get() = ThemeConfigStore.configFilePath

    /** 贴边亮度采样: 解码宽度上限与采样带高度 (系统栏量级) */
    private const val EDGE_SAMPLE_WIDTH = 64
    private const val EDGE_SAMPLE_DP = 48f

    // 存档库：仅存储用户自定义/导入的主题
    val configList: ArrayList<Config> by lazy {
        ArrayList(getConfigsFromDisk())
    }

    fun getTheme() = when {
        AppConfig.isEInkMode -> Theme.EInk
        AppConfig.isNightTheme -> Theme.Dark
        else -> Theme.Light
    }

    /** 当前日/夜模式的背景图路径,未设置为 null (pref 存相对引用, 读取时解析为绝对, 见 resolveImagePath) */
    val curBgImagePath: String?
        get() = resolveImagePath(
            App.instance.getPrefString(
                if (AppConfig.isNightTheme) PreferKey.bgImageN else PreferKey.bgImage
            )
        )

    fun applyDayNight(context: Context) {
        initNightMode()
        applyTheme(context)
        // 图集昼夜切换: 列表由 BookCoverShared 记忆化缓存按 isNightTheme 惰性取, 无需手动刷
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
                themeName = androidAppString("default_day_theme"),
                isNightTheme = false,
                primaryColor = "#${dayBg.hexString}",
                accentColor = "#${dayAccent.hexString}",
                backgroundColor = "#${dayBg.hexString}",
                bottomBackground = "#${dayBbg.hexString}"
            ).apply { isBuiltin = true },
            Config(
                themeName = androidAppString("default_night_theme"),
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

    /** 壁纸贴边亮度缓存: key = 路径-mtime-窗口宽-窗口高-是否顶边 (特征值同名同内容, 换图靠 mtime 失效) */
    private val bgEdgeLightCache = HashMap<String, Boolean>()

    /**
     * 壁纸贴边那一条带 (状态栏/导航栏覆盖区) 是否浅色底, 供系统栏图标明暗判定。
     *
     * 壁纸页的系统栏底色是透明的, 直接拿透明色去 `isColorLight` 会恒判深色底
     * (透明的 RGB 是纯黑) → 图标恒为白, 浅色壁纸上看不见。这里按**显示出来的像素**采样:
     * 显示端是 ContentScale.Crop, 先按窗口比例反推可见区, 再取贴边 48dp 折算的行带算平均亮度。
     *
     * @param metrics 窗口尺寸 (widthPixels/heightPixels, 见 windowSize)
     * @param top true 取顶边 (状态栏), false 取底边 (导航栏)
     * @return 无背景图/解码失败返回 null, 调用方回落主题色判定
     */
    fun curBgEdgeIsLight(metrics: DisplayMetrics, top: Boolean): Boolean? {
        val path = curBgImagePath?.takeIf { it.isNotBlank() } ?: return null
        val key = "$path-${File(path).lastModified()}-" +
            "${metrics.widthPixels}-${metrics.heightPixels}-$top"
        bgEdgeLightCache[key]?.let { return it }
        return runCatching {
            // 只判一条带的平均亮度, 降采样到 64px 宽够用 (2 的幂采样保持比例, 裁切换算照旧成立)
            val bitmap = BitmapUtils.decodeBitmap(path, EDGE_SAMPLE_WIDTH) ?: return@runCatching null
            try {
                bitmap.edgeIsLight(metrics, top).also { bgEdgeLightCache[key] = it }
            } finally {
                bitmap.recycle()
            }
        }.onFailure {
            AppLog.put("采样背景图亮度出错\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun Bitmap.edgeIsLight(metrics: DisplayMetrics, top: Boolean): Boolean {
        val winW = metrics.widthPixels.coerceAtLeast(1)
        val winH = metrics.heightPixels.coerceAtLeast(1)
        // Crop: 按 max 比例铺满后居中裁切, 只有裁切后仍可见的那部分才会出现在系统栏后面
        val scale = maxOf(winW.toFloat() / width, winH.toFloat() / height)
        val visibleH = (winH / scale).coerceAtMost(height.toFloat())
        val startY = ((height - visibleH) / 2f).coerceAtLeast(0f)
        // 采样带 = 系统栏高度量级 (48dp) 折算回图片像素, 至少 1 行
        val density = App.instance.resources.displayMetrics.density
        val bandPx = (EDGE_SAMPLE_DP * density / scale).coerceAtLeast(1f)
        val y0 = if (top) {
            startY.toInt()
        } else {
            (startY + visibleH - bandPx).toInt()
        }.coerceIn(0, height - 1)
        val rows = bandPx.toInt().coerceIn(1, height - y0)
        val pixels = IntArray(width * rows)
        getPixels(pixels, 0, width, 0, y0, width, rows)
        var sum = 0.0
        for (pixel in pixels) {
            sum += ColorUtils.calculateLuminance(pixel)
        }
        return sum / pixels.size >= 0.5
    }

    fun save() {
        ThemeConfigStore.save(configList.map { it.toThemeConfigData() })
    }

    fun delConfig(index: Int) {
        if (index !in configList.indices) return
        configList.removeAt(index)
        save()
        applyTheme(App.instance)
    }

    fun upConfig() {
        configList.clear()
        configList.addAll(getConfigsFromDisk())
    }

    fun clearBg() {
        bgEdgeLightCache.clear()
        // 图集化后背景图落 customImg 内容特征值命名 (pref 存裸文件名, 经 resolveImagePath 解析);
        // 只处理纯数字主干文件, 删除前经 deleteImageIfUnreferenced 四键检查 (启动封面/界面背景
        // 日/夜), 任一键仍引用则保留 —— 同图复用场景不误删, 封面/启动图等其他图集文件不动
        App.instance.externalFiles.getFile("customImg").listFiles()?.forEach {
            val stem = it.name.substringBeforeLast('.', it.name)
            if (stem.isNotEmpty() && stem.all(Char::isDigit)) {
                deleteImageIfUnreferenced(it.absolutePath, withFile = true)
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
                save()
                return
            }
        }
        configList.add(newConfig)
        save()
    }

    /** 保留 androidx toColorInt (支持颜色名), 不走 shared 的纯 hex 解析, 避免收窄已有主题的可用范围 */
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

    private fun getConfigsFromDisk(): List<Config> =
        ThemeConfigStore.load().map { it.toConfig() }

    /** Config ↔ ThemeConfigData 字段一一对应, 仅用于走 shared 的读写实现 */
    private fun Config.toThemeConfigData() = ThemeConfigData(
        themeName, isNightTheme, primaryColor, accentColor, backgroundColor, bottomBackground
    )

    private fun ThemeConfigData.toConfig() = Config(
        themeName, isNightTheme, primaryColor, accentColor, backgroundColor, bottomBackground
    )

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
    @Serializable
    data class Config(
        var themeName: String,
        var isNightTheme: Boolean,
        var primaryColor: String,
        var accentColor: String,
        var backgroundColor: String,
        var bottomBackground: String
    ) {

        @Transient
        @kotlinx.serialization.Transient
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
