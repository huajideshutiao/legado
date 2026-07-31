package io.legado.app.ui.book.manga.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDetailSeekBar
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.contrast
import legado.shared.generated.resources.enable_manga_gray
import legado.shared.generated.resources.manga_color_filter
import org.jetbrains.compose.resources.stringResource

/**
 * 漫画颜色滤镜对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.manga.config.MangaColorFilterDialog`, 但去掉对
 * BaseComposeDialogFragment / WindowManager / AppConfig 的依赖, 改为纯 @Composable + 回调:
 * - 4 个 SeekBar (对比度/R/G/B) 拖动中实时回调 [onColorFilterChange]
 * - 灰度开关切换时回调 [onGrayChange]
 * - 关闭即销毁状态, 持久化由调用方在回调内完成 (对照 app 端 onDismiss 写 AppConfig)
 *
 * @param config 当前滤镜配置 (作为初始值, 内部状态由 remember 持有)
 * @param grayEnabled 当前灰度开关
 * @param onColorFilterChange 滤镜参数变化 (拖动中实时回调)
 * @param onGrayChange 灰度开关变化
 * @param onDismiss 用户关闭对话框 (返回按钮 / 点击外部)
 */
@Composable
fun MangaColorFilterDialog(
    config: MangaColorFilterConfig,
    grayEnabled: Boolean,
    onColorFilterChange: (MangaColorFilterConfig) -> Unit,
    onGrayChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val titleText = stringResource(Res.string.manga_color_filter)
    val contrastLabel = stringResource(Res.string.contrast)
    val grayLabel = stringResource(Res.string.enable_manga_gray)

    var ct by remember { mutableStateOf(config.ct) }
    var r by remember { mutableStateOf(config.r) }
    var g by remember { mutableStateOf(config.g) }
    var b by remember { mutableStateOf(config.b) }
    var gray by remember { mutableStateOf(grayEnabled) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.background,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = titleText,
                    onBack = onDismiss,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // 对比度: app 端 value=ct+50, max=100, 显示 it-50
                    AppDetailSeekBar(
                        title = contrastLabel,
                        value = ct + 50,
                        max = 100,
                        valueFormat = { "${it - 50}" },
                        onChanged = {
                            ct = it - 50
                            onColorFilterChange(MangaColorFilterConfig(r, g, b, ct))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppDetailSeekBar(
                        title = "R",
                        value = r,
                        max = 255,
                        onChanged = {
                            r = it
                            onColorFilterChange(MangaColorFilterConfig(r, g, b, ct))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppDetailSeekBar(
                        title = "G",
                        value = g,
                        max = 255,
                        onChanged = {
                            g = it
                            onColorFilterChange(MangaColorFilterConfig(r, g, b, ct))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppDetailSeekBar(
                        title = "B",
                        value = b,
                        max = 255,
                        onChanged = {
                            b = it
                            onColorFilterChange(MangaColorFilterConfig(r, g, b, ct))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                gray = !gray
                                onGrayChange(gray)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppCheckbox(
                            checked = gray,
                            onCheckedChange = {
                                gray = it
                                onGrayChange(it)
                            },
                        )
                        Text(grayLabel, color = colors.primaryText)
                    }
                }
            }
        }
    }
}
