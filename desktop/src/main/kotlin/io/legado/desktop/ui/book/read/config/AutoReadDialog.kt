package io.legado.desktop.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.ui.book.read.config.AutoReadActions
import io.legado.app.ui.book.read.config.AutoReadController

import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString

/**
 * 桌面端"自动阅读"对话框入口（包装 shared/sharedUiMain 的 [io.legado.app.ui.book.read.config.AutoReadPanel]）。
 *
 * # 职责
 *
 * - 用 [AppAlertDialog] 包裹 [io.legado.app.ui.book.read.config.AutoReadPanel]，提供标题"自动翻页" + 关闭按钮
 * - 装配桌面版 [AutoReadController]（桥接到 [ReadBookConfigShared.autoReadSpeed]）
 * - 装配桌面版 [AutoReadActions]（桥接到 [AutoReadDialogCallbacks] 各回调）
 *
 * # 简化项
 *
 * - controller: app 端 thin wrapper 桥接到 `ReadBookConfig.autoReadSpeed`，
 *   桌面端直接读写 [ReadBookConfigShared.autoReadSpeed]，已通过 prefs 持久化
 * - upTtsSpeechRate: app 端调 `ReadAloud.upTtsSpeechRate` + pause/resume，
 *   桌面端 TTS 引擎未暴露语速调节 API，暂 no-op（加 TODO）
 *
 * @param readBookConfig 阅读配置（由 ReaderScreen 注入）
 * @param callbacks 动作回调（由 ReaderScreen 注入，桥接到目录侧栏 / 菜单显隐 / 翻页停止 / 配置面板）
 * @param onDismiss 关闭回调
 */
@Composable
fun AutoReadDialog(
    readBookConfig: ReadBookConfigShared,
    callbacks: AutoReadDialogCallbacks,
    onDismiss: () -> Unit,
) {
    val controller = remember(readBookConfig) { DesktopAutoReadController(readBookConfig) }
    val actions = remember(callbacks) { DesktopAutoReadActions(callbacks) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("auto_next_page"),
        content = { io.legado.app.ui.book.read.config.AutoReadPanel(controller = controller, actions = actions) },
        okButton = AlertButton(text = rememberString("close")) { onDismiss() },
    )
}

/**
 * 自动阅读对话框动作回调集，由 ReaderScreen 实现并注入。
 */
interface AutoReadDialogCallbacks {
    /** 打开目录列表 */
    fun openChapterList()
    /** 显示主菜单 */
    fun showMenuBar()
    /** 停止自动翻页 */
    fun autoPageStop()
    /** 显示翻页动画配置（桌面端切到 ReadStyleScreen 完整版） */
    fun showPageAnimConfig()
}

/**
 * 桌面版 [AutoReadController]：直接读写 [ReadBookConfigShared.autoReadSpeed]。
 */
private class DesktopAutoReadController(
    private val readBookConfig: ReadBookConfigShared,
) : AutoReadController {
    override var autoReadSpeed: Int
        get() = readBookConfig.autoReadSpeed
        set(value) { readBookConfig.autoReadSpeed = value }
}

/**
 * 桌面版 [AutoReadActions]：桥接到 [AutoReadDialogCallbacks]。
 *
 * upTtsSpeechRate: 桌面端 TTS 引擎未暴露语速调节 API，暂 no-op（TODO）。
 */
private class DesktopAutoReadActions(
    private val callbacks: AutoReadDialogCallbacks,
) : AutoReadActions {
    override fun openChapterList() = callbacks.openChapterList()
    override fun showMenuBar() = callbacks.showMenuBar()
    override fun autoPageStop() = callbacks.autoPageStop()
    override fun showPageAnimConfig() = callbacks.showPageAnimConfig()
    override fun upTtsSpeechRate() {
        // TODO: 桌面端 TTS 引擎未暴露语速调节 API，暂 no-op
    }
}
