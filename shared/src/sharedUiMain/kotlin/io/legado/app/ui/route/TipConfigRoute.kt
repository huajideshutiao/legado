package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.config.TipConfigController
import io.legado.app.ui.book.read.config.TipConfigScreen
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * 提示信息配置 shared 路由入口。
 *
 * [TipConfigController] 桥接 [ReadBookConfigProviders] (shared 版 ReadBookConfig,
 * 已含 app 端 ReadTipConfig 转发的全部 tip 字段),
 * onPostConfig 经 [ReadBookEvents.postConfig] 通知渲染刷新;
 * Route 退出时 [io.legado.app.help.config.ReadBookConfigShared.save] 持久化,
 * 对齐 app 端 TipConfigDialog dismiss -> ReadBookConfig.save()。
 */
@Composable
fun TipConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
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
        onBack = { navigator.pop() },
        onPostConfig = { changes -> ReadBookEvents.postConfig(changes) },
    )
}
