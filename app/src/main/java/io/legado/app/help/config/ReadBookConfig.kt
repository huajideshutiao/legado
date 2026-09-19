package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.constant.PageAnim
import io.legado.app.help.config.ReadBookConfig.Config

/**
 * 阅读界面配置 (app 端薄壳)。
 *
 * 数据/持久化/导入导出已下沉 [ReadBookConfigShared]（configList + shareConfig +
 * readConfig.json/shareReadConfig.json 读写 + zip 导入导出），本 object 只做转发，
 * 保证 app 消费方 `ReadBookConfig.xxx` 写法不变。
 *
 * 原 Android 独占的背景渲染（bg Drawable / upBg / curBgDrawable）已随阅读页迁移
 * shared Compose 渲染与"内置背景下线、背景完全用户自选"移除。
 *
 * `ReadBookConfig.Config` 类型已并入 [ReadStyleConfig]（Kotlin 2.2 的嵌套 typealias
 * 仍需编译器开关，故用同名工厂函数 [Config] 兼容 `ReadBookConfig.Config()` 构造写法）。
 */
@Suppress("ConstPropertyName")
@Keep
object ReadBookConfig {

    /**
     * 下沉实现。App.onCreate 已 `ReadBookConfigProviders.register(...)`，
     * 未注册时自建并回填，避免注册前的早期访问直接崩溃。
     */
    private val shared: ReadBookConfigShared
        get() = ReadBookConfigProviders.getOrNull() ?: fallbackShared

    private val fallbackShared: ReadBookConfigShared by lazy {
        ReadBookConfigShared(PreferenceProviders.get())
            .also { ReadBookConfigProviders.register(it) }
    }

    const val configFileName = ReadBookConfigShared.configFileName
    const val shareConfigFileName = ReadBookConfigShared.shareConfigFileName
    val configFilePath get() = shared.configFilePath
    val shareConfigFilePath get() = shared.shareConfigFilePath

    val configList get() = shared.configList
    var shareConfig
        get() = shared.shareConfig
        set(value) {
            shared.shareConfig = value
        }
    var durConfig
        get() = shared.durConfig
        set(value) {
            shared.durConfig = value
        }
    val config get() = shared.config

    var isComic
        get() = shared.isComic
        set(value) {
            shared.isComic = value
        }

    val textColor: Int get() = shared.textColor

    /** `ReadBookConfig.Config()` 兼容工厂，等价于 `ReadStyleConfig()`。 */
    @Suppress("FunctionName")
    fun Config(): ReadStyleConfig = ReadStyleConfig()

    fun getConfig(index: Int): ReadStyleConfig = shared.getConfig(index)

    fun initConfigs() = shared.initConfigs()

    fun initShareConfig() = shared.initShareConfig()

    fun save() = shared.save()

    fun getAllPicBgStr(): List<String> = shared.getAllPicBgStr()

    fun deleteDur(): Boolean = shared.deleteDur()

    fun clearBgAndCache() = shared.clearBgAndCache()

    fun getExportConfig(): ReadStyleConfig = shared.getExportConfig()

    fun import(byteArray: ByteArray): ReadStyleConfig = shared.import(byteArray)

    //配置写入读取
    var autoReadSpeed
        get() = shared.autoReadSpeed
        set(value) {
            shared.autoReadSpeed = value
        }
    var styleSelect
        get() = shared.styleSelect
        set(value) {
            shared.styleSelect = value
        }
    var readStyleSelect
        get() = shared.readStyleSelect
        set(value) {
            shared.readStyleSelect = value
        }
    var comicStyleSelect
        get() = shared.comicStyleSelect
        set(value) {
            shared.comicStyleSelect = value
        }
    var shareLayout
        get() = shared.shareLayout
        set(value) {
            shared.shareLayout = value
        }

    /**
     * 两端对齐
     */
    val textFullJustify get() = shared.textFullJustify

    /**
     * 底部对齐
     */
    val textBottomJustify get() = shared.textBottomJustify
    var hideStatusBar
        get() = shared.hideStatusBar
        set(value) {
            shared.hideStatusBar = value
        }
    var hideNavigationBar
        get() = shared.hideNavigationBar
        set(value) {
            shared.hideNavigationBar = value
        }

    var bgAlpha
        get() = shared.bgAlpha
        set(value) {
            shared.bgAlpha = value
        }

    var pageAnim
        get() = shared.pageAnim
        set(@PageAnim.Anim value) {
            shared.pageAnim = value
        }

    var textFont
        get() = shared.textFont
        set(value) {
            shared.textFont = value
        }

    var textBold
        get() = shared.textBold
        set(value) {
            shared.textBold = value
        }

    var textSize
        get() = shared.textSize
        set(value) {
            shared.textSize = value
        }

    var letterSpacing
        get() = shared.letterSpacing
        set(value) {
            shared.letterSpacing = value
        }

    var lineSpacingExtra
        get() = shared.lineSpacingExtra
        set(value) {
            shared.lineSpacingExtra = value
        }

    var paragraphSpacing
        get() = shared.paragraphSpacing
        set(value) {
            shared.paragraphSpacing = value
        }

    /**
     * 标题位置 0:居左 1:居中 2:隐藏
     */
    var titleMode
        get() = shared.titleMode
        set(value) {
            shared.titleMode = value
        }
    var titleSize
        get() = shared.titleSize
        set(value) {
            shared.titleSize = value
        }

    /**
     * 是否标题居中
     */
    val isMiddleTitle get() = shared.isMiddleTitle

    var titleTopSpacing
        get() = shared.titleTopSpacing
        set(value) {
            shared.titleTopSpacing = value
        }

    var titleBottomSpacing
        get() = shared.titleBottomSpacing
        set(value) {
            shared.titleBottomSpacing = value
        }

    var paragraphIndent
        get() = shared.paragraphIndent
        set(value) {
            shared.paragraphIndent = value
        }

    var underline
        get() = shared.underline
        set(value) {
            shared.underline = value
        }

    var paddingBottom
        get() = shared.paddingBottom
        set(value) {
            shared.paddingBottom = value
        }

    var paddingLeft
        get() = shared.paddingLeft
        set(value) {
            shared.paddingLeft = value
        }

    var paddingRight
        get() = shared.paddingRight
        set(value) {
            shared.paddingRight = value
        }

    var paddingTop
        get() = shared.paddingTop
        set(value) {
            shared.paddingTop = value
        }

    var headerPaddingBottom
        get() = shared.headerPaddingBottom
        set(value) {
            shared.headerPaddingBottom = value
        }

    var headerPaddingLeft
        get() = shared.headerPaddingLeft
        set(value) {
            shared.headerPaddingLeft = value
        }

    var headerPaddingRight
        get() = shared.headerPaddingRight
        set(value) {
            shared.headerPaddingRight = value
        }

    var headerPaddingTop
        get() = shared.headerPaddingTop
        set(value) {
            shared.headerPaddingTop = value
        }

    var footerPaddingBottom
        get() = shared.footerPaddingBottom
        set(value) {
            shared.footerPaddingBottom = value
        }

    var footerPaddingLeft
        get() = shared.footerPaddingLeft
        set(value) {
            shared.footerPaddingLeft = value
        }

    var footerPaddingRight
        get() = shared.footerPaddingRight
        set(value) {
            shared.footerPaddingRight = value
        }

    var footerPaddingTop
        get() = shared.footerPaddingTop
        set(value) {
            shared.footerPaddingTop = value
        }

    var showHeaderLine
        get() = shared.showHeaderLine
        set(value) {
            shared.showHeaderLine = value
        }

    var showFooterLine
        get() = shared.showFooterLine
        set(value) {
            shared.showFooterLine = value
        }
}
