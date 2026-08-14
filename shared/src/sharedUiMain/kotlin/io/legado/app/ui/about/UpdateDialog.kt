package io.legado.app.ui.about

// 下沉说明：核心逻辑在 commonMain AppUpdateShared（检查更新/UpdateInfo），本文件是更新对话框的
// KMP 共享 UI（原 app 端 UpdateDialog BaseComposeDialogFragment 下沉）。
// 经 AppOverlay key="updateDialog" 渲染，payload=IntentData key 携带 AppUpdateShared.UpdateInfo。
// 对照 app 端 UpdateDialog.Content: 标题=新版本号, 操作区=下载按钮 + 打开发布页按钮(非空时),
// 正文 MarkdownContentSelectable (multiplatformMarkdown, 与 TextDialog Mode.MD 同一渲染路径)。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.help.IntentData
import io.legado.app.help.toast.Toasters
import io.legado.app.help.update.AppUpdateShared
import io.legado.app.model.Download
import io.legado.app.ui.compose.MarkdownContentSelectable
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.PlatformCapabilityProviders
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_download
import legado.shared.generated.resources.download_start
import legado.shared.generated.resources.open_release_page
import org.jetbrains.compose.resources.stringResource

/**
 * 更新弹窗 Overlay (key="updateDialog")。
 *
 * 对照 app 端 UpdateDialog: 标题=新版本号, 操作区=下载 + 打开发布页(非空时),
 * 正文 Markdown 可滚动。下载走 commonMain [Download.start] (与 WebViewUtil 同链),
 * 打开发布页走 [PlatformCapabilityProviders.openExternalUrl]。
 */
@Composable
internal fun UpdateDialogOverlayContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val updateInfo = remember(overlay.payload) { IntentData.get<AppUpdateShared.UpdateInfo>(overlay.payload) }
    if (updateInfo == null) {
        // 对照 app 端 updateBody 缺失时 toast "没有数据" 后关闭
        LaunchedEffect(Unit) {
            Toasters.get().toast("没有数据")
            navigator.dismissOverlay(overlay.key)
        }
        return
    }
    AppDialog(
        onDismissRequest = { navigator.dismissOverlay(overlay.key) },
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = AppTheme.colors.fillet,
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = updateInfo.tagName,
                    onBack = { navigator.dismissOverlay(overlay.key) },
                    actions = {
                        // 对照 app 端: url/name 均非空才可下载 (UpdateInfo 为非空 String, 条件恒真)
                        val downloadStartText = stringResource(Res.string.download_start)
                        AppTextButton(text = stringResource(Res.string.action_download)) {
                            Download.start(updateInfo.downloadUrl, updateInfo.fileName)
                            Toasters.get().toast(downloadStartText)
                        }
                        if (updateInfo.releasePageUrl.isNotBlank()) {
                            AppTextButton(text = stringResource(Res.string.open_release_page)) {
                                PlatformCapabilityProviders.get().openExternalUrl(updateInfo.releasePageUrl)
                            }
                        }
                    },
                )
                MarkdownContentSelectable(
                    content = updateInfo.updateLog,
                    // 滚动由 MarkdownContent 内部分支承担 (短文档 Column 自带 / 长文档 LazyColumn 虚拟化)
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        // 对照 app 端 space.md 的 16dp 内边距
                        .padding(16.dp)
                )
            }
        }
    }
}
