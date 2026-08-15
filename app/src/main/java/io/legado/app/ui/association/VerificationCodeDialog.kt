package io.legado.app.ui.association

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.load
import coil3.request.CachePolicy
import coil3.toBitmap
import io.legado.app.R
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.image.sourceOrigin
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.ImageProvider
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 图片验证码对话框
 * Compose 化：正文全 Compose，验证码图片经 AndroidView 承载 ImageView 供 Glide 加载。
 */
object VerificationCodeDialog {

    fun display(
        imageUrl: String,
        sourceOrigin: String? = null,
        sourceName: String? = null,
        sourceType: Int
    ) {
        // 因 PhotoDialog 经 showDialogFragment 显示，需 AppCompatActivity 提供 supportFragmentManager
        val activity = io.legado.app.help.LifecycleHelp.currentActivity as? AppCompatActivity
        if (activity == null) {
            appCtx.toastOnUi("无法在后台显示验证码对话框")
            return
        }

        lateinit var dialog: io.legado.app.base.ComposeDialog
        dialog = activity.alert {
            customView {
                var code by remember { mutableStateOf("") }
                Column(Modifier.fillMaxWidth()) {
                    // Toolbar：标题 + 副标题(源名) + ok/禁用/删除菜单
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                rememberString("verification_code"),
                                color = AppTheme.colors.primaryText,
                                fontSize = 18.sp,
                            )
                            sourceName?.let {
                                Text(it, color = AppTheme.colors.secondaryText, fontSize = 13.sp)
                            }
                        }
                        IconButton(onClick = {
                            SourceVerificationHelp.setResult(sourceOrigin!!, code)
                            dialog.dismiss()
                        }) {
                            Icon(
                                painter = rememberPainter("ic_check"),
                                contentDescription = rememberString("ok"),
                                tint = AppTheme.colors.primaryText,
                            )
                        }
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    painter = rememberPainter("ic_more_vert"),
                                    contentDescription = rememberString("more_menu"),
                                    tint = AppTheme.colors.primaryText,
                                )
                            }
                            AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    onClick = {
                                        showMenu = false
                                        sourceOrigin?.let { Coroutine.async { SourceHelp.enableSource(it, sourceType, false) } }
                                        dialog.dismiss()
                                    },
                                ) { Text(rememberString("disable_source")) }
                                DropdownMenuItem(
                                    onClick = {
                                        showMenu = false
                                        activity.alert(androidAppString("draw")) {
                                            setMessage(androidAppString("sure_del") + "\n" + sourceName)
                                            noButton()
                                            yesButton {
                                                sourceOrigin?.let { Coroutine.async { SourceHelp.deleteSource(it, sourceType) } }
                                                dialog.dismiss()
                                            }
                                        }
                                    },
                                ) { Text(rememberString("delete_source")) }
                            }
                        }
                    }
                    AndroidView(
                        factory = { ctx ->
                            AppCompatImageView(ctx).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setOnClickListener {
                                    activity.showDialogFragment(PhotoDialog(imageUrl, sourceOrigin))
                                }
                                loadImage(this, imageUrl, sourceOrigin)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                    )
                    AppUnderlineTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = rememberString("verification_code"),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            onDismiss {
                SourceVerificationHelp.checkResult(sourceOrigin!!)
            }
        }
    }

    private fun loadImage(
        imageView: ImageView,
        url: String,
        sourceOrigin: String?
    ) {
        ImageProvider.remove(url)
        imageView.setImageResource(R.drawable.image_loading_error)
        imageView.load(url) {
            sourceOrigin(sourceOrigin)
            memoryCachePolicy(CachePolicy.DISABLED)
            diskCachePolicy(CachePolicy.DISABLED)
            listener(
                onSuccess = { _, result ->
                    result.image?.toBitmap()?.let { bmp ->
                        val copy = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
                        ImageProvider.put(url, copy)
                    }
                },
                onError = { _, _ ->
                    imageView.setImageResource(R.drawable.image_loading_error)
                }
            )
        }
    }

}
