package io.legado.app.ui.about

import io.legado.app.help.IntentData
import io.legado.app.help.toast.Toasters
import io.legado.app.help.update.AppUpdateManager
import io.legado.app.help.update.UpdateCheckResult
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay

/**
 * 四端共用的"检查更新 → 弹更新弹窗"编排 (对照原版 app 端 `AppUpdate.check`)。
 *
 * 原版 AppUpdate.check 的三条分支逐条对齐:
 * - 有新版本 → 弹更新弹窗 (原版 `showDialogFragment(UpdateDialog(it))`,
 *   这里走 [AppOverlay] key="updateDialog" 交 [UpdateDialogOverlayContent] 渲染)
 * - 已是最新 → toast `is_latest_version` (silent 时不提示)
 * - 检查失败 → toast "检查更新" + 换行 + 异常信息 (silent 时不提示)
 *
 * 原版的等待框由调用方负责 (关于页用 [AboutScreenModel.checkUpdate] 的 checkingUpdate 状态
 * 驱动共享 WaitDialog; 启动时的静默检查无等待框, 同原版 silent=true)。
 *
 * @param silent      true 时只在发现新版本时弹窗, 不 toast (对照原版启动自动检查)
 * @param latestText  已是最新版的提示文案 (`is_latest_version`)
 * @param failedLabel 失败提示的前缀 (`check_update`, 与原版拼法一致: 前缀 + 换行 + 异常信息;
 *                    异常无 message 时只显前缀, 不复刻原版拼出 "null" 的缺陷)
 */
suspend fun checkUpdateAndPrompt(silent: Boolean, latestText: String, failedLabel: String) {
    when (val result = AppUpdateManager.check()) {
        is UpdateCheckResult.NewVersion -> {
            // 启动期静默检查可能早于首帧组合, 挂起等待 navigator 注册就绪后再弹窗, 避免丢掉更新提示
            AppNavigatorProviders.awaitNavigator().showOverlay(
                AppOverlay.Dialog(
                    key = "updateDialog",
                    payload = IntentData.put(result.info),
                )
            )
        }

        UpdateCheckResult.UpToDate -> if (!silent) Toasters.get().toast(latestText)

        is UpdateCheckResult.Failed -> if (!silent) {
            val msg = result.error.message
            val text = if (msg.isNullOrBlank()) failedLabel else "$failedLabel\n$msg"
            Toasters.get().toast(text)
        }
    }
}
