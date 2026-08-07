package io.legado.app.ui.route

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.config.ClickActionConfig
import io.legado.app.ui.book.read.config.ClickActionDialog
import io.legado.app.ui.book.read.config.MoreConfigScreen
import io.legado.app.ui.book.read.page.detectClickArea
import io.legado.app.ui.compose.component.AppBottomSheetDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.root.PlatformCapabilityProviders
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.page_touch_slop_dialog_title
import legado.shared.generated.resources.page_touch_slop_summary
import org.jetbrains.compose.resources.stringResource

/**
 * 阅读界面更多设置底部弹窗形态 (对照原版 MoreConfigDialog:
 * BasePrefDialogFragment + setupAsBottomDialog(480dp): 全宽贴底、固定高 480dp、
 * 无圆角 (applyFilletBackground=false) + 背景 R.color.background, 无标题栏)。
 * 由阅读菜单"设置"按钮弹起, 行为与正文 [MoreConfigBody] 一致。
 */
@Composable
fun MoreConfigDialogHost(
    onDismiss: () -> Unit,
) {
    val pref = LocalPreferenceStoreProvider.current
    val readBookConfig = ReadBookConfigProviders.get()
    AppBottomSheetDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
        maxHeight = 480.dp,
    ) {
        AppTheme {
            // 原版 setupAsBottomDialog: MATCH_PARENT 全宽 + 高 480dp + 无圆角 (BasePrefDialogFragment
            // applyFilletBackground=false); 内容背景 bottomBackground (createPrefContainer)
            Surface(
                shape = RoundedCornerShape(0.dp),
                color = AppTheme.colors.bottomBackground,
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            ) {
                MoreConfigBody(
                    pref = pref,
                    readBookConfig = readBookConfig,
                )
            }
        }
    }
}

/** 更多设置正文 (Screen + 内嵌对话框), 路由/弹窗两形态共用 */
@Composable
private fun MoreConfigBody(
    pref: PreferenceStoreProvider,
    readBookConfig: ReadBookConfigShared,
) {
    // 触摸灵敏度摘要: 系统scaledTouchSlop格式化 (对照 app 端 page_touch_slop_summary)
    val slopSquare = PlatformCapabilityProviders.getOrNull()?.getScaledTouchSlop() ?: 0
    val pageTouchSlopSummary =
        stringResource(Res.string.page_touch_slop_summary, slopSquare.toString())
    var showPageTouchSlop by remember { mutableStateOf(false) }
    var showClickRegional by remember { mutableStateOf(false) }

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

    // 翻页触发距离 NumberPicker (对齐 app 端 pickPageTouchSlop)
    if (showPageTouchSlop) {
        NumberPickerDialog(
            title = stringResource(Res.string.page_touch_slop_dialog_title),
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
                // 对照原版配置页 onDestroy → AppConfig.detectClickArea: 变更后立即校验,
                // 九宫格全部非 0 时强制恢复中间格为菜单 + toast
                detectClickArea()
            },
            onDismiss = { showClickRegional = false },
        )
    }
}
