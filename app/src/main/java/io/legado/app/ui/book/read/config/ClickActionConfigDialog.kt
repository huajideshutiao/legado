package io.legado.app.ui.book.read.config


import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.platform.AndroidAppConfigProvider
import io.legado.app.ui.compose.platform.AndroidEventBusProvider
import io.legado.app.ui.compose.platform.AndroidPreferenceStoreProvider
import io.legado.app.ui.compose.platform.AndroidThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 点击区域设置：全屏 3x3 半透明网格，点击各格弹动作选择。
 * 全屏沉浸窗口自管，不走 BaseComposeDialogFragment 的 0.9w/0.8h 约束。
 */
class ClickActionConfigDialog : DialogFragment() {

    private val actions by lazy {
        linkedMapOf(
            Pair(0, getString(R.string.menu)),
            Pair(1, getString(R.string.next_page)),
            Pair(2, getString(R.string.prev_page)),
            Pair(3, getString(R.string.next_chapter)),
            Pair(4, getString(R.string.previous_chapter)),
            Pair(5, getString(R.string.read_aloud_prev_paragraph)),
            Pair(6, getString(R.string.read_aloud_next_paragraph)),
            Pair(7, getString(R.string.bookmark_add)),
            Pair(9, getString(R.string.replace_state_change)),
            Pair(10, getString(R.string.chapter_list)),
            Pair(11, getString(R.string.search_content)),
            Pair(13, getString(R.string.read_aloud_pause_resume))
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            // 注入 Android actual Provider，供 commonMain AppTheme 通过 LocalXxx 取依赖
            val themeStoreProvider = remember { AndroidThemeStoreProvider() }
            val appConfigProvider = remember { AndroidAppConfigProvider() }
            val eventBusProvider = remember { AndroidEventBusProvider() }
            val preferenceStoreProvider = remember { AndroidPreferenceStoreProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides themeStoreProvider,
                LocalAppConfigProvider provides appConfigProvider,
                LocalEventBusProvider provides eventBusProvider,
                LocalPreferenceStoreProvider provides preferenceStoreProvider,
            ) {
                AppTheme {
                    GridContent()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.decorView.setPadding(0, 0, 0, 0)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
            val attr = window.attributes
            if (AppConfig.isEInkMode) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attr.dimAmount = 0.0f
                attr.windowAnimations = 0
            } else {
                attr.windowAnimations = R.style.Animation_Dialog
            }
            window.attributes = attr
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.run {
                    if (ReadBookConfig.hideNavigationBar) {
                        hide(WindowInsets.Type.navigationBars())
                    }
                    if (ReadBookConfig.hideStatusBar) {
                        hide(WindowInsets.Type.statusBars())
                    }
                }
            }
            @Suppress("DEPRECATION")
            var flag = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            if (ReadBookConfig.hideNavigationBar) {
                flag = flag or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                flag = flag or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            }
            if (ReadBookConfig.hideStatusBar) {
                flag = flag or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
            window.decorView.systemUiVisibility = flag
        }
    }

    @Composable
    private fun GridContent() {
        val context = LocalContext.current
        val translucent = colorResource(R.color.translucent)
        val cardShape = RoundedCornerShape(3.dp)

        // 9 个区域：AppConfig 写入口 + 当前动作值
        val setters = listOf<(Int) -> Unit>(
            { AppConfig.clickActionTL = it }, { AppConfig.clickActionTC = it },
            { AppConfig.clickActionTR = it }, { AppConfig.clickActionML = it },
            { AppConfig.clickActionMC = it }, { AppConfig.clickActionMR = it },
            { AppConfig.clickActionBL = it }, { AppConfig.clickActionBC = it },
            { AppConfig.clickActionBR = it },
        )
        val values = listOf(
            AppConfig.clickActionTL, AppConfig.clickActionTC, AppConfig.clickActionTR,
            AppConfig.clickActionML, AppConfig.clickActionMC, AppConfig.clickActionMR,
            AppConfig.clickActionBL, AppConfig.clickActionBC, AppConfig.clickActionBR,
        )
        val states = remember { values.map { mutableIntStateOf(it) } }

        Box(Modifier.fillMaxSize().background(translucent)) {
            Column(Modifier.fillMaxSize()) {
                repeat(3) { row ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        repeat(3) { col ->
                            val index = row * 3 + col
                            var value by states[index]
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .padding(1.dp)
                                    .background(translucent, cardShape)
                                    .clickable {
                                        context.selector(
                                            getString(R.string.select_action),
                                            actions.values.toList()
                                        ) { _, i ->
                                            val action = actions.keys.toList()[i]
                                            setters[index](action)
                                            value = action
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(actions[value].orEmpty(), color = Color.White)
                            }
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(translucent, cardShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.click_regional_config),
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = rememberPainter("ic_baseline_close"),
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                    modifier = Modifier
                        .padding(4.dp)
                        .clickable { dismissAllowingStateLoss() },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppConfig.detectClickArea()
    }
}
