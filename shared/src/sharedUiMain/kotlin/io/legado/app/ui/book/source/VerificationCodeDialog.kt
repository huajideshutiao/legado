package io.legado.app.ui.book.source

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.source.SourceHelp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.captcha_load_failed_hint
import legado.shared.generated.resources.delete_source
import legado.shared.generated.resources.disable_source
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.more_menu
import legado.shared.generated.resources.no
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.sure_del
import legado.shared.generated.resources.verification_code
import legado.shared.generated.resources.yes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 跨平台图片验证码对话框 (四端唯一实现; 原 app 端 association.VerificationCodeDialog 平行实现已删)。
 *
 * desktop/iOS/鸿蒙的 [io.legado.app.help.source.VerificationUiProvider] 经
 * SourceUiRequest.VerificationCode 事件驱动本对话框, 采集验证码后由调用方回填
 * [io.legado.app.help.source.SourceVerificationHelpShared.setResult]。
 *
 * 图片加载走 [ImageBitmapLoader] (直连拉取——验证码同 URL 每次返回不同图, 显式
 * `useBitmapCache=false` 同时绕过 [DecodedBitmapCache] 位图 LRU 与网络原始字节缓存;
 * 网络书源自动带 header/cookie)。
 *
 * 标题栏溢出菜单提供"禁用源/删除源" (对照 app 端同款对话框): 禁用走
 * [SourceHelp.enableSource](false), 删除先确认再走 [SourceHelp.deleteSource],
 * 操作完成后关对话框 (调用方 onDismiss 按 checkResult 语义回填空串)。
 *
 * 点图放大: 对照原版 setOnClickListener → PhotoDialog(imageUrl, sourceOrigin),
 * 走全屏大图 overlay (key="photo" → PhotoViewOverlayDialog, 与阅读页/评论区同一通道;
 * 原版 PhotoDialog 就是全屏, 验证码图与大图共用同一 URL 会重拉一次, 与原版行为一致)。
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
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    // 删除源确认对话框状态 (对照 app 端溢出菜单 → alert(sure_del) { yesButton { deleteSource } })
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 直连加载验证码 (produceState: 进组合即拉取, url 不变不重拉)。小图与全屏预览
    // 都按封面链路解密；useBitmapCache=false 同时绕过位图和原始字节缓存，固定 URL 也会重下。
    val imageState by produceState<CaptchaImageState>(CaptchaImageState.Loading, url) {
        value = runCatching {
            ImageBitmapLoader().loadBitmap(
                url = url,
                book = null,
                bookSource = source as? BookSource,
                isCover = true,
                useBitmapCache = false,
            )
        }.fold(
            onSuccess = { it?.let(CaptchaImageState::Success) ?: CaptchaImageState.Error },
            onFailure = { CaptchaImageState.Error },
        )
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        content = {
            Column(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.spacingDefault)) {
                // 工具栏: 标题 + 源名 + 溢出菜单 (禁用源/删除源, 对照 app 端 VerificationCodeDialog;
                // 确认仍走底部 okButton)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.verification_code),
                            color = colors.primaryText,
                            fontSize = 18.sp,
                        )
                        Text(
                            text = source.getTag(),
                            color = colors.secondaryText,
                            fontSize = 13.sp,
                        )
                    }
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_more_vert),
                                contentDescription = stringResource(Res.string.more_menu),
                                tint = colors.primaryText,
                            )
                        }
                        AppDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                onClick = {
                                    showMenu = false
                                    // 对照 app 端: SourceHelp.enableSource(key, type, false) 后关对话框
                                    scope.launch {
                                        SourceHelp.enableSource(
                                            source.getKey(),
                                            source.getSourceType(),
                                            false,
                                        )
                                    }
                                    onDismiss()
                                },
                            ) { Text(stringResource(Res.string.disable_source)) }
                            DropdownMenuItem(
                                onClick = {
                                    showMenu = false
                                    showDeleteConfirm = true
                                },
                            ) { Text(stringResource(Res.string.delete_source)) }
                        }
                    }
                }
                when (val state = imageState) {
                    CaptchaImageState.Loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 200.dp)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(Res.string.loading),
                            color = colors.secondaryText,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 56.dp),
                        )
                    }

                    is CaptchaImageState.Success -> Image(
                        bitmap = state.bitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 200.dp)
                            .padding(vertical = 8.dp)
                            // 点图放大 (对照原版 setOnClickListener → PhotoDialog): 全屏大图 overlay
                            .clickable {
                                AppNavigatorProviders.get().showOverlay(
                                    AppOverlay.Dialog(
                                        key = "photo",
                                        payload = url,
                                        sourceOrigin = (source as? BookSource)?.bookSourceUrl,
                                    )
                                )
                            },
                    )

                    CaptchaImageState.Error -> Text(
                        text = stringResource(Res.string.captcha_load_failed_hint) + "\n" + url,
                        color = colors.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                AppUnderlineTextField(
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

    // 删除源确认 (对照 app 端溢出菜单 → alert(draw) { sure_del + 源名; noButton; yesButton { deleteSource } })
    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del) + "\n" + source.getTag(),
            okButton = AlertButton(stringResource(Res.string.yes)) {
                showDeleteConfirm = false
                scope.launch {
                    SourceHelp.deleteSource(source.getKey(), source.getSourceType())
                }
                onDismiss()
            },
            cancelButton = AlertButton(stringResource(Res.string.no)) { showDeleteConfirm = false },
        )
    }
}

private sealed interface CaptchaImageState {
    data object Loading : CaptchaImageState
    data class Success(val bitmap: ImageBitmap) : CaptchaImageState
    data object Error : CaptchaImageState
}
