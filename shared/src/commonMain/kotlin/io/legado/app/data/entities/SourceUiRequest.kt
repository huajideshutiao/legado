package io.legado.app.data.entities

/**
 * 书源 JS 触发 UI 弹窗的纯 Kotlin 事件。
 * entity 只发事件(零安卓渗漏), 弹窗解析见 app 侧 SourceUiEventBridge。
 */
sealed class SourceUiRequest {
    abstract val source: BaseSource

    data class Login(override val source: BaseSource) : SourceUiRequest()

    data class SourceVariable(override val source: BaseSource) : SourceUiRequest()

    /**
     * 图片验证码请求 (desktop/iOS/鸿蒙 VerificationUiProvider 发出, 各端 SourceUiEventBridge
     * 弹 sharedUiMain VerificationCodeDialog; Android 端不经事件走 VerificationCodeDialog.display)。
     */
    data class VerificationCode(override val source: BaseSource, val url: String) : SourceUiRequest()
}
