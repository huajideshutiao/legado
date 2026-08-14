package io.legado.app.ui.route

import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.config.TipConfigController
import io.legado.app.ui.book.read.config.TipConfigScreen
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 提示信息配置弹窗形态 (对照原版 TipConfigDialog: BaseDialogFragment 居中对话框)。
 * 由界面设置弹窗"提示"入口弹起；界面设置弹窗在信息弹窗打开动画完成后关闭
 * (见 [io.legado.app.ui.route.ReadStyleDialogHost])。
 *
 * 半透明形态：不压暗阅读页 + 背景半透明，让阅读页的页眉/页脚透过弹窗隐约可见
 * (用户需求)。顶栏已移除，点弹窗外区域 / 返回键关闭。
 */
@Composable
fun TipConfigDialogHost(
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
        // 半透明弹窗：不压暗阅读页，页眉/页脚透过弹窗隐约可见
        // (对照原版 BaseDialogFragment 保留 dim；桌面/iOS/鸿蒙 CMP Dialog 自带 0.6
        // scrim 无法关闭，属平台限制)
        dim = false,
    ) {
        AppTheme {
            // 原版 BaseDialogFragment: 0.9 宽居中 + filletBackground 8dp 圆角;
            // 顶栏已移除（用户需求），背景按 [TipConfigDialogTranslucency] 半透明
            Surface(
                shape = DesignTokens.shapeDefault,
                color = AppTheme.colors.background.copy(alpha = TipConfigDialogTranslucency),
                modifier = Modifier.appDialogSize(),
            ) {
                TipConfigContent()
            }
        }
    }
}

/** 信息设置弹窗背景不透明度（<1 时阅读页透过弹窗隐约可见，可调）。 */
private const val TipConfigDialogTranslucency = 0.8f

/**
 * 提示配置正文 (路由/弹窗两形态共用)。
 *
 * [TipConfigController] 桥接 [ReadBookConfigProviders] (shared 版 ReadBookConfig,
 * 已含 app 端 ReadTipConfig 转发的全部 tip 字段),
 * onPostConfig 经 [ReadBookEvents.postConfig] 通知渲染刷新;
 * 退出时 [io.legado.app.help.config.ReadBookConfigShared.save] 持久化,
 * 对齐 app 端 TipConfigDialog dismiss -> ReadBookConfig.save()。
 */
@Composable
fun TipConfigContent() {
    val readBookConfig = ReadBookConfigProviders.get()
    val controller = remember {
        object : TipConfigController {
            override var titleMode: Int
                get() = readBookConfig.titleMode
                set(value) {
                    readBookConfig.titleMode = value
                }

            override var titleSize: Int
                get() = readBookConfig.titleSize
                set(value) {
                    readBookConfig.titleSize = value
                }

            override var titleTop: Int
                get() = readBookConfig.titleTopSpacing
                set(value) {
                    readBookConfig.titleTopSpacing = value
                }

            override var titleBottom: Int
                get() = readBookConfig.titleBottomSpacing
                set(value) {
                    readBookConfig.titleBottomSpacing = value
                }

            override var headerMode: Int
                get() = readBookConfig.headerMode
                set(value) {
                    readBookConfig.headerMode = value
                }

            override var footerMode: Int
                get() = readBookConfig.footerMode
                set(value) {
                    readBookConfig.footerMode = value
                }

            override var tipHeaderLeft: Int
                get() = readBookConfig.tipHeaderLeft
                set(value) {
                    readBookConfig.tipHeaderLeft = value
                }

            override var tipHeaderMiddle: Int
                get() = readBookConfig.tipHeaderMiddle
                set(value) {
                    readBookConfig.tipHeaderMiddle = value
                }

            override var tipHeaderRight: Int
                get() = readBookConfig.tipHeaderRight
                set(value) {
                    readBookConfig.tipHeaderRight = value
                }

            override var tipFooterLeft: Int
                get() = readBookConfig.tipFooterLeft
                set(value) {
                    readBookConfig.tipFooterLeft = value
                }

            override var tipFooterMiddle: Int
                get() = readBookConfig.tipFooterMiddle
                set(value) {
                    readBookConfig.tipFooterMiddle = value
                }

            override var tipFooterRight: Int
                get() = readBookConfig.tipFooterRight
                set(value) {
                    readBookConfig.tipFooterRight = value
                }

            override var tipColor: Int
                get() = readBookConfig.tipColor
                set(value) {
                    readBookConfig.tipColor = value
                }

            override var tipDividerColor: Int
                get() = readBookConfig.tipDividerColor
                set(value) {
                    readBookConfig.tipDividerColor = value
                }
        }
    }

    // 退出时持久化 (对齐 app 端 TipConfigDialog dismiss -> ReadBookConfig.save())
    DisposableEffect(Unit) {
        onDispose { readBookConfig.save() }
    }

    TipConfigScreen(
        controller = controller,
        onPostConfig = { changes -> ReadBookEvents.postConfig(changes) },
    )
}
