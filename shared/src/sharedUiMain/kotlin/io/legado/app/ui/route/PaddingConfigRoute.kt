package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.config.PaddingConfigController
import io.legado.app.ui.book.read.config.PaddingConfigScreen
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.padding
import org.jetbrains.compose.resources.stringResource

/**
 * 边距配置 shared 路由入口。
 * 通过 [PaddingConfigContent] 复用配置屏幕, 本路由负责标题栏 + 导航 (pop)。
 */
@Composable
fun PaddingConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val titleStr = stringResource(Res.string.padding)
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleStr,
            onBack = { navigator.pop() },
        )
        PaddingConfigContent()
    }
}

/**
 * 边距配置弹窗形态 (对照原版 PaddingConfigDialog)。
 * 由界面设置弹窗"边距"入口弹起。
 */
@Composable
fun PaddingConfigDialogHost(
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            Surface(
                shape = DesignTokens.dialogShape,
                color = AppTheme.colors.background,
                modifier = Modifier.appDialogSize().padding(16.dp),
            ) {
                Column {
                    DialogTitleBar(
                        title = stringResource(Res.string.padding),
                        onBack = onDismiss,
                    )
                    PaddingConfigContent()
                }
            }
        }
    }
}

/**
 * 边距配置正文 (路由/弹窗两形态共用)。
 *
 * [PaddingConfigController] 桥接 [ReadBookConfigProviders] (shared 版 ReadBookConfig),
 * onPostConfig 经 [ReadBookEvents.postConfig] 通知渲染刷新;
 * 退出时 [io.legado.app.help.config.ReadBookConfigShared.save] 持久化,
 * 对齐 app 端 PaddingConfigDialog.onDismiss -> ReadBookConfig.save()。
 */
@Composable
fun PaddingConfigContent() {
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

    PaddingConfigScreen(
        controller = controller,
        onPostConfig = { changes -> ReadBookEvents.postConfig(changes) },
    )
}
