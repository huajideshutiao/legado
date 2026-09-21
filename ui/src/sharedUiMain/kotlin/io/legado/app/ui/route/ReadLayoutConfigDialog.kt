package io.legado.app.ui.route

import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.config.PaddingConfigController
import io.legado.app.ui.book.read.config.ReadLayoutConfigScreen
import io.legado.app.ui.book.read.config.TipConfigController
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 版面设置弹窗形态（原 边距/提示 两个对话框合并，对照原版 PaddingConfigDialog +
 * TipConfigDialog: BaseDialogFragment 居中对话框，无顶栏）。由界面设置弹窗"信息"入口
 * 弹起，打开期间界面设置弹窗隐藏（对照原版 tvPadding 先 dismissAllowingStateLoss），
 * 关掉后恢复。压暗阅读页（对照原版 BaseDialogFragment 保留 dim），点弹窗外区域 /
 * 返回键关闭。
 */
@Composable
fun ReadLayoutConfigDialogHost(
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            // 原版 BaseDialogFragment: 0.9 宽居中 + filletBackground 8dp 圆角
            Surface(
                shape = DesignTokens.shapeDefault,
                color = AppTheme.colors.background,
                modifier = Modifier.appDialogSize(),
            ) {
                ReadLayoutConfigContent()
            }
        }
    }
}

/**
 * 版面配置正文（原 TipConfigContent / PaddingConfigContent 合并）。
 *
 * [TipConfigController] / [PaddingConfigController] 桥接 [ReadBookConfigProviders]
 * (shared 版 ReadBookConfig，已含 app 端 ReadTipConfig 转发的全部 tip 字段)，
 * onPostConfig 经 [ReadBookEvents.postConfig] 通知渲染刷新；
 * 退出时 [io.legado.app.help.config.ReadBookConfigShared.save] 持久化，
 * 对齐 app 端 TipConfigDialog / PaddingConfigDialog dismiss -> ReadBookConfig.save()。
 */
@Composable
fun ReadLayoutConfigContent() {
    val readBookConfig = ReadBookConfigProviders.get()
    val tipController = remember {
        object : TipConfigController {
            override val textSize: Int
                get() = readBookConfig.textSize

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
    val paddingController = remember {
        object : PaddingConfigController {
            override var showHeaderLine: Boolean
                get() = readBookConfig.showHeaderLine
                set(value) {
                    readBookConfig.showHeaderLine = value
                }

            override var showFooterLine: Boolean
                get() = readBookConfig.showFooterLine
                set(value) {
                    readBookConfig.showFooterLine = value
                }

            override var headerPaddingTop: Int
                get() = readBookConfig.headerPaddingTop
                set(value) {
                    readBookConfig.headerPaddingTop = value
                }

            override var headerPaddingBottom: Int
                get() = readBookConfig.headerPaddingBottom
                set(value) {
                    readBookConfig.headerPaddingBottom = value
                }

            override var headerPaddingLeft: Int
                get() = readBookConfig.headerPaddingLeft
                set(value) {
                    readBookConfig.headerPaddingLeft = value
                }

            override var headerPaddingRight: Int
                get() = readBookConfig.headerPaddingRight
                set(value) {
                    readBookConfig.headerPaddingRight = value
                }

            override var paddingTop: Int
                get() = readBookConfig.paddingTop
                set(value) {
                    readBookConfig.paddingTop = value
                }

            override var paddingBottom: Int
                get() = readBookConfig.paddingBottom
                set(value) {
                    readBookConfig.paddingBottom = value
                }

            override var paddingLeft: Int
                get() = readBookConfig.paddingLeft
                set(value) {
                    readBookConfig.paddingLeft = value
                }

            override var paddingRight: Int
                get() = readBookConfig.paddingRight
                set(value) {
                    readBookConfig.paddingRight = value
                }

            override var footerPaddingTop: Int
                get() = readBookConfig.footerPaddingTop
                set(value) {
                    readBookConfig.footerPaddingTop = value
                }

            override var footerPaddingBottom: Int
                get() = readBookConfig.footerPaddingBottom
                set(value) {
                    readBookConfig.footerPaddingBottom = value
                }

            override var footerPaddingLeft: Int
                get() = readBookConfig.footerPaddingLeft
                set(value) {
                    readBookConfig.footerPaddingLeft = value
                }

            override var footerPaddingRight: Int
                get() = readBookConfig.footerPaddingRight
                set(value) {
                    readBookConfig.footerPaddingRight = value
                }
        }
    }

    // 退出时持久化 (对齐 app 端 TipConfigDialog / PaddingConfigDialog dismiss -> ReadBookConfig.save())
    DisposableEffect(Unit) {
        onDispose { readBookConfig.save() }
    }

    ReadLayoutConfigScreen(
        tipController = tipController,
        paddingController = paddingController,
        onPostConfig = { changes -> ReadBookEvents.postConfig(changes) },
    )
}
