package io.legado.app.ui.association

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import io.legado.app.ui.compose.component.AppDropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.base.ComposeDialog
import io.legado.app.constant.AppLog
import io.legado.app.constant.SourceType
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceHelp
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.toastOnUi
import org.jetbrains.compose.resources.stringResource
import splitties.init.appCtx

object OpenUrlConfirmDialog {

    fun display(
        uri: String,
        mimeType: String?,
        sourceOrigin: String? = null,
        sourceName: String? = null,
        sourceType: Int = SourceType.book
    ) {
        // 仅用作 Context 承载 Dialog, 无需 AppCompatActivity 特有能力
        val activity: Activity? = LifecycleHelp.currentActivity
        if (activity == null) {
            appCtx.toastOnUi("无法在后台显示跳转确认对话框")
            return
        }
        val dialog = ComposeDialog(activity)
        dialog.setComposeContent {
            Content(
                sourceName = sourceName,
                onDisableSource = {
                    sourceOrigin?.let { Coroutine.async { SourceHelp.enableSource(it, sourceType, false) } }
                    dialog.dismiss()
                },
                onDeleteSource = {
                    activity.alert(R.string.draw) {
                        setMessage(activity.getString(R.string.sure_del) + "\n" + sourceName)
                        noButton()
                        yesButton {
                            sourceOrigin?.let { Coroutine.async { SourceHelp.deleteSource(it, sourceType) } }
                            dialog.dismiss()
                        }
                    }
                },
                onCancel = { dialog.dismiss() },
                onOk = {
                    openUrl(uri, mimeType)
                    dialog.dismiss()
                },
            )
        }
        dialog.show()
    }

    @Composable
    private fun Content(
        sourceName: String?,
        onDisableSource: () -> Unit,
        onDeleteSource: () -> Unit,
        onCancel: () -> Unit,
        onOk: () -> Unit,
    ) {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = "跳转确认",
                subtitle = sourceName,
                actions = { OverflowMenu(onDisableSource, onDeleteSource) },
            )
            Text(
                text = "$sourceName 正在请求跳转链接/应用，是否跳转？",
                color = colors.primaryText,
                modifier = Modifier.padding(24.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                AppTextButton(
                    text = stringResource(R.string.cancel),
                    color = colors.secondaryText,
                    onClick = onCancel,
                )
                AppTextButton(text = stringResource(R.string.ok), onClick = onOk)
            }
        }
    }

    @Composable
    private fun RowScope.OverflowMenu(
        onDisableSource: () -> Unit,
        onDeleteSource: () -> Unit,
    ) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    painter = rememberPainter("ic_more_vert"),
                    contentDescription = null,
                    tint = AppTheme.colors.primaryText,
                )
            }
            AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onDisableSource()
                    },
                ) { Text(stringResource(R.string.disable_source)) }
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onDeleteSource()
                    },
                ) { Text(stringResource(R.string.delete_source)) }
            }
        }
    }

    private fun openUrl(uriString: String, mimeType: String?) {
        try {
            val uri = uriString.toUri()
            val targetIntent = Intent(Intent.ACTION_VIEW).apply {
                if (!mimeType.isNullOrBlank()) {
                    setDataAndType(uri, mimeType)
                } else {
                    data = uri
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (targetIntent.resolveActivity(appCtx.packageManager) != null) {
                appCtx.startActivity(targetIntent)
            } else {
                appCtx.toastOnUi(R.string.can_not_open)
            }
        } catch (e: Exception) {
            AppLog.put("打开链接失败", e, true)
        }
    }
}
