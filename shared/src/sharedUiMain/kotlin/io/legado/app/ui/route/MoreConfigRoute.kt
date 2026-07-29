package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.config.ClickActionConfig
import io.legado.app.ui.book.read.config.ClickActionDialog
import io.legado.app.ui.book.read.config.MoreConfigScreen
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * 阅读界面更多设置 shared 路由入口。
 *
 * 暂无对应 ScreenModel, 触摸灵敏度摘要等状态用 remember { mutableStateOf } 暂存。
 * [MoreConfigScreen.onPageTouchSlop] / [MoreConfigScreen.onClickRegionalConfig] 已接入
 * 内嵌 [NumberPickerDialog] / [ClickActionDialog], 通过 [LocalPreferenceStoreProvider]
 * 读写 prefs, 行为对齐 app 端 MoreConfigDialog。
 *
 * `onPrefChange` 承接 app 端 OnSharedPreferenceChangeListener 副作用, 按 key 派发
 * ReadBookEvents / reloadHideBarPrefs 等通知, 保证设置变更即时生效。
 */
@Composable
fun MoreConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val pref = LocalPreferenceStoreProvider.current
    val readBookConfig = ReadBookConfigProviders.get()
    // 触摸灵敏度摘要: 系统scaledTouchSlop格式化 (对照 app 端 page_touch_slop_summary)
    val slopSquare = PlatformCapabilityProviders.getOrNull()?.getScaledTouchSlop() ?: 0
    val pageTouchSlopSummary = rememberString("page_touch_slop_summary", slopSquare.toString())
    // 顶栏标题 (对照 app 端 R.string.other_setting, 与 ReadConfigScreen 入口项一致)
    val titleStr = rememberString("other_setting")
    var showPageTouchSlop by remember { mutableStateOf(false) }
    var showClickRegional by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleStr,
            onBack = { navigator.pop() },
        )
        MoreConfigScreen(
            pageTouchSlopSummary = pageTouchSlopSummary,
            onPageTouchSlop = { showPageTouchSlop = true },
            onClickRegionalConfig = { showClickRegional = true },
            onPrefChange = { key ->
                // 对照 app 端 MoreConfigDialog.onSharedPreferenceChanged
                when (key) {
                    PreferKey.hideStatusBar, PreferKey.hideNavigationBar -> {
                        readBookConfig.reloadHideBarPrefs()
                        ReadBookEvents.postConfig(
                            ReadConfigChange.SYSTEM_UI, ReadConfigChange.STYLE
                        )
                    }

                    PreferKey.keepLight -> ReadBookEvents.postKeepLightChange()
                    PreferKey.textFullJustify,
                    PreferKey.textBottomJustify,
                    PreferKey.useZhLayout -> {
                        ReadBookEvents.postConfig(ReadConfigChange.LOAD_CONTENT)
                    }

                    PreferKey.doublePageHorizontal -> {
                        // app 端: ChapterProvider.upLayout() + ReadBook.loadContent(false)
                        // shared 端无 ChapterProvider/ReadBook 单例, 走 LOAD_CONTENT 通知读者刷新
                        ReadBookEvents.postConfig(ReadConfigChange.LOAD_CONTENT)
                    }

                    PreferKey.showReadTitleAddition -> ReadBookEvents.postActionBarChange()
                    PreferKey.progressBarBehavior -> ReadBookEvents.postSeekBarChange()
                    // screenOrientation 需 ReadBookActivity.setOrientation, 平台专属, 此处不处理
                }
            },
        )
    }

    // 翻页触发距离 NumberPicker (对齐 app 端 pickPageTouchSlop)
    if (showPageTouchSlop) {
        NumberPickerDialog(
            title = rememberString("page_touch_slop_dialog_title"),
            value = pref.getInt(PreferKey.pageTouchSlop, 0),
            range = 0..9999,
            onConfirm = {
                pref.putInt(PreferKey.pageTouchSlop, it)
                ReadBookEvents.postConfig(listOf(ReadConfigChange.PAGE_SLOP))
            },
            onDismiss = { showPageTouchSlop = false },
        )
    }

    // 点击区域配置对话框 (对齐 app 端 showClickRegionalConfig)
    if (showClickRegional) {
        val config = ClickActionConfig(
            tl = pref.getInt(PreferKey.clickActionTL, 2),
            tc = pref.getInt(PreferKey.clickActionTC, 2),
            tr = pref.getInt(PreferKey.clickActionTR, 1),
            ml = pref.getInt(PreferKey.clickActionML, 2),
            mc = pref.getInt(PreferKey.clickActionMC, 0),
            mr = pref.getInt(PreferKey.clickActionMR, 1),
            bl = pref.getInt(PreferKey.clickActionBL, 2),
            bc = pref.getInt(PreferKey.clickActionBC, 1),
            br = pref.getInt(PreferKey.clickActionBR, 1),
        )
        ClickActionDialog(
            clickActionConfig = config,
            onConfirm = { updated ->
                pref.putInt(PreferKey.clickActionTL, updated.tl)
                pref.putInt(PreferKey.clickActionTC, updated.tc)
                pref.putInt(PreferKey.clickActionTR, updated.tr)
                pref.putInt(PreferKey.clickActionML, updated.ml)
                pref.putInt(PreferKey.clickActionMC, updated.mc)
                pref.putInt(PreferKey.clickActionMR, updated.mr)
                pref.putInt(PreferKey.clickActionBL, updated.bl)
                pref.putInt(PreferKey.clickActionBC, updated.bc)
                pref.putInt(PreferKey.clickActionBR, updated.br)
            },
            onDismiss = { showClickRegional = false },
        )
    }
}
