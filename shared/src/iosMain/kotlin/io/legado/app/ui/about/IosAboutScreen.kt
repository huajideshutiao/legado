package io.legado.app.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.help.copyToClipboard
import io.legado.app.help.file.exportFile
import io.legado.app.help.openURL
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString
import kotlinx.coroutines.launch

/**
 * iOS 端"关于"页 Screen 入口 (包装 shared/sharedUiMain 的 [AboutScreen])。
 *
 * # 职责
 *
 * 对照 app 端 [AboutActivity] 的薄壳模式, iOS 端在 [io.legado.app.ui.IosNavHost]
 * 的 ABOUT 路由分支调用本入口。
 *
 * 业务展示逻辑全部下沉到 shared/sharedUiMain, 本文件仅做 iOS 平台适配:
 * - **顶栏**: 用 [AppTitleBar] (与 app 端 AboutActivity 一致, AboutScreen 本身无 TitleBar);
 * - **外链跳转**: 贡献者 / Telegram / 许可证 / 免责声明 均走 [openURL]
 *   (内部用 UIApplication.sharedApplication().openURL);
 * - **更新日志**: 传入版本号 summary (iOS 端暂从 NSBundle.mainBundle 读短版本号, 与 app 端
 *   读 UpdateLog 不同; 完整更新日志展示留 TODO);
 * - **检查更新 / 崩溃日志 / 保存日志 / 堆转储**: iOS 端暂未实现对应平台能力,
 *   回调内 toast 提示暂不支持 (后续 KP6+ 接入 iOS 自动更新 / 日志采集框架后补全)。
 *
 * @param onBack 返回回调 (切回调用方路由, 由 IosNavHost 注入)
 */
@Composable
fun IosAboutScreen(
    onBack: () -> Unit,
) {
    // 更新日志 summary: iOS 端暂用包短版本号展示 (与 app 端读 UpdateLog.md 不同)
    // 完整更新日志展示留 TODO, 后续 KP6+ 接入 Bundle 资源读取
    val versionSummary = remember {
        runCatching {
            platform.Foundation.NSBundle.mainBundle
                .objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
        }.getOrNull().orEmpty()
    }
    // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val checkUpdateText = rememberString("ios_check_update_not_implemented")
    val crashLogText = rememberString("ios_crash_log_not_implemented")
    val heapDumpText = rememberString("ios_heap_dump_not_supported")
    val exportSuccessText = rememberString("export_success")
    val copiedToClipboardText = rememberString("copied_to_clipboard")
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("about"),
            onBack = onBack,
        )
        AboutScreen(
            updateLogSummary = versionSummary,
            onContributors = {
                openURL("https://github.com/gedoor/legado/graphs/contributors")
            },
            onTelegramGroup = {
                openURL("https://t.me/legado_cloud")
            },
            onCheckUpdate = {
                // TODO: iOS 端检查更新 (App Store / 自建版本接口), KP6+ 接入
                Toasters.get().toast(checkUpdateText)
            },
            onCrashLog = {
                // TODO: iOS 端崩溃日志查看 (依赖日志采集框架), KP6+ 接入
                Toasters.get().toast(crashLogText)
            },
            onSaveLog = {
                // 导出 AppLog 日志到文件, 失败降级复制到剪贴板
                scope.launch {
                    val text = AppLog.logs.joinToString("\n\n") { (time, msg, throwable) ->
                        buildString {
                            append("[$time] $msg")
                            throwable?.let { append("\n${it.stackTraceToString()}") }
                        }
                    }
                    val saved = exportFile("legado_log.txt", text.encodeToByteArray())
                    if (saved) {
                        Toasters.get().toast(exportSuccessText)
                    } else {
                        copyToClipboard(text)
                        Toasters.get().toast(copiedToClipboardText)
                    }
                }
            },
            onCreateHeapDump = {
                // iOS 端无 JVM 堆转储概念, 永久 no-op (与 desktop JVM 端差异)
                Toasters.get().toast(heapDumpText)
            },
            onLicense = {
                openURL("https://github.com/gedoor/legado/blob/master/LICENSE")
            },
            onDisclaimer = {
                openURL("https://github.com/gedoor/legado/blob/master/disclaimer.md")
            },
        )
    }
}
