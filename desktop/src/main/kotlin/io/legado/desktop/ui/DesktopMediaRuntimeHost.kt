package io.legado.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.desktop.media.DesktopMediaRuntime

/**
 * 媒体播放组件按需下载的弹框宿主, 由 Main.kt 挂在主窗口 Compose 根容器 (与 DesktopToastHost 同级)。
 *
 * 为什么要一个全局宿主: 需要这套 native 的入口不止一个 —— 视频页 (`MediampVideoPlayPlatformProvider`)
 * 与桌面音频引擎 (`DesktopAudioPlayer`) 共用同一套 mpv/FFmpeg, 两处各自弹框会出现两层对话框、
 * 也各自要处理"下载完成后重建播放器"。这里只订阅 [DesktopMediaRuntime.uiState] 单点呈现。
 *
 * 三态与用户裁决一致: 缺失先问 (约 21MB 要占用户流量) → 下载中给进度 → 失败报错并给重试,
 * 不静默降级、不自动重下。
 */
@Composable
fun DesktopMediaRuntimeHost() {
    val state by DesktopMediaRuntime.uiState.collectAsState()
    when (state.phase) {
        null -> Unit

        DesktopMediaRuntime.Phase.NeedConfirm -> AppAlertDialog(
            onDismissRequest = { DesktopMediaRuntime.dismiss() },
            title = jvmGetString("media_runtime_title"),
            message = jvmGetString("media_runtime_need_download"),
            okButton = AlertButton(
                text = jvmGetString("action_download"),
                dismissOnClick = false,
                onClick = { DesktopMediaRuntime.startInstall() },
            ),
            cancelButton = AlertButton(
                text = jvmGetString("cancel"),
                onClick = { DesktopMediaRuntime.dismiss() },
            ),
            widthFraction = 0.5f,
        )

        DesktopMediaRuntime.Phase.Downloading -> AppAlertDialog(
            // 下载中禁外点关闭: 半截安装靠 .part + sha1 标记兜住, 但用户不该误以为关框就是取消安装
            onDismissRequest = {},
            title = jvmGetString("media_runtime_title"),
            message = jvmGetString("media_runtime_downloading", state.progressText),
            content = {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    LinearProgressIndicator(
                        progress = if (state.totalBytes > 0) state.percent / 100f else 0f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            widthFraction = 0.5f,
        )

        DesktopMediaRuntime.Phase.Failed -> AppAlertDialog(
            onDismissRequest = { DesktopMediaRuntime.dismiss() },
            title = jvmGetString("media_runtime_title"),
            message = jvmGetString("media_runtime_download_failed", state.message.orEmpty()),
            okButton = AlertButton(
                text = jvmGetString("retry"),
                dismissOnClick = false,
                onClick = { DesktopMediaRuntime.startInstall() },
            ),
            cancelButton = AlertButton(
                text = jvmGetString("cancel"),
                onClick = { DesktopMediaRuntime.dismiss() },
            ),
            widthFraction = 0.5f,
        )
    }
}

/** 进度文案里的百分比与体积一栏 (MB, 一位小数); 总量未知时只报已下量 */
private val DesktopMediaRuntime.UiState.progressText: String
    get() = if (totalBytes > 0) {
        "%1\$d%%  %2\$s".format(percent, sizePair)
    } else {
        sizePair
    }

private val DesktopMediaRuntime.UiState.sizePair: String
    get() = "%.1f / %.1f MB".format(
        downloadedBytes / 1048576.0,
        (if (totalBytes > 0) totalBytes else MEDIA_RUNTIME_FALLBACK_BYTES) / 1048576.0,
    )

/** 总量未知时文案里显示的下界: 实测 windows-x64 工件 22,086,741B */
private const val MEDIA_RUNTIME_FALLBACK_BYTES = 22_086_741L
