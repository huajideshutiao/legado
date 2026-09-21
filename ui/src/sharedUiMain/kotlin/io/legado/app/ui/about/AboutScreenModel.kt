package io.legado.app.ui.about

import io.legado.app.help.toast.Toasters
import io.legado.app.ui.about.AboutScreenModel.Companion.HEADER_EASTER_EGG_CLICKS
import io.legado.app.ui.compose.platform.syncGetString
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
 * @param updateLogSummary  更新日志条目 summary (如 "版本 3.25.070226")
 * @param contributorsUrl   贡献者页面 URL (平台各异: Android 读 R.string, 桌面端硬编码)
 * @param telegramGroupUrl  Telegram 群链接 (平台各异)
 * @param showCheckUpdate   是否显示"检查更新"入口 (未接入更新能力的端隐藏)
 * @param checkingUpdate    正在检查更新 (入口置灰)
 */
data class AboutUiState(
    val updateLogSummary: String = "",
    val contributorsUrl: String = "",
    val telegramGroupUrl: String = "",
    val showCheckUpdate: Boolean = true,
    val checkingUpdate: Boolean = false,
)

/**
 * 关于页用户交互回调。
 *
 * 平台相关逻辑 (saveLog/createHeapDump/share 等)
 * 由宿主实现, shared 端不直接持有 Android Context / FileDoc / CrashHandler。
 *
 * - [onShare]: 顶栏分享按钮 (替代原 Activity 内 `share(...)`)
 * - [onOpenUrl]: 打开外链 (贡献者 / Telegram, URL 由 state 传入)
 * - [onCheckUpdate]: 检查更新
 * - [onShowCrashLogs]: 显示崩溃日志
 * - [onSaveLog]: 保存日志 (Android: copy logs/crash/logcat 到 backupPath; 桌面: JFileChooser 导出)
 * - [onCreateHeapDump]: 创建堆转储 (Android: CrashHandler.doHeapDump; 桌面: HotSpotDiagnosticMXBean)
 * - [onShowMdFile]: 显示 MD 文件 (title + fileName, 宿主读 assets/classpath 后弹 MD 对话框)
 * - [onShowDonateQr]: 点击"捐赠二维码"条目 (内置图落盘后交给大图查看器 + 感谢 toast)
 */
interface AboutUiActions {
    fun onShare()
    fun onOpenUrl(url: String)
    fun onCheckUpdate()
    fun onShowCrashLogs()
    fun onSaveLog()
    fun onCreateHeapDump()
    fun onShowMdFile(title: String, fileName: String)
    fun onShowDonateQr()
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

    // ===== 顶部卡片连点彩蛋 =====

    /** 连点计数; 触发后停在上限不再变化, 离开页面时随 ScreenModel 销毁一起重置。 */
    private var headerClickCount = 0

    /**
     * 顶部卡片点击: 连点 [HEADER_EASTER_EGG_CLICKS] 次触发彩蛋。
     *
     * 提示节奏照搬 AOSP `BuildNumberPreferenceController` (连点版本号开开发者选项):
     * 剩余次数满足 `remaining < 总次数 - 2` 才 toast「还差 N 次」——5 次即第 3、4 次有提示、
     * 前两次静默、第 5 次触发。不做超时重新计数 (AOSP 也只在 onStart 重置倒计时);
     * 触发后计数保持在上限, 本次停留期间继续点击一律忽略 (不再从头计数、不再 toast),
     * 离开页面时 ScreenModel 随路由销毁, 计数自然归零, 重进可再触发一次。
     * 文案走 [syncGetString]: 点击处不在组合里, 同步取才能保证连点时 toast 顺序不乱。
     * 彩蛋当前为占位 toast, 真功能落地只需改触发分支。
     */
    fun onHeaderClick() {
        if (headerClickCount >= HEADER_EASTER_EGG_CLICKS) return
        if (++headerClickCount >= HEADER_EASTER_EGG_CLICKS) {
            Toasters.get().toast(syncGetString("nothing_here"))
            return
        }
        val remaining = HEADER_EASTER_EGG_CLICKS - headerClickCount
        if (remaining < HEADER_EASTER_EGG_CLICKS - 2) {
            Toasters.get().toast(syncGetString("about_dev_hint", remaining))
        }
    }

    private companion object {

        /** 触发彩蛋所需的连点次数。 */
        const val HEADER_EASTER_EGG_CLICKS = 5
    }

    /**
     * 检查更新: 四端同一条链 ([checkUpdateAndPrompt]), 进行中置灰入口
     * (对照原版 AppUpdate.check 的 WaitDialog: 本端用条目置灰代替转圈弹窗)。
     */
    suspend fun checkUpdate(latestText: String, failedLabel: String) {
        if (_state.value.checkingUpdate) return
        _state.value = _state.value.copy(checkingUpdate = true)
        try {
            checkUpdateAndPrompt(silent = false, latestText = latestText, failedLabel = failedLabel)
        } finally {
            _state.value = _state.value.copy(checkingUpdate = false)
        }
    }
}
