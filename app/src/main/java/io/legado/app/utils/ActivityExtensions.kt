@file:Suppress("DEPRECATION")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
import androidx.fragment.app.DialogFragment
import io.legado.app.help.i18n.androidAppString
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay

val WindowManager.windowSize: DisplayMetrics
    get() {
        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics: WindowMetrics = currentWindowMetrics
            val insets = windowMetrics.windowInsets
                .getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
            val windowWidth = windowMetrics.bounds.width()
            val windowHeight = windowMetrics.bounds.height()
            var insetsWidth = insets.left + insets.right
            var insetsHeight = insets.top + insets.bottom
            if (windowWidth > windowHeight) {
                val tmp = insetsWidth
                insetsWidth = insetsHeight
                insetsHeight = tmp
            }
            displayMetrics.widthPixels = windowWidth - insetsWidth
            displayMetrics.heightPixels = windowHeight - insetsHeight
        } else {
            defaultDisplay.getMetrics(displayMetrics)
        }
        return displayMetrics
    }

/**
 * 真实屏幕尺寸 (含状态栏/导航栏/cutout 的完整物理区): R+ `maximumWindowMetrics`,
 * 低版本 `getRealMetrics` (对照原版 setCoverFromUri 同款)。
 * 与 [windowSize] (扣系统栏的应用可用区) 相对 —— 启动图 edge-to-edge 铺满全屏, 裁剪/解码
 * 必须用本扩展, 否则竖屏构图少算状态栏一条导致比例错位。
 */
fun WindowManager.realScreenSize(): android.graphics.Point {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = maximumWindowMetrics.bounds
        android.graphics.Point(bounds.width(), bounds.height())
    } else {
        val metrics = DisplayMetrics()
        defaultDisplay.getRealMetrics(metrics)
        android.graphics.Point(metrics.widthPixels, metrics.heightPixels)
    }
}

/**
 * 内容铺到系统栏之后 (edge-to-edge): insets 全量派发给应用, 由各界面自行避让
 * (Compose 侧 statusBarsPadding / navigationBarsPadding / imePadding)。
 *
 * 只留官方一条开关 —— 旧实现 `setDecorFitsSystemWindows(true)` 与手写
 * `LAYOUT_FULLSCREEN|LAYOUT_STABLE` 语义相反 (前者要 decor 代为避让, 后者要铺满)。
 * 不用 androidx `enableEdgeToEdge()`: 它会按 SystemBarStyle 自己决定两栏底色与
 * 对比度保护, 而底色归 ThemeStore (setStatusBarColorAuto / setNavigationBarColorAuto),
 * 两者叠加会出现系统栏底色与主题色不一致。
 */
fun Activity.edgeToEdge() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.clearFlags(
        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
    )
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    // 挖孔模式: Android 15 起非浮动窗口一律按 ALWAYS 解释, 这里显式声明同一意图,
    // 不依赖系统兜底 (API 30+ 有 ALWAYS; 28/29 用 SHORT_EDGES 让内容进入挖孔区)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    // 系统栏对比度保护: 状态栏与三键导航栏默认会盖一层半透明底, 关掉后系统栏区域
    // 完全透出内容 (底栏色/阅读背景色直通), 与手势导航行为一致 —— 对照官方
    // "Display content edge-to-edge in views" 的 Create transparent system bars
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
}

/**
 * 设置状态栏颜色
 */
fun Activity.setStatusBarColorAuto(
    @ColorInt color: Int,
    fullScreen: Boolean
) {
    val isLightBar = ColorUtils.isColorLight(color)
    if (fullScreen) {
        window.statusBarColor = Color.TRANSPARENT
    } else {
        window.statusBarColor = color
    }
    setLightStatusBar(isLightBar)
}

fun Activity.setLightStatusBar(isLightBar: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.let {
            if (isLightBar) {
                it.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                it.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        }
    }
    val decorView = window.decorView
    val systemUiVisibility = decorView.systemUiVisibility
    if (isLightBar) {
        decorView.systemUiVisibility =
            systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    } else {
        decorView.systemUiVisibility =
            systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
    }
}

/**
 * 设置导航栏颜色
 */
@SuppressLint("InlinedApi") // SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR 是内联常量, API<26 被忽略, 安全
fun Activity.setNavigationBarColorAuto(@ColorInt color: Int) {
    window.navigationBarColor = color
    setLightNavigationBar(ColorUtils.isColorLight(color))
}

/** 导航栏图标明暗 (与底色解耦: 壁纸页底色透明, 明暗按壁纸贴边像素判) */
@SuppressLint("InlinedApi") // SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR 是内联常量, API<26 被忽略, 安全
fun Activity.setLightNavigationBar(isLightBor: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.let {
            if (isLightBor) {
                it.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else {
                it.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }
        }
    }
    val decorView = window.decorView
    var systemUiVisibility = decorView.systemUiVisibility
    systemUiVisibility = if (isLightBor) {
        systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    } else {
        systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
    }
    decorView.systemUiVisibility = systemUiVisibility
}

fun Activity.keepScreenOn(on: Boolean) {
    val isScreenOn =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
    if (on == isScreenOn) return
    if (on) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

fun Activity.toggleSystemBar(show: Boolean) {
    WindowCompat.getInsetsController(window, window.decorView).run {
        if (show) {
            show(WindowInsetsCompat.Type.systemBars())
        } else {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/////以下方法需要在View完全被绘制出来之后调用，否则判断不了,在比如 onWindowFocusChanged（）方法中可以得到正确的结果/////

/**
 * 显示目录help下的帮助文档
 * 帮助文档对话框已下沉 shared (HelpDialog): 经 help Overlay 读 web/help/md/{fileName}.md 渲染
 */
fun AppCompatActivity.showHelp(fileName: String) {
    AppNavigatorProviders.get().showOverlay(
        AppOverlay.Dialog(key = "help", payload = fileName)
    )
}

/**
 * 显示导出成功对话框
 */
fun Activity.showExportSuccess(uri: Uri) {
    alert(androidAppString("export_success")) {
        if (uri.toString().isAbsUrl()) {
            setMessage(io.legado.app.help.DirectLinkUpload.getSummary())
        }
        editTextView(hint = androidAppString("path"), text = uri.toString())
        okButton {
            sendToClip(uri.toString())
        }
    }
}

/**
 * 显示导出成功对话框 (Fragment版本)
 */
fun androidx.fragment.app.Fragment.showExportSuccess(uri: Uri) {
    alert(androidAppString("export_success")) {
        if (uri.toString().isAbsUrl()) {
            setMessage(io.legado.app.help.DirectLinkUpload.getSummary())
        }
        editTextView(hint = androidAppString("path"), text = uri.toString())
        okButton {
            requireContext().sendToClip(uri.toString())
        }
    }
}
