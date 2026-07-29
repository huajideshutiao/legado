package io.legado.app.ui.about

import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ===== state / actions =====

/**
 * 关于页 UI 状态 (immutable)。
 *
 * 平台资源 (版本号/URL) 由宿主 Activity/桌面端解析后推入 [AboutScreenModel.updateState]。
 *
 * @param version           应用版本号 (如 "3.25.070226")
 * @param updateLogSummary  更新日志条目 summary (如 "版本 3.25.070226")
 * @param contributorsUrl   贡献者页面 URL (平台各异: Android 读 R.string, 桌面端硬编码)
 * @param telegramGroupUrl  Telegram 群链接 (平台各异)
 */
data class AboutUiState(
    val version: String = "",
    val updateLogSummary: String = "",
    val contributorsUrl: String = "",
    val telegramGroupUrl: String = "",
)

/**
 * 关于页用户交互回调。
 *
 * 平台相关逻辑 (saveLog/createHeapDump/showMdFile/checkUpdate/share 等)
 * 由宿主实现, shared 端不直接持有 Android Context / FileDoc / CrashHandler。
 *
 * - [onShare]: 顶栏分享按钮 (替代原 Activity 内 `share(...)`)
 * - [onOpenUrl]: 打开外链 (贡献者 / Telegram, URL 由 state 传入)
 * - [onCheckUpdate]: 检查更新
 * - [onShowCrashLogs]: 显示崩溃日志
 * - [onSaveLog]: 保存日志 (Android: copy logs/crash/logcat 到 backupPath; 桌面: JFileChooser 导出)
 * - [onCreateHeapDump]: 创建堆转储 (Android: CrashHandler.doHeapDump; 桌面: HotSpotDiagnosticMXBean)
 * - [onShowMdFile]: 显示 MD 文件 (title + fileName, 宿主读 assets/classpath 后弹 MD 对话框)
 */
interface AboutUiActions {
    fun onShare()
    fun onOpenUrl(url: String)
    fun onCheckUpdate()
    fun onShowCrashLogs()
    fun onSaveLog()
    fun onCreateHeapDump()
    fun onShowMdFile(title: String, fileName: String)
}

// ===== ScreenModel =====

/**
 * 关于页 shared ScreenModel: 托管 [AboutUiState]。
 *
 * 平台资源 (版本号/URL) 无法在 shared 层直接读取 (AppConst.appInfo 为 Android 扩展,
 * R.string.contributors_url 为 Android 资源), 由宿主在 Composition 时调用 [updateState] 推入。
 */
class AboutScreenModel : ScreenModel {

    private val _state = MutableStateFlow(AboutUiState())
    val state: StateFlow<AboutUiState> = _state.asStateFlow()

    fun updateState(state: AboutUiState) {
        _state.value = state
    }
}
