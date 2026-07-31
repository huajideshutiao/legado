package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.config.PaddingConfigController
import io.legado.app.ui.book.read.config.PaddingConfigScreen
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.padding
import org.jetbrains.compose.resources.stringResource

/**
 * 边距配置 shared 路由入口。
 *
 * [PaddingConfigController] 桥接 [ReadBookConfigProviders] (shared 版 ReadBookConfig),
 * onPostConfig 经 [ReadBookEvents.postConfig] 通知渲染刷新;
 * Route 退出时 [io.legado.app.help.config.ReadBookConfigShared.save] 持久化,
 * 对齐 app 端 PaddingConfigDialog.onDismiss -> ReadBookConfig.save()。
 */
@Composable
fun PaddingConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val readBookConfig = ReadBookConfigProviders.get()
    val controller = remember {
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

    // 退出时持久化 (对齐 app 端 PaddingConfigDialog.onDismiss -> ReadBookConfig.save())
    DisposableEffect(Unit) {
        onDispose { readBookConfig.save() }
    }

    // 顶栏标题 (对照 app 端 R.string.padding, 与 ReadConfigScreen 入口项一致)
    val titleStr = stringResource(Res.string.padding)

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleStr,
            onBack = { navigator.pop() },
        )
        PaddingConfigScreen(
            controller = controller,
            onPostConfig = { changes -> ReadBookEvents.postConfig(changes) },
        )
    }
}
