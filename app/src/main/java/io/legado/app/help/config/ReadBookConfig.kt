package io.legado.app.help.config

import android.graphics.Color.blue
import android.graphics.Color.green
import android.graphics.Color.red
import android.graphics.Color.rgb
import android.graphics.NinePatch
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import androidx.annotation.Keep
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import io.legado.app.App
import io.legado.app.R
import io.legado.app.constant.PageAnim
import io.legado.app.help.config.ReadBookConfig.Config
import io.legado.app.help.config.ReadBookConfig.bg
import io.legado.app.help.config.ReadBookConfig.bgMeanColor
import io.legado.app.help.config.ReadBookConfig.upBg
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.compose.platform.AndroidPreferenceStoreProvider
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.RemoteAssetsUtils
import io.legado.app.utils.centerCrop
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getRepresentativeColor
import io.legado.app.utils.printOnDebug
import java.io.File

/**
 * 阅读界面配置 (app 端薄壳)。
 *
 * 数据/持久化/导入导出已下沉 [ReadBookConfigShared]（configList + shareConfig +
 * readConfig.json/shareReadConfig.json 读写 + zip 导入导出），本 object 只做转发，
 * 保证 app 消费方 `ReadBookConfig.xxx` 写法不变。
 *
 * 留在 app 端的只有 Android 独占的背景渲染：[bg] / [bgMeanColor] / [upBg] /
 * [curBgDrawable]（Drawable / NinePatch / BitmapUtils / RemoteAssetsUtils）。
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
        ReadBookConfigShared(AndroidPreferenceStoreProvider())
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

    /** 当前背景 Drawable（Android 渲染态，由 [upBg] 刷新）。 */
    var bg: Drawable? = null

    /** 背景主色（状态栏/导航栏/仿真翻页取色用）。 */
    var bgMeanColor: Int = 0

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

    /**
     * 刷新背景 Drawable 与 [bgMeanColor]，回收上一张 Bitmap。
     *
     * 位图背景取底部 30% 区域的代表色后各通道 +3（沿用原实现的微调）。
     */
    fun upBg(width: Int, height: Int) {
        val drawable = durConfig.curBgDrawable(width, height)
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            bgMeanColor = try {
                val bitmap = drawable.bitmap
                val cropHeight = (bitmap.height * 0.3).toInt()
                val cropTop = bitmap.height - cropHeight
                val it = bitmap.getRepresentativeColor(0, cropTop, bitmap.width, cropHeight)
                rgb(
                    (red(it) + 3).coerceAtMost(255),
                    (green(it) + 3).coerceAtMost(255),
                    (blue(it) + 3).coerceAtMost(255)
                )
            } catch (e: Exception) {
                e.printOnDebug()
                0
            }
        } else if (drawable is ColorDrawable) {
            bgMeanColor = drawable.color
        }
        val tmp = bg
        bg = drawable
        (tmp as? BitmapDrawable)?.bitmap?.recycle()
    }

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
    var useZhLayout
        get() = shared.useZhLayout
        set(value) {
            shared.useZhLayout = value
        }

    /** 设置页直写 pref 后同步缓存字段 */
    fun reloadHideBarPrefs() = shared.reloadHideBarPrefs()

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

/**
 * 当前生效背景的 Drawable（Android 独占，原 `ReadBookConfig.Config.curBgDrawable`）。
 *
 * bgType: 0 纯色, 1 内置背景（[RemoteAssetsUtils] 远端缓存，未就绪时下载并回放 BG 事件），
 * 2 用户图片（`{externalFiles}/bg` 下文件名或绝对路径）。九宫格图走 [NinePatchDrawable]，
 * 其余 centerCrop 到视口尺寸。
 */
fun ReadStyleConfig.curBgDrawable(width: Int, height: Int): Drawable {
    if (width == 0 || height == 0) {
        return App.instance.getCompatColor(R.color.background).toDrawable()
    }
    var bgDrawable: Drawable? = null
    val resources = App.instance.resources
    try {
        bgDrawable = when (curBgType()) {
            0 -> curBgStr().toColorInt().toDrawable()
            1 -> {
                val bgName = curBgStr()
                val cacheFile = RemoteAssetsUtils.getBgCachePath(bgName)
                val bitmap = if (cacheFile.exists() && cacheFile.length() > 0) {
                    BitmapUtils.decodeBitmap(cacheFile.absolutePath, width, height)
                } else {
                    Coroutine.async {
                        val downloaded = RemoteAssetsUtils.downloadBgIfNeeded(bgName)
                        if (downloaded != null) {
                            ReadBookEvents.postConfig(ReadConfigChange.BG)
                        }
                    }
                    val previewBytes = RemoteAssetsUtils.getBgPreviewBytes(bgName)
                    if (previewBytes != null) {
                        BitmapUtils.decodeBitmap(previewBytes, width, height)
                    } else {
                        null
                    }
                }
                bitmap?.let {
                    val chunk = it.ninePatchChunk
                    if (chunk != null && NinePatch.isNinePatchChunk(chunk)) {
                        NinePatchDrawable(resources, it, chunk, Rect(), null)
                    } else {
                        val result = it.centerCrop(width, height)
                        if (result != it) it.recycle()
                        result.toDrawable(resources)
                    }
                }
            }

            else -> {
                val path = curBgStr().let {
                    if (it.contains(File.separator)) it
                    else FileUtils.getPath(App.instance.externalFiles, "bg", curBgStr())
                }
                val bitmap = BitmapUtils.decodeBitmap(path, width, height)
                bitmap?.let {
                    val chunk = it.ninePatchChunk
                    if (chunk != null && NinePatch.isNinePatchChunk(chunk)) {
                        NinePatchDrawable(resources, it, chunk, Rect(), null)
                    } else {
                        val result = it.centerCrop(width, height)
                        if (result != it) it.recycle()
                        result.toDrawable(resources)
                    }
                }
            }
        }
    } catch (e: OutOfMemoryError) {
        e.printOnDebug()
    } catch (e: Exception) {
        e.printOnDebug()
    }
    return bgDrawable ?: App.instance.getCompatColor(R.color.background).toDrawable()
}
