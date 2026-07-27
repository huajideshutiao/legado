package io.legado.app.help.config

import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import kotlinx.serialization.Serializable

/**
 * `ReadBookConfig` 的 KMP 下沉版本（桌面/iOS/鸿蒙共用）。
 *
 * 与 app 端 `io.legado.app.help.config.ReadBookConfig` 的差异：
 * 1. 用 `class` + [PreferenceStoreProvider] 注入取代 Android `object` + `appCtx` SharedPreferences 直读；
 *    调用方通过 [ReadConfigProviders] / [io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider]
 *    取得已注入实例，行为与 app 端逐字段对齐。
 * 2. **简单值配置** (textSize / lineSpacingExtra / paragraphSpacing / paddingXxx /
 *    textBold / letterSpacing / pageAnim / titleMode / titleSize / textFullJustify /
 *    textBottomJustify / hideStatusBar / hideNavigationBar / useZhLayout / bgAlpha /
 *    shareLayout / autoReadSpeed / readStyleSelect / comicStyleSelect /
 *    headerPaddingXxx / footerPaddingXxx / showHeaderLine / showFooterLine /
 *    paragraphIndent / underline / tipHeaderXxx / tipFooterXxx / tipColor /
 *    tipDividerColor / headerMode / footerMode / textFont)
 *    全部走 [prefs]，key 字符串与 app 端 `PreferKey` 已有项保持一致；
 *    其余原本走 `Config` data class 的项 (textSize 等) 在桌面端直接落到 pref，
 *    key 名与字段名相同。
 * 3. **样式主题列表** [configList] 简化为内存 `MutableList<ReadStyleConfig>`，
 *    JSON 持久化 (readConfig.json) 留待后续 commonMain 引入跨平台 JSON 库后再实现。
 * 4. **不包含** Drawable / Bitmap 操作 (curBgDrawable / upBg / save / import 等),
 *    这些仍由 app 端 `ReadBookConfig` actual 保留; 但 **clearBgAndCache 已下沉**
 *    (基于 [AppFilesDirs] + [BackupFileOps] 跨平台路径/文件抽象), 见 [clearBgAndCache]。
 *
 * 字段命名与 app 端保持一致，方便后续 app 端迁移到 Shared 版时最小改动。
 */
@Suppress("MemberVisibilityCanBePrivate")
class ReadBookConfigShared(private val prefs: PreferenceStoreProvider) {

    // -------------------- 全局简单值 (走 prefs，与 app 端 PreferKey 一致) --------------------

    /** 自动阅读速度（原 `AppConfig.autoReadSpeed`，默认 10）。 */
    var autoReadSpeed: Int
        get() = prefs.getInt(PreferKey.autoReadSpeed, 10)
        set(value) = prefs.putInt(PreferKey.autoReadSpeed, value)

    /** 文字阅读样式选择索引（默认 0）。 */
    var readStyleSelect: Int
        get() = prefs.getInt(PreferKey.readStyleSelect, 0)
        set(value) = prefs.putInt(PreferKey.readStyleSelect, value)

    /** 漫画样式选择索引（默认 0）。 */
    var comicStyleSelect: Int
        get() = prefs.getInt(PreferKey.comicStyleSelect, 0)
        set(value) = prefs.putInt(PreferKey.comicStyleSelect, value)

    /** 当前样式选择索引（按 [isComic] 切换 read/comic）。 */
    val styleSelect: Int
        get() = if (isComic) comicStyleSelect else readStyleSelect

    /** 是否共享排版（原 `AppConfig.shareLayout`，默认 false）。 */
    var shareLayout: Boolean
        get() = prefs.getBoolean(PreferKey.shareLayout, false)
        set(value) = prefs.putBoolean(PreferKey.shareLayout, value)

    /** 两端对齐（默认 true）。 */
    val textFullJustify: Boolean
        get() = prefs.getBoolean(PreferKey.textFullJustify, true)

    /** 底部对齐（默认 true）。 */
    val textBottomJustify: Boolean
        get() = prefs.getBoolean(PreferKey.textBottomJustify, true)

    /** 隐藏状态栏（默认 false）。 */
    var hideStatusBar: Boolean
        get() = prefs.getBoolean(PreferKey.hideStatusBar, false)
        set(value) = prefs.putBoolean(PreferKey.hideStatusBar, value)

    /** 隐藏导航栏（默认 false）。 */
    var hideNavigationBar: Boolean
        get() = prefs.getBoolean(PreferKey.hideNavigationBar, false)
        set(value) = prefs.putBoolean(PreferKey.hideNavigationBar, value)

    /** 中文排版模式（默认 false）。 */
    var useZhLayout: Boolean
        get() = prefs.getBoolean(PreferKey.useZhLayout, false)
        set(value) = prefs.putBoolean(PreferKey.useZhLayout, value)

    /**
     * 兼容 app 端 `reloadHideBarPrefs()` 的占位实现。
     * Shared 版 [hideStatusBar] / [hideNavigationBar] 直接读 prefs，无需 reload。
     */
    fun reloadHideBarPrefs() = Unit

    // -------------------- 样式主题内字段 (app 端原走 Config data class，桌面端落到 prefs) --------------------

    /** 背景透明度（默认 100）。 */
    var bgAlpha: Int
        get() = prefs.getInt(KEY_BG_ALPHA, 100)
        set(value) = prefs.putInt(KEY_BG_ALPHA, value)

    /** 翻页动画（[PageAnim.Anim]，默认 [PageAnim.coverPageAnim]）。 */
    var pageAnim: Int
        get() = prefs.getInt(KEY_PAGE_ANIM, PageAnim.coverPageAnim)
        set(@PageAnim.Anim value) {
            prefs.putInt(KEY_PAGE_ANIM, value)
        }

    /** 字体名/路径（默认空串）。 */
    var textFont: String
        get() = prefs.getString(KEY_TEXT_FONT, "") ?: ""
        set(value) = prefs.putString(KEY_TEXT_FONT, value)

    /** 是否粗体字 0:正常 1:粗体 2:细体（默认 0）。 */
    var textBold: Int
        get() = prefs.getInt(KEY_TEXT_BOLD, 0)
        set(value) = prefs.putInt(KEY_TEXT_BOLD, value)

    /** 文字大小（默认 20）。 */
    var textSize: Int
        get() = prefs.getInt(KEY_TEXT_SIZE, 20)
        set(value) = prefs.putInt(KEY_TEXT_SIZE, value)

    /** 字间距（默认 0.1f；用 String 存以避开 [PreferenceStoreProvider] 无 Float API）。 */
    var letterSpacing: Float
        get() = prefs.getString(KEY_LETTER_SPACING, DEFAULT_LETTER_SPACING)
            ?.toFloatOrNull() ?: 0.1f
        set(value) = prefs.putString(KEY_LETTER_SPACING, value.toString())

    /** 行间距（默认 12）。 */
    var lineSpacingExtra: Int
        get() = prefs.getInt(KEY_LINE_SPACING_EXTRA, 12)
        set(value) = prefs.putInt(KEY_LINE_SPACING_EXTRA, value)

    /** 段距（默认 2）。 */
    var paragraphSpacing: Int
        get() = prefs.getInt(KEY_PARAGRAPH_SPACING, 2)
        set(value) = prefs.putInt(KEY_PARAGRAPH_SPACING, value)

    /** 标题位置 0:居左 1:居中 2:隐藏（默认 0）。 */
    var titleMode: Int
        get() = prefs.getInt(KEY_TITLE_MODE, 0)
        set(value) = prefs.putInt(KEY_TITLE_MODE, value)

    /** 是否标题居中（与 app 端 `isMiddleTitle` 一致）。 */
    val isMiddleTitle: Boolean
        get() = titleMode == 1

    /** 标题字号（默认 0）。 */
    var titleSize: Int
        get() = prefs.getInt(KEY_TITLE_SIZE, 0)
        set(value) = prefs.putInt(KEY_TITLE_SIZE, value)

    /** 标题顶部间距（默认 0）。 */
    var titleTopSpacing: Int
        get() = prefs.getInt(KEY_TITLE_TOP_SPACING, 0)
        set(value) = prefs.putInt(KEY_TITLE_TOP_SPACING, value)

    /** 标题底部间距（默认 0）。 */
    var titleBottomSpacing: Int
        get() = prefs.getInt(KEY_TITLE_BOTTOM_SPACING, 0)
        set(value) = prefs.putInt(KEY_TITLE_BOTTOM_SPACING, value)

    /** 段落缩进（默认全角空格 "　　"）。 */
    var paragraphIndent: String
        get() = prefs.getString(KEY_PARAGRAPH_INDENT, DEFAULT_PARAGRAPH_INDENT)
            ?: DEFAULT_PARAGRAPH_INDENT
        set(value) = prefs.putString(KEY_PARAGRAPH_INDENT, value)

    /** 下划线（默认 false）。 */
    var underline: Boolean
        get() = prefs.getBoolean(KEY_UNDERLINE, false)
        set(value) = prefs.putBoolean(KEY_UNDERLINE, value)

    /** 正文下边距（默认 6）。 */
    var paddingBottom: Int
        get() = prefs.getInt(KEY_PADDING_BOTTOM, 6)
        set(value) = prefs.putInt(KEY_PADDING_BOTTOM, value)

    /** 正文左边距（默认 16）。 */
    var paddingLeft: Int
        get() = prefs.getInt(KEY_PADDING_LEFT, 16)
        set(value) = prefs.putInt(KEY_PADDING_LEFT, value)

    /** 正文右边距（默认 16）。 */
    var paddingRight: Int
        get() = prefs.getInt(KEY_PADDING_RIGHT, 16)
        set(value) = prefs.putInt(KEY_PADDING_RIGHT, value)

    /** 正文上边距（默认 6）。 */
    var paddingTop: Int
        get() = prefs.getInt(KEY_PADDING_TOP, 6)
        set(value) = prefs.putInt(KEY_PADDING_TOP, value)

    /** 页眉下边距（默认 0）。 */
    var headerPaddingBottom: Int
        get() = prefs.getInt(KEY_HEADER_PADDING_BOTTOM, 0)
        set(value) = prefs.putInt(KEY_HEADER_PADDING_BOTTOM, value)

    /** 页眉左边距（默认 16）。 */
    var headerPaddingLeft: Int
        get() = prefs.getInt(KEY_HEADER_PADDING_LEFT, 16)
        set(value) = prefs.putInt(KEY_HEADER_PADDING_LEFT, value)

    /** 页眉右边距（默认 16）。 */
    var headerPaddingRight: Int
        get() = prefs.getInt(KEY_HEADER_PADDING_RIGHT, 16)
        set(value) = prefs.putInt(KEY_HEADER_PADDING_RIGHT, value)

    /** 页眉上边距（默认 0）。 */
    var headerPaddingTop: Int
        get() = prefs.getInt(KEY_HEADER_PADDING_TOP, 0)
        set(value) = prefs.putInt(KEY_HEADER_PADDING_TOP, value)

    /** 页脚下边距（默认 6）。 */
    var footerPaddingBottom: Int
        get() = prefs.getInt(KEY_FOOTER_PADDING_BOTTOM, 6)
        set(value) = prefs.putInt(KEY_FOOTER_PADDING_BOTTOM, value)

    /** 页脚左边距（默认 16）。 */
    var footerPaddingLeft: Int
        get() = prefs.getInt(KEY_FOOTER_PADDING_LEFT, 16)
        set(value) = prefs.putInt(KEY_FOOTER_PADDING_LEFT, value)

    /** 页脚右边距（默认 16）。 */
    var footerPaddingRight: Int
        get() = prefs.getInt(KEY_FOOTER_PADDING_RIGHT, 16)
        set(value) = prefs.putInt(KEY_FOOTER_PADDING_RIGHT, value)

    /** 页脚上边距（默认 6）。 */
    var footerPaddingTop: Int
        get() = prefs.getInt(KEY_FOOTER_PADDING_TOP, 6)
        set(value) = prefs.putInt(KEY_FOOTER_PADDING_TOP, value)

    /** 是否显示页眉分隔线（默认 false）。 */
    var showHeaderLine: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HEADER_LINE, false)
        set(value) = prefs.putBoolean(KEY_SHOW_HEADER_LINE, value)

    /** 是否显示页脚分隔线（默认 true）。 */
    var showFooterLine: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FOOTER_LINE, true)
        set(value) = prefs.putBoolean(KEY_SHOW_FOOTER_LINE, value)

    /** 页眉左槽位 tip 类型（默认 [ReadTipConfigShared.time]）。 */
    var tipHeaderLeft: Int
        get() = prefs.getInt(KEY_TIP_HEADER_LEFT, ReadTipConfigShared.time)
        set(value) = prefs.putInt(KEY_TIP_HEADER_LEFT, value)

    /** 页眉中槽位 tip 类型（默认 [ReadTipConfigShared.none]）。 */
    var tipHeaderMiddle: Int
        get() = prefs.getInt(KEY_TIP_HEADER_MIDDLE, ReadTipConfigShared.none)
        set(value) = prefs.putInt(KEY_TIP_HEADER_MIDDLE, value)

    /** 页眉右槽位 tip 类型（默认 [ReadTipConfigShared.battery]）。 */
    var tipHeaderRight: Int
        get() = prefs.getInt(KEY_TIP_HEADER_RIGHT, ReadTipConfigShared.battery)
        set(value) = prefs.putInt(KEY_TIP_HEADER_RIGHT, value)

    /** 页脚左槽位 tip 类型（默认 [ReadTipConfigShared.chapterTitle]）。 */
    var tipFooterLeft: Int
        get() = prefs.getInt(KEY_TIP_FOOTER_LEFT, ReadTipConfigShared.chapterTitle)
        set(value) = prefs.putInt(KEY_TIP_FOOTER_LEFT, value)

    /** 页脚中槽位 tip 类型（默认 [ReadTipConfigShared.none]）。 */
    var tipFooterMiddle: Int
        get() = prefs.getInt(KEY_TIP_FOOTER_MIDDLE, ReadTipConfigShared.none)
        set(value) = prefs.putInt(KEY_TIP_FOOTER_MIDDLE, value)

    /** 页脚右槽位 tip 类型（默认 [ReadTipConfigShared.pageAndTotal]）。 */
    var tipFooterRight: Int
        get() = prefs.getInt(KEY_TIP_FOOTER_RIGHT, ReadTipConfigShared.pageAndTotal)
        set(value) = prefs.putInt(KEY_TIP_FOOTER_RIGHT, value)

    /** tip 颜色索引（默认 0）。 */
    var tipColor: Int
        get() = prefs.getInt(KEY_TIP_COLOR, 0)
        set(value) = prefs.putInt(KEY_TIP_COLOR, value)

    /** tip 分隔线颜色索引（默认 -1，表示不显示）。 */
    var tipDividerColor: Int
        get() = prefs.getInt(KEY_TIP_DIVIDER_COLOR, -1)
        set(value) = prefs.putInt(KEY_TIP_DIVIDER_COLOR, value)

    /** 页眉显示模式（默认 0：状态栏显示时隐藏）。 */
    var headerMode: Int
        get() = prefs.getInt(KEY_HEADER_MODE, 0)
        set(value) = prefs.putInt(KEY_HEADER_MODE, value)

    /** 页脚显示模式（默认 0：显示）。 */
    var footerMode: Int
        get() = prefs.getInt(KEY_FOOTER_MODE, 0)
        set(value) = prefs.putInt(KEY_FOOTER_MODE, value)

    // -------------------- 样式主题列表（内存，commonMain 无 GSON 暂不持久化） --------------------

    /** 当前是否漫画模式（内存字段，与 app 端 `isComic` 一致）。 */
    var isComic: Boolean = false

    /**
     * 样式主题列表。桌面端简化为内存 `MutableList`，初始仅含一个默认主题；
     * 持久化（readConfig.json）待后续引入跨平台 JSON 库再实现。
     */
    val configList: MutableList<ReadStyleConfig> = mutableListOf(ReadStyleConfig())

    /** 共享排版配置（shareLayout=true 时使用，初始取 configList[5] 或默认值）。 */
    var shareConfig: ReadStyleConfig = configList.getOrElse(5) { ReadStyleConfig() }

    /** 当前主题（按 [styleSelect] 取，越界回退到 index 0）。 */
    val durConfig: ReadStyleConfig
        get() = getConfig(styleSelect)

    /** 实际生效配置（shareLayout=true 走 [shareConfig]，否则走 [durConfig]）。 */
    val config: ReadStyleConfig
        get() = if (shareLayout) shareConfig else durConfig

    /**
     * 按索引取样式主题，越界回退到 index 0（与 app 端 `getConfig` 一致，
     * 但不做 resetAll，因桌面端无 DefaultData.readConfigs 兜底）。
     */
    fun getConfig(index: Int): ReadStyleConfig {
        if (configList.isEmpty()) {
            configList.add(ReadStyleConfig())
        }
        return configList.getOrNull(index) ?: configList[0]
    }

    /**
     * 导出当前主题副本（与 app 端 `getExportConfig` 对应）。
     * 桌面端暂不实现 shareLayout 字段覆盖逻辑，仅返回 [durConfig] 副本。
     */
    fun getExportConfig(): ReadStyleConfig = durConfig.copy()

    /** 收集所有图片背景资源字符串（bgType==2），供清理缓存用。 */
    fun getAllPicBgStr(): List<String> {
        val list = mutableListOf<String>()
        configList.forEach {
            if (it.bgType == 2) list.add(it.bgStr)
            if (it.bgTypeNight == 2) list.add(it.bgStrNight)
            if (it.bgTypeEInk == 2) list.add(it.bgStrEInk)
        }
        return list
    }

    /**
     * 清理阅读背景缓存图片 + readConfig 临时缓存 (对照 app 端 `ReadBookConfig.clearBgAndCache`)。
     *
     * # 行为
     * 1. 收集 [configList] 中所有 bgType==2 的背景路径 (bgStr/bgStrNight/bgStrEInk),
     *    含分隔符视为绝对路径, 否则拼接为 `{externalFilesDir ?: filesDir}/bg/{bgStr}`
     *    (与 app 端 `Config.getBgPath` 逻辑一致)
     * 2. 删除 `{externalFilesDir ?: filesDir}/bg/` 目录下不在白名单中的文件
     * 3. 删除 `{externalCacheDir ?: cacheDir}/readConfig` 目录 (导入配置临时解压目录)
     * 4. 删除 `{externalCacheDir ?: cacheDir}/readConfig.zip` (导入配置 zip 临时文件)
     *
     * # 平台差异
     * - app 端: `externalFilesDir` / `externalCacheDir` 由 Android Context 提供
     * - 桌面端: 均为 null, 回退到 `filesDir` (~/.legado/files) / `cacheDir` (~/.legado/cache)
     * - 桌面端 BgTextConfigDialog 用户选图直接存绝对路径, bg/ 目录通常不存在, 清理为 no-op
     *
     * # 与 app 端 `ReadBookConfig.clearBgAndCache` 的差异
     * - app 端用 `appCtx.externalFiles.getFile("bg")` / `appCtx.externalCache.getFile("readConfig")`
     * - shared 版用 [AppFilesDirs] + [BackupFileOps] 跨平台抽象, 行为等价
     * - app 端 `App.kt` 仍调原 `ReadBookConfig.clearBgAndCache()` (object 方法), 不经此处;
     *   桌面端 `DesktopMainViewModel.postLoad` 调本方法
     */
    fun clearBgAndCache() {
        val filesBase = AppFilesDirs.get().externalFilesDir ?: AppFilesDirs.get().filesDir
        val cacheBase = AppFilesDirs.get().externalCacheDir ?: AppFilesDirs.get().cacheDir
        val sep = BackupFileOps.separator

        // 1. 收集白名单 (bgType==2 的背景路径, 含绝对路径和 bg/ 目录下文件名)
        val bgs = hashSetOf<String>()
        configList.forEach { config ->
            if (config.bgType == 2) bgs.add(resolveBgPath(filesBase, config.bgStr, sep))
            if (config.bgTypeNight == 2) bgs.add(resolveBgPath(filesBase, config.bgStrNight, sep))
            if (config.bgTypeEInk == 2) bgs.add(resolveBgPath(filesBase, config.bgStrEInk, sep))
        }

        // 2. 删除 bg 目录中不在白名单的文件 (目录不存在 listFiles 返回 null, 跳过)
        val bgDir = filesBase + sep + "bg"
        BackupFileOps.listFiles(bgDir)?.forEach { filePath ->
            if (filePath !in bgs) {
                BackupFileOps.delete(filePath)
            }
        }

        // 3. 删除 readConfig 缓存目录 + zip 临时文件 (与 app 端 externalCache/readConfig 对齐)
        BackupFileOps.delete(cacheBase + sep + "readConfig")
        BackupFileOps.delete(cacheBase + sep + "readConfig.zip")
    }

    /**
     * 解析背景路径: 含分隔符视为绝对路径, 否则拼接为 `{filesBase}/bg/{bgStr}`。
     * (与 app 端 `Config.getBgPath` 内 `bgStr.contains(File.separator)` 判断一致)
     */
    private fun resolveBgPath(filesBase: String, bgStr: String, sep: String): String {
        return if (bgStr.contains(sep)) bgStr else filesBase + sep + "bg" + sep + bgStr
    }

    /**
     * 删除当前主题。仅当 configList 大于 5 时允许删除（与 app 端一致）。
     * 删除后同步调整 [readStyleSelect] / [comicStyleSelect] 索引。
     */
    fun deleteDur(): Boolean {
        if (configList.size > 5) {
            val removeIndex = styleSelect
            configList.removeAt(removeIndex)
            if (removeIndex <= readStyleSelect) {
                readStyleSelect -= 1
            }
            if (removeIndex <= comicStyleSelect) {
                comicStyleSelect -= 1
            }
            return true
        }
        return false
    }

    // -------------------- 常量（prefs key，与 app 端 Config data class 字段名对齐） --------------------

    private companion object {
        // 字段名作 prefs key，桌面端首次启动用与 app 端 Config 默认值一致的默认值
        const val KEY_BG_ALPHA = "bgAlpha"
        const val KEY_PAGE_ANIM = "pageAnim"
        const val KEY_TEXT_FONT = "textFont"
        const val KEY_TEXT_BOLD = "textBold"
        const val KEY_TEXT_SIZE = "textSize"
        const val KEY_LETTER_SPACING = "letterSpacing"
        const val KEY_LINE_SPACING_EXTRA = "lineSpacingExtra"
        const val KEY_PARAGRAPH_SPACING = "paragraphSpacing"
        const val KEY_TITLE_MODE = "titleMode"
        const val KEY_TITLE_SIZE = "titleSize"
        const val KEY_TITLE_TOP_SPACING = "titleTopSpacing"
        const val KEY_TITLE_BOTTOM_SPACING = "titleBottomSpacing"
        const val KEY_PARAGRAPH_INDENT = "paragraphIndent"
        const val KEY_UNDERLINE = "underline"
        const val KEY_PADDING_BOTTOM = "paddingBottom"
        const val KEY_PADDING_LEFT = "paddingLeft"
        const val KEY_PADDING_RIGHT = "paddingRight"
        const val KEY_PADDING_TOP = "paddingTop"
        const val KEY_HEADER_PADDING_BOTTOM = "headerPaddingBottom"
        const val KEY_HEADER_PADDING_LEFT = "headerPaddingLeft"
        const val KEY_HEADER_PADDING_RIGHT = "headerPaddingRight"
        const val KEY_HEADER_PADDING_TOP = "headerPaddingTop"
        const val KEY_FOOTER_PADDING_BOTTOM = "footerPaddingBottom"
        const val KEY_FOOTER_PADDING_LEFT = "footerPaddingLeft"
        const val KEY_FOOTER_PADDING_RIGHT = "footerPaddingRight"
        const val KEY_FOOTER_PADDING_TOP = "footerPaddingTop"
        const val KEY_SHOW_HEADER_LINE = "showHeaderLine"
        const val KEY_SHOW_FOOTER_LINE = "showFooterLine"
        const val KEY_TIP_HEADER_LEFT = "tipHeaderLeft"
        const val KEY_TIP_HEADER_MIDDLE = "tipHeaderMiddle"
        const val KEY_TIP_HEADER_RIGHT = "tipHeaderRight"
        const val KEY_TIP_FOOTER_LEFT = "tipFooterLeft"
        const val KEY_TIP_FOOTER_MIDDLE = "tipFooterMiddle"
        const val KEY_TIP_FOOTER_RIGHT = "tipFooterRight"
        const val KEY_TIP_COLOR = "tipColor"
        const val KEY_TIP_DIVIDER_COLOR = "tipDividerColor"
        const val KEY_HEADER_MODE = "headerMode"
        const val KEY_FOOTER_MODE = "footerMode"

        const val DEFAULT_LETTER_SPACING = "0.1"
        const val DEFAULT_PARAGRAPH_INDENT = "　　"
    }
}

/**
 * 样式主题配置（与 app 端 `ReadBookConfig.Config` 中"纯样式"字段对应）。
 *
 * 仅承载与背景/颜色/状态栏图标/翻页动画相关的字段；这些字段在 app 端属于
 * `Config` data class 的一部分（最终序列化到 readConfig.json），桌面端简化为
 * 内存 `data class`，由 [ReadBookConfigShared.configList] 持有。
 *
 * 不包含 Drawable / Bitmap / File 操作，hex 颜色字符串到 Int 的解析由
 * 各平台 actual 完成（桌面端可直接用 `Color(hexStr)`）。
 *
 * @param name 主题名称
 * @param bgStr 白天背景（hex 字符串如 "#EEEEEE" 或图片路径）
 * @param bgStrNight 夜间背景
 * @param bgStrEInk E-Ink 背景
 * @param bgType 白天背景类型 0:颜色 1:assets 图片 2:其它图片
 * @param bgTypeNight 夜间背景类型
 * @param bgTypeEInk E-Ink 背景类型
 * @param darkStatusIcon 白天是否暗色状态栏图标
 * @param darkStatusIconNight 夜间是否暗色状态栏图标
 * @param darkStatusIconEInk E-Ink 是否暗色状态栏图标
 * @param textColorStr 白天文字颜色 hex
 * @param textColorStrNight 夜间文字颜色 hex
 * @param textColorStrEInk E-Ink 文字颜色 hex
 * @param textColor 当前文字颜色 Int（由 actual 解析 hex 得到，默认 0）
 * @param bgMeanColor 背景主色 Int（由 actual 取色得到，默认 0）
 * @param pageAnim 翻页动画（[PageAnim.Anim]）
 * @param pageAnimEInk E-Ink 翻页动画（默认 [PageAnim.noAnim]）
 */
data class ReadStyleConfig(
    var name: String = "",
    var bgStr: String = "#EEEEEE",
    var bgStrNight: String = "#000000",
    var bgStrEInk: String = "#FFFFFF",
    var bgType: Int = 0,
    var bgTypeNight: Int = 0,
    var bgTypeEInk: Int = 0,
    var darkStatusIcon: Boolean = true,
    var darkStatusIconNight: Boolean = false,
    var darkStatusIconEInk: Boolean = true,
    var textColorStr: String = "#3E3D3B",
    var textColorStrNight: String = "#ADADAD",
    var textColorStrEInk: String = "#000000",
    var textColor: Int = 0,
    var bgMeanColor: Int = 0,
    var pageAnim: Int = 0,
    var pageAnimEInk: Int = PageAnim.noAnim,
)
