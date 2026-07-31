package io.legado.app.ui.book.source

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.dialog.PhotoViewDialog
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.captcha_load_failed_hint
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.verification_code
import org.jetbrains.compose.resources.stringResource

/**
 * 跨平台图片验证码对话框 (对照 app 端 `io.legado.app.ui.association.VerificationCodeDialog`)。
 *
 * desktop/iOS/鸿蒙的 [io.legado.app.help.source.VerificationUiProvider] 经
 * SourceUiRequest.VerificationCode 事件驱动本对话框, 采集验证码后由调用方回填
 * [io.legado.app.help.source.SourceVerificationHelpShared.setResult]。
 *
 * 图片加载走 [ImageBitmapLoader] (直连拉取零缓存——验证码同 URL 每次返回不同图,
 * 网络书源自动带 header/cookie; iOS actual 暂 stub 返回 null 走 URL 文案降级)。
 *
 * 与 app 端差异 (平台能力约束, 不入 sharedUiMain):
 * - 无"禁用源/删除源"溢出菜单 (app 端管理入口, 各端书源管理页已有同能力)
 *
 * 点图放大: 对照 app 端 setOnClickListener → PhotoDialog(imageUrl, sourceOrigin),
 * 走 sharedUiMain [PhotoViewDialog] (重新拉取, 与 app 端 PhotoDialog 重新请求行为一致)。
 *
 * @param url 验证码图片 URL
 * @param source 书源/订阅源 (取 tag 展示; BookSource 时图片请求带源 header)
 * @param onConfirm 确认回调 (传入用户输入, 可为空串)
 * @param onDismiss 关闭回调 (未确认关闭, 调用方按 app 端 checkResult 语义回填空串)
 */
@Composable
fun VerificationCodeDialog(
    url: String,
    source: BaseSource,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    var code by remember { mutableStateOf("") }
    // 点图放大对话框状态 (null=隐藏; 对照 app 端点图 → PhotoDialog(imageUrl, sourceOrigin))
    var photoSrc by remember { mutableStateOf<String?>(null) }
    // 直连加载验证码 (produceState: 进组合即拉取, url 不变不重拉)
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = runCatching {
            ImageBitmapLoader().loadBitmap(url, null, source as? BookSource)
        }.getOrNull()
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.verification_code),
        content = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text(
                    text = source.getTag(),
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                )
                val loaded = bitmap
                if (loaded != null) {
                    Image(
                        bitmap = loaded,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 200.dp)
                            .padding(vertical = 8.dp)
                            // 点图放大 (对照 app 端 setOnClickListener → PhotoDialog)
                            .clickable { photoSrc = url },
                    )
                } else {
                    // 加载中/失败降级: 提示手动打开 URL (对照 desktop 原 Swing 版降级文案)
                    Text(
                        text = stringResource(Res.string.captcha_load_failed_hint) + "\n" + url,
                        color = colors.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                AppOutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = stringResource(Res.string.verification_code),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        okButton = AlertButton(text = stringResource(Res.string.ok)) { onConfirm(code) },
        cancelButton = AlertButton(text = stringResource(Res.string.cancel)) { onDismiss() },
    )

    // 点图放大对话框 (验证码同 URL 每次返回不同图, 大图重新拉取与 app 端 PhotoDialog 行为一致)
    photoSrc?.let { src ->
        PhotoViewDialog(
            src = src,
            onDismiss = { photoSrc = null },
            bookSource = source as? BookSource,
        )
    }
}
