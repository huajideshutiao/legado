package io.legado.app.base

import android.content.Context
import android.graphics.Color
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.drawable.toDrawable
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.filletBackground
import io.legado.app.ui.compose.platform.AndroidAppConfigProvider
import io.legado.app.ui.compose.platform.AndroidEventBusProvider
import io.legado.app.ui.compose.platform.AndroidThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 窗口级 Compose 对话框, 命令式入口 (alert / selector DSL) 的承载。
 *
 * 对话框窗口挂在创建它的 Activity 上, 归属由窗口决定: 不需要请求队列、渲染宿主与前台判据,
 * 界面去后台 (如拉起系统选择器) 期间窗口与内容照旧存在。ComponentDialog 自带
 * Lifecycle / SavedState / ViewTree owner 并推到 RESUMED, ComposeView 可直接重组。
 *
 * 窗口规格与居中对话框统一: 宽 0.9 屏宽 (上限 800dp)、高随内容自适应并封顶 0.8 屏高、
 * 8dp 圆角; 墨水屏模式清暗化与进出场动画。
 */
open class ComposeDialog(
    context: Context,
    private val fullWidth: Boolean = true,
    private val applyFilletBackground: Boolean = true,
) : ComponentDialog(context) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    fun setComposeContent(content: @Composable () -> Unit) {
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // 注入 Android actual Provider, 供 commonMain AppTheme 通过 LocalXxx 取依赖
                val themeStoreProvider = remember { AndroidThemeStoreProvider() }
                val appConfigProvider = remember { AndroidAppConfigProvider() }
                val eventBusProvider = remember { AndroidEventBusProvider() }
                CompositionLocalProvider(
                    LocalThemeStoreProvider provides themeStoreProvider,
                    LocalAppConfigProvider provides appConfigProvider,
                    LocalEventBusProvider provides eventBusProvider,
                ) {
                    AppTheme {
                        val maxH = with(LocalDensity.current) {
                            (context.resources.displayMetrics.heightPixels * 0.8f).toDp()
                        }
                        Box(Modifier.heightIn(max = maxH)) { content() }
                    }
                }
            }
            if (!AppConfig.isEInkMode && applyFilletBackground) {
                background = context.filletBackground
                clipToOutline = true
            }
        }
        setContentView(composeView)
    }

    override fun onStart() {
        super.onStart()
        window?.let {
            it.decorView.setPadding(0, 0, 0, 0)
            it.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            val attr = it.attributes
            if (AppConfig.isEInkMode) {
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attr.dimAmount = 0.0f
                attr.windowAnimations = 0
            } else {
                attr.windowAnimations = R.style.Animation_Dialog
            }
            it.attributes = attr
            val dm = context.resources.displayMetrics
            if (fullWidth) {
                val width = (dm.widthPixels * 0.9).toInt().coerceAtMost((800 * dm.density).toInt())
                it.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            } else {
                it.setLayout(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
            }
        }
    }
}
