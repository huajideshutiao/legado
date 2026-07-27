package io.legado.app.help.source

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.SourceUiRequest
import io.legado.app.help.LifecycleHelp
import io.legado.app.ui.association.VerificationCodeDialog
import io.legado.app.ui.login.showLoginDialog
import io.legado.app.ui.widget.dialog.showSourceVariableDialog
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 书源 JS 弹窗事件桥。
 * BaseSource(entity, 零安卓渗漏) 发 [SourceUiRequest], 这里在主线程拿 currentActivity
 * 转调 app 侧 show*Dialog 扩展。语义对齐原 BaseSource.show*Dialog()(LifecycleHelp.currentActivity)。
 */
object SourceUiEventBridge {

    fun init() {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            FlowBus.with(EventBus.SOURCE_UI_REQUEST).collect { event ->
                if (event !is SourceUiRequest) return@collect
                val activity = LifecycleHelp.currentActivity as? AppCompatActivity ?: return@collect
                when (event) {
                    is SourceUiRequest.Login -> event.source.showLoginDialog(activity)
                    is SourceUiRequest.SourceVariable -> event.source.showSourceVariableDialog(activity)
                    // Android 端验证码不经事件总线 (VerificationUiProviderImpl 直调
                    // VerificationCodeDialog.display), 此分支仅保证 sealed when 穷尽
                    is SourceUiRequest.VerificationCode -> VerificationCodeDialog.display(
                        event.url, event.source.getKey(), event.source.getTag(),
                        event.source.getSourceType()
                    )
                }
            }
        }
    }
}
