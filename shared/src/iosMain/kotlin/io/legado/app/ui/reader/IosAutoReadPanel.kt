package io.legado.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.ui.book.read.config.AutoReadActions
import io.legado.app.ui.book.read.config.AutoReadController

import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 端"自动阅读"对话框入口 (包装 shared/sharedUiMain 的 [io.legado.app.ui.book.read.config.AutoReadPanel])。
 *
 * 对照桌面端 `desktop/src/main/kotlin/io/legado/desktop/ui/book/read/config/AutoReadDialog.kt`,
 * iOS 端用 [AppAlertDialog] 包裹 [io.legado.app.ui.book.read.config.AutoReadPanel], 提供"自动翻页"标题 + 关闭按钮,
 * 装配 iOS 版 [IosAutoReadController] (桥接到 [ReadBookConfigShared.autoReadSpeed]) 与
 * [IosAutoReadActions] (桥接到 [IosAutoReadDialogCallbacks] 各回调)。
 *
 * @param readBookConfig 阅读配置 (由 IosReaderScreen 注入, 来自 LocalReadConfigProviders)
 * @param callbacks 动作回调 (由 IosReaderScreen 注入, 桥接到目录侧栏 / 菜单显隐 / 翻页停止 / 样式配置)
 * @param onDismiss 关闭回调
 */
@Composable
fun IosAutoReadPanel(
    readBookConfig: ReadBookConfigShared,
    callbacks: IosAutoReadDialogCallbacks,
    onDismiss: () -> Unit,
) {
    val controller = remember(readBookConfig) { IosAutoReadController(readBookConfig) }
    val actions = remember(callbacks) { IosAutoReadActions(callbacks) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("auto_next_page"),
        content = { io.legado.app.ui.book.read.config.AutoReadPanel(controller = controller, actions = actions) },
        okButton = AlertButton(text = rememberString("close")) { onDismiss() },
    )
}

/**
 * iOS 自动阅读对话框动作回调集, 由 IosReaderScreen 实现并注入。
 */
interface IosAutoReadDialogCallbacks {
    /** 打开目录列表 */
    fun openChapterList()
    /** 显示主菜单 */
    fun showMenuBar()
    /** 停止自动翻页 */
    fun autoPageStop()
    /** 显示翻页动画配置 (iOS 端切到 IosReadStyleDialog 完整版) */
    fun showPageAnimConfig()
}

/**
 * iOS 版 [AutoReadController]: 直接读写 [ReadBookConfigShared.autoReadSpeed]。
 */
private class IosAutoReadController(
    private val readBookConfig: ReadBookConfigShared,
) : AutoReadController {
    override var autoReadSpeed: Int
        get() = readBookConfig.autoReadSpeed
        set(value) { readBookConfig.autoReadSpeed = value }
}

/**
 * iOS 版 [AutoReadActions]: 桥接到 [IosAutoReadDialogCallbacks]。
 *
 * upTtsSpeechRate: iOS 端 TTS 引擎未暴露语速调节 API, 暂 no-op (TODO)。
 */
private class IosAutoReadActions(
    private val callbacks: IosAutoReadDialogCallbacks,
) : AutoReadActions {
    override fun openChapterList() = callbacks.openChapterList()
    override fun showMenuBar() = callbacks.showMenuBar()
    override fun autoPageStop() = callbacks.autoPageStop()
    override fun showPageAnimConfig() = callbacks.showPageAnimConfig()
    override fun upTtsSpeechRate() {
        // TODO: iOS 端 TTS 引擎未暴露语速调节 API, 暂 no-op
    }
}
