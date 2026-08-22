package io.legado.app.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import androidx.core.graphics.drawable.toDrawable
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.compose.platform.AndroidAppConfigProvider
import io.legado.app.ui.compose.platform.AndroidEventBusProvider
import io.legado.app.ui.compose.platform.AndroidPreferenceStoreProvider
import io.legado.app.ui.compose.platform.AndroidThemeStoreProvider
import io.legado.app.ui.compose.platform.AppKeyRouter
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.disableAutoFill
import io.legado.app.utils.edgeToEdge
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.windowSize

/**
 * 纯 Compose 宿主 Activity：主题背景/全屏/系统栏着色/背景图/EventBus RECREATE 观察，
 * 内容由 [Content] 提供。残留 View 岛（alert customView 等）着色改由构造点
 * 显式调用 lib/theme 的 applyThemeTree，不再有 Factory2 注入。
 */
abstract class BaseComposeActivity(
    val fullScreen: Boolean = true,
    private val theme: Theme = Theme.Auto,
    private val toolBarTheme: Theme = Theme.Auto,
    private val imageBg: Boolean = true
) : AppCompatActivity() {

    private var needRecreate = false

    val isInMultiWindow: Boolean
        @SuppressLint("ObsoleteSdkInt")
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                isInMultiWindowMode
            } else {
                false
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        initTheme()
        window.decorView.disableAutoFill()
        super.onCreate(savedInstanceState)
        setupSystemBar()
        upBackgroundImage()
        setContent {
            // 注入 Android actual Provider，供 commonMain AppTheme 通过 LocalXxx 取依赖
            val themeStoreProvider = remember { AndroidThemeStoreProvider() }
            val appConfigProvider = remember { AndroidAppConfigProvider() }
            val eventBusProvider = remember { AndroidEventBusProvider() }
            val preferenceStoreProvider = remember { AndroidPreferenceStoreProvider() }
            CompositionLocalProvider(
                // Android 12+ 默认 stretch overscroll: 越界下拉整体下移, 露出窗口背景
                // (主题黑/白); 统一禁用 overscroll 视觉效果 (对齐原版 View 体系无边行为),
                // 只改 Android 入口, iOS rubber-band / 桌面 overscroll 不受影响
                LocalOverscrollFactory provides null,
                LocalThemeStoreProvider provides themeStoreProvider,
                LocalAppConfigProvider provides appConfigProvider,
                LocalEventBusProvider provides eventBusProvider,
                LocalPreferenceStoreProvider provides preferenceStoreProvider,
            ) {
                AppTheme { Content() }
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
        observeLiveBus()
        onActivityCreated(savedInstanceState)
    }

    /** 页面内容，子类以纯 Compose 提供(通常含 AppTitleBar)。 */
    @Composable
    abstract fun Content()

    /** 内容已挂载后的初始化钩子(如订阅数据流)，默认空实现。 */
    open fun onActivityCreated(savedInstanceState: Bundle?) {}

    override fun onStart() {
        super.onStart()
        if (needRecreate) {
            needRecreate = false
            recreate()
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        setupSystemBar()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupSystemBar()
    }

    open fun initTheme() {
        // 在 super.onCreate 之前注入 Window 背景，防止过渡瞬间的黑屏/白屏闪烁
        if (theme != Theme.Transparent) {
            val bg = ThemeConfig.curBgImagePath
            if (bg.isNullOrBlank()) {
                window.setBackgroundDrawable(backgroundColor.toDrawable())
            } else {
                window.setBackgroundDrawable(null)
            }
        } else {
            window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }

        val isDark = when (theme) {
            Theme.Dark -> true
            Theme.Light -> false
            Theme.Transparent -> AppConfig.isNightTheme
            else -> AppConfig.isNightTheme
        }

        when (theme) {
            Theme.Transparent -> setTheme(R.style.AppTheme_Transparent)
            else -> {
                if (isDark) {
                    setTheme(R.style.AppTheme_Dark)
                } else {
                    if (ColorUtils.isColorLight(backgroundColor)) {
                        setTheme(R.style.AppTheme_Light)
                    } else {
                        setTheme(R.style.AppTheme_Dark)
                    }
                }
            }
        }
    }

    open fun upBackgroundImage() {
        if (imageBg) {
            try {
                ThemeConfig.getBgDrawable(this, windowManager.windowSize)?.let {
                    window.decorView.background = it
                }
            } catch (_: OutOfMemoryError) {
                toastOnUi("背景图片太大,内存溢出")
            } catch (e: Exception) {
                AppLog.put("加载背景出错\n${e.localizedMessage}", e)
            }
        }
    }

    open fun setupSystemBar() {
        if (fullScreen && !isInMultiWindow) {
            edgeToEdge()
        }
        val bg = ThemeConfig.curBgImagePath
        val statusBarColor =
            if (bg.isNullOrBlank()) ThemeStore.statusBarColor else Color.TRANSPARENT
        setStatusBarColorAuto(statusBarColor, fullScreen)
        if (toolBarTheme == Theme.Dark) {
            setLightStatusBar(false)
        } else if (toolBarTheme == Theme.Light) {
            setLightStatusBar(true)
        }
        upNavigationBarColor()
    }

    open fun upNavigationBarColor() {
        val bg = ThemeConfig.curBgImagePath
        val navColor =
            if (bg.isNullOrBlank()) ThemeStore.navigationBarColor else Color.TRANSPARENT
        setNavigationBarColorAuto(navColor)
    }

    open fun observeLiveBus() {
        observeEvent<String>(EventBus.RECREATE) {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                recreate()
            } else {
                needRecreate = true
            }
        }
    }

    /**
     * 页面级快捷键的 Activity 层桥接：Android 触摸模式下硬件键能否进入 Compose 焦点链
     * 不可靠（2026-08 用户实测音量键不翻页），故在 Activity 层无条件收键后送
     * [AppKeyRouter.dispatchPlatform]（统一路由: 全屏 Esc → 统一返回 → F5 刷新 →
     * 快捷键栈捕获+冒泡两阶段）。对照原版 ReadBookActivity.dispatchKeyEvent →
     * keyHandler.dispatchKeyEvent 的 Activity 层拦截语义（原版连 onKeyUp 也消费，迁移版
     * repeatPolicy=TRIGGER 已内建抬起消费语义，KeyDown/KeyUp 都直接送分发即可）。
     *
     * 注册栈只在页面组合时非空（如阅读页 [io.legado.app.ui.compose.platform.VolumeKeyPageTurnHandler]），
     * 故桥接自然只在该页面生效，无全局开关。两阶段均从 Activity 层可达（不再依赖焦点链,
     * 顺带修复阅读/漫画方向键在无焦点场景的响应）; 未命中时交还原链路（系统音量等），
     * 带修饰键/功能键的组合由捕获阶段消费, 不抢占输入框文本输入。
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val composeEvent = KeyEvent(event)
        if (AppKeyRouter.dispatchPlatform(composeEvent)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.dispatchTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        }
    }

    override fun finish() {
        currentFocus?.hideSoftInput()
        super.finish()
    }
}
