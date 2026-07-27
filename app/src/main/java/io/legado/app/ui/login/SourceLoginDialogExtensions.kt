package io.legado.app.ui.login

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.IntentData
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity

/**
 * 登录对话框入口（原 BaseSource.showLoginDialog，去 entity 安卓渗漏后上移）。
 * JS 的 source.showLoginDialog() 经事件桥 SourceUiEventBridge 转调此扩展。
 */
fun BaseSource.showLoginDialog(activity: AppCompatActivity?) {
    activity ?: return
    if (loginUi.isNullOrEmpty()) {
        if (!loginUrl.isNullOrBlank()) {
            activity.startActivity<WebViewActivity> {
                putExtra("url", loginUrl)
                putExtra("title", activity.getString(R.string.login_source, getTag()))
                putExtra("sourceName", getTag())
                putExtra("sourceOrigin", getKey())
                putExtra("sourceType", getSourceType())
                putExtra("isLogin", true)
            }
        }
    } else {
        IntentData.source = this
        activity.showDialogFragment<SourceLoginDialog>()
    }
}
