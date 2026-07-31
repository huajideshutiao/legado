package io.legado.app.ui.about

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.update.AppUpdateShared
import io.legado.app.lib.theme.space
import io.legado.app.model.Download
import io.legado.app.ui.compose.MarkdownContentSelectable
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.utils.toastOnUi

/**
 * 更新弹窗（迁 dialog_text_view → Compose）。
 * Markdown 正文走 multiplatformMarkdown，与 TextDialog Mode.MD 同一渲染路径。
 */
class UpdateDialog() : BaseComposeDialogFragment() {


    constructor(updateInfo: AppUpdateShared.UpdateInfo) : this() {
        arguments = Bundle().apply {
            putString("newVersion", updateInfo.tagName)
            putString("updateBody", updateInfo.updateLog)
            putString("url", updateInfo.downloadUrl)
            putString("name", updateInfo.fileName)
        }
    }

    @Composable
    override fun Content() {
        val updateBody = arguments?.getString("updateBody")
        if (updateBody == null) {
            LaunchedEffect(Unit) {
                toastOnUi("没有数据")
                dismissAllowingStateLoss()
            }
            return
        }
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = arguments?.getString("newVersion") ?: "",
                onBack = { dismissAllowingStateLoss() },
                actions = {
                    AppTextButton(text = stringResource(R.string.action_download)) {
                        val url = arguments?.getString("url")
                        val name = arguments?.getString("name")
                        if (url != null && name != null) {
                            Download.start(url, name)
                            toastOnUi(R.string.download_start)
                        }
                    }
                },
            )
            val pad = with(LocalDensity.current) { requireContext().space.md.toDp() }
            MarkdownContentSelectable(
                content = updateBody,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(pad)
            )
        }
    }

}
