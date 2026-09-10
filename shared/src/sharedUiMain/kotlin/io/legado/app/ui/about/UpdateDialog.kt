package io.legado.app.ui.about

// 更新对话框 (四端唯一实现, 原 app 端 UpdateDialog BaseComposeDialogFragment 下沉)。
// 经 AppOverlay key="updateDialog" 渲染，payload=IntentData key 携带 UpdateCheckInfo。
// 对照原版 UpdateDialog: 标题=新版本号, 操作区=下载按钮 (原版唯一的 menu_download),
// 正文 MarkdownContentSelectable (multiplatformMarkdown, 与 MdDocDialog 同一渲染路径)。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.legado.app.help.IntentData
import io.legado.app.help.toast.Toasters
import io.legado.app.help.update.UpdateCheckInfo
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
import io.legado.app.ui.root.PlatformServiceProviders
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_download
import legado.shared.generated.resources.download_start
import legado.shared.generated.resources.open_in_browser
import org.jetbrains.compose.resources.stringResource

/**
 * 更新弹窗 Overlay (key="updateDialog", 四端唯一实现)。
 *
 * 对照原版 UpdateDialog: 标题=新版本号, 正文 Markdown 可滚动, 右上"下载"。
 * 下载走 commonMain [Download.start] (Android → DownloadManager + 下载完拉起安装器;
 * desktop/iOS/鸿蒙 → FileDownloader 落 downloads 目录, 桌面端下载完交系统默认程序打开)。
 *
 * "浏览器打开"是新增按钮 (原版没有): 本平台没有对应安装包资产时
 * ([UpdateCheckInfo.hasAsset] 为 false, 如某次 release 只传了 apk/deb/dmg 而无 msi)
 * 下载按钮就没有直链可用, 得让用户能自己去 release 页拿。走
 * [PlatformServiceProviders] 的浏览器服务 (与关于页贡献者/Telegram 外链同一条链)。
 */
@Composable
internal fun UpdateDialogOverlayContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val updateInfo = remember(overlay.payload) {
        IntentData.get<UpdateCheckInfo>(overlay.payload)
    }
    if (updateInfo == null) {
        // 对照原版 updateBody 缺失时 toast "没有数据" 后关闭
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
                    title = updateInfo.versionName,
                    onBack = { navigator.dismissOverlay(overlay.key) },
                    actions = {
                        // 对照原版 menu_download: url/name 非空才下载 (无本平台资产时不显示)
                        if (updateInfo.hasAsset) {
                            val downloadStartText = stringResource(Res.string.download_start)
                            AppTextButton(text = stringResource(Res.string.action_download)) {
                                Download.start(updateInfo.downloadUrl, updateInfo.fileName)
                                Toasters.get().toast(downloadStartText)
                            }
                        }
                        if (updateInfo.landingUrl.isNotBlank()) {
                            AppTextButton(text = stringResource(Res.string.open_in_browser)) {
                                PlatformServiceProviders.get().browser
                                    .openUrl(updateInfo.landingUrl)
                            }
                        }
                    },
                )
                MarkdownContentSelectable(
                    content = updateInfo.releaseNote,
                    // 滚动由 MarkdownContent 内部分支承担 (短文档 Column 自带 / 长文档 LazyColumn 虚拟化)
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        // 不留上边距: 顶栏底色 (bottomBackground) 与对话框底色 (fillet) 同值,
                        // 正文上边距会被视觉并入顶栏, 顶栏显得偏高; 左右/底取 arco_spacing_default
                        .padding(
                            start = DesignTokens.spacingDefault,
                            end = DesignTokens.spacingDefault,
                            bottom = DesignTokens.spacingDefault,
                        )
                )
            }
        }
    }
}
