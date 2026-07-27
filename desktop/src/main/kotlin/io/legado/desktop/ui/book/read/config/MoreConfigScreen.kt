package io.legado.desktop.ui.book.read.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.book.read.config.ClickActionConfig
import io.legado.app.ui.book.read.config.ClickActionDialog
import io.legado.app.ui.book.read.config.MoreConfigScreen as SharedMoreConfigScreen
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.dialog.NumberPickerDialog

/**
 * 桌面端"更多配置" Screen 入口 (包装 shared/sharedUiMain 的 [SharedMoreConfigScreen])。
 *
 * # 职责
 *
 * - 在 [SharedMoreConfigScreen] 之上加 [AppTitleBar] (标题"更多配置" + 返回按钮)
 * - 装配 pageTouchSlopSummary (从 prefs 读取 pageTouchSlop, 0=系统默认)
 * - 装配 1 个 NumberPicker 弹窗: pageTouchSlop(0..9999, 0=系统默认)
 * - 装配 1 个 Dialog: ClickActionDialog (点击区域配置),
 *   回调 onClickRegionalConfig 弹出对应 shared 共享 Dialog
 * - 注入 4 个 DesktopXxxProvider 供 commonMain 的 [AppTheme] /
 *   [SharedMoreConfigScreen] 内部 PreferenceScreen 通过 LocalXxx 取依赖
 *
 * # 简化项
 *
 * - pageTouchSlopSummary: app 端用 ViewConfiguration.get(context).scaledTouchSlop +
 *   getString(R.string.page_touch_slop_summary, slopSquare), 桌面端无 Android
 *   ViewConfiguration, 直接显示 prefs 数值
 * - onPageTouchSlop: 范围 0..9999 (app 端原 max=9999, min=0), 写 prefs.pageTouchSlop 后
 *   不发 ReadBookEvents.postConfig(ReadConfigChange.PAGE_SLOP) (桌面端阅读页直接读 prefs)
 * - onClickRegionalConfig: app 端 ReadBookActivity.showClickRegionalConfig()
 *   (点击区域配置 Dialog), 桌面端弹 shared 共享 [ClickActionDialog], 直接读写 prefs
 *   (AppConfigAccessor 接口未暴露 clickActionXX 字段, 走 PreferenceProvider)
 *
 * @param onBack 返回回调 (切回 SETTINGS 路由, 由 DesktopApp 注入)
 */
@Composable
fun MoreConfigScreen(onBack: () -> Unit) {
    // 桌面端 Provider 注入: 供 commonMain 的 AppTheme / SharedMoreConfigScreen 取依赖
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AppTitleBar(
                        title = rememberString("more_config"),
                        onBack = onBack,
                    )
                    MoreConfigContent()
                }
            }
        }
    }
}

/**
 * 装配 pageTouchSlopSummary + 3 个回调 + 3 个 Dialog, 位置传参调用 [SharedMoreConfigScreen]。
 *
 * 与 app 端 MoreConfigDialog 内 MoreConfigScreen(...) 调用对齐, 差异见顶层 KDoc。
 */
@Composable
private fun MoreConfigContent() {
    val prefs = remember { PreferenceProviders.get() }
    val pageTouchSlopDialogTitle = rememberString("page_touch_slop_dialog_title")

    // pageTouchSlop 当前值 + 显隐状态 (mutableIntStateOf 让 summary 重组)
    // 默认值 0 = 系统默认 (app 端 AppConfig.pageTouchSlop 默认 0)
    var pageTouchSlop by remember {
        mutableIntStateOf(prefs.getInt(PreferKey.pageTouchSlop, 0))
    }
    var showPageTouchSlopDialog by remember { mutableStateOf(false) }
    // 点击区域配置 Dialog 的显隐状态 (接入 shared 共享的 ClickActionDialog)
    var showClickActionDialog by remember { mutableStateOf(false) }
    // summary 直接显示数值 (桌面端无 ViewConfiguration.scaledTouchSlop, 0=系统默认)
    val pageTouchSlopSummary = pageTouchSlop.toString()

    SharedMoreConfigScreen(
        pageTouchSlopSummary = pageTouchSlopSummary,
        onPageTouchSlop = { showPageTouchSlopDialog = true },
        onClickRegionalConfig = {
            // 弹出 ClickActionDialog (shared 共享, 替代 app 端 ReadBookActivity.showClickRegionalConfig())
            showClickActionDialog = true
        },
    )

    // 1 个 NumberPickerDialog (shared 共享, 替代 app 端 showNumberPicker)
    // app 端范围: pageTouchSlop 0..9999, 默认 0=系统默认
    if (showPageTouchSlopDialog) {
        NumberPickerDialog(
            title = pageTouchSlopDialogTitle,
            value = pageTouchSlop,
            range = 0..9999,
            onConfirm = {
                pageTouchSlop = it
                prefs.putInt(PreferKey.pageTouchSlop, it)
                showPageTouchSlopDialog = false
            },
            onDismiss = { showPageTouchSlopDialog = false },
        )
    }

    // 点击区域配置 Dialog (shared 共享, 替代 app 端 ClickActionConfigDialog)
    // AppConfigAccessor 接口未暴露 clickActionXX 字段, 桌面端直接经
    // PreferenceProvider 读写 prefs (与 app 端 AppConfig.clickActionXX = cachedIntPref 语义对齐)
    // 默认值与 AppConfig 一致: tl=2/tc=2/tr=1/ml=2/mc=0/mr=1/bl=2/bc=1/br=1
    if (showClickActionDialog) {
        val clickActionConfig = ClickActionConfig(
            tl = prefs.getInt(PreferKey.clickActionTL, 2),
            tc = prefs.getInt(PreferKey.clickActionTC, 2),
            tr = prefs.getInt(PreferKey.clickActionTR, 1),
            ml = prefs.getInt(PreferKey.clickActionML, 2),
            mc = prefs.getInt(PreferKey.clickActionMC, 0),
            mr = prefs.getInt(PreferKey.clickActionMR, 1),
            bl = prefs.getInt(PreferKey.clickActionBL, 2),
            bc = prefs.getInt(PreferKey.clickActionBC, 1),
            br = prefs.getInt(PreferKey.clickActionBR, 1),
        )
        ClickActionDialog(
            clickActionConfig = clickActionConfig,
            onConfirm = { newCfg ->
                // 即时写回 9 个 clickActionXX 字段 (与原版 AppConfig.clickActionXX = it 语义对齐)
                prefs.putInt(PreferKey.clickActionTL, newCfg.tl)
                prefs.putInt(PreferKey.clickActionTC, newCfg.tc)
                prefs.putInt(PreferKey.clickActionTR, newCfg.tr)
                prefs.putInt(PreferKey.clickActionML, newCfg.ml)
                prefs.putInt(PreferKey.clickActionMC, newCfg.mc)
                prefs.putInt(PreferKey.clickActionMR, newCfg.mr)
                prefs.putInt(PreferKey.clickActionBL, newCfg.bl)
                prefs.putInt(PreferKey.clickActionBC, newCfg.bc)
                prefs.putInt(PreferKey.clickActionBR, newCfg.br)
            },
            onDismiss = { showClickActionDialog = false },
        )
    }
}
