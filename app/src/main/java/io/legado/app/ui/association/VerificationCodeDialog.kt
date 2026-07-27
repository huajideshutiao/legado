package io.legado.app.ui.association

import android.annotation.SuppressLint
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
import io.legado.app.ui.compose.component.AppDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.ImageProvider
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.dialogs.alert
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
                                stringResource(R.string.verification_code),
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
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = stringResource(R.string.ok),
                                tint = AppTheme.colors.primaryText,
                            )
                        }
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_more_vert),
                                    contentDescription = stringResource(R.string.more_menu),
                                    tint = AppTheme.colors.primaryText,
                                )
                            }
                            AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.disable_source)) },
                                    onClick = {
                                        showMenu = false
                                        sourceOrigin?.let { Coroutine.async { SourceHelp.enableSource(it, sourceType, false) } }
                                        dialog.dismiss()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_source)) },
                                    onClick = {
                                        showMenu = false
                                        activity.alert(R.string.draw) {
                                            setMessage(activity.getString(R.string.sure_del) + "\n" + sourceName)
                                            noButton()
                                            yesButton {
                                                sourceOrigin?.let { Coroutine.async { SourceHelp.deleteSource(it, sourceType) } }
                                                dialog.dismiss()
                                            }
                                        }
                                    },
                                )
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
                                loadImage(activity, this, imageUrl, sourceOrigin)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                    )
                    AppOutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = stringResource(R.string.verification_code),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            onDismiss {
                SourceVerificationHelp.checkResult(sourceOrigin!!)
            }
        }
    }

    @SuppressLint("CheckResult")
    private fun loadImage(
        activity: AppCompatActivity,
        imageView: ImageView,
        url: String,
        sourceUrl: String?
    ) {
        ImageProvider.remove(url)
        ImageLoader.loadBitmap(activity, url).apply {
            sourceUrl?.let {
                apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, it))
            }
        }.error(R.drawable.image_loading_error)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap?>,
                    isFirstResource: Boolean
                ): Boolean {
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap,
                    model: Any,
                    target: Target<Bitmap?>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    val bitmap = resource.copy(resource.config!!, true)
                    ImageProvider.put(url, bitmap)
                    return false
                }
            })
            .into(imageView)
    }

}
