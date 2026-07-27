package io.legado.app.ui.login

import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.IntentData
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.utils.openUrl

/**
 * 书源登录对话框 (app 端 actual 包装)。
 *
 * UI 与业务逻辑已下沉到 shared/sharedUiMain 的 [SourceLoginDialog], 本类仅保留:
 * - [BaseComposeDialogFragment] 宿主 (Dialog 窗口 / 主题 / 圆角 / Provider 注入)
 * - Android 平台特有回调注入: [onOpenUrl] 走 Context.openUrl (Intent 打开浏览器/WebView)
 * - IntentData 取 source/book/chapter 传给 shared
 *
 * URL 登录场景 (loginUi 为空) 由 [SourceLoginDialogExtensions.showLoginDialog] 直接打开
 * WebViewActivity, 不进入本对话框 (WebView 保留各端 actual)。
 */
class SourceLoginDialog : BaseComposeDialogFragment() {

    @Composable
    override fun Content() {
        val source = IntentData.source as? BaseSource ?: return
        SourceLoginDialog(
            source = source,
            onDismiss = { dismissAllowingStateLoss() },
            onOpenUrl = { url -> context?.openUrl(url) },
            book = IntentData.book,
            chapter = IntentData.chapter,
        )
    }
}
