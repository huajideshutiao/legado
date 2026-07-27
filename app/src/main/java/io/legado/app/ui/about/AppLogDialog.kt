package io.legado.app.ui.about

import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeDialogFragment

/**
 * 应用日志对话框 DialogFragment 壳。
 * UI 下沉至 shared/sharedUiMain 的 [AppLogDialogContent]；本类仅保留 Fragment 壳，
 * 供 app 端 showDialogFragment<AppLogDialog>() 调用点零改动。
 *
 * 行=时间+消息，点击带异常的行弹堆栈 TextDialog；菜单=清空。
 */
class AppLogDialog : BaseComposeDialogFragment() {


    @Composable
    override fun Content() {
        AppLogDialogContent(onDismiss = { dismissAllowingStateLoss() })
    }

}
