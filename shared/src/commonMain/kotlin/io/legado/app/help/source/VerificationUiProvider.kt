package io.legado.app.help.source

import io.legado.app.data.entities.BaseSource
import kotlin.concurrent.Volatile

/**
 * 源验证 UI 能力 provider (跨平台抽象)。
 *
 * 原 app 端 `SourceVerificationHelp` 中 UI 依赖部分 (弹图片验证码对话框 /
 * 启动内置浏览器 Activity) 经此接口抽象, 由 app 端 [VerificationUiProviderImpl]
 * (app 模块, 同包) 在 App.onCreate 经 [registerAndroidVerificationUiProvider] 注册,
 * 供 shared/commonMain 的核心流程 ([SourceVerificationHelpShared]) 回调。
 *
 * desktop/iOS/鸿蒙 端可各自注册实现, 复用 [SourceVerificationHelpShared] 核心流程。
 *
 * 模式参考 `SourceHelpAccessors` / `AppDbProviders`。
 */
interface VerificationUiProvider {

    /**
     * 弹图片验证码对话框 (对应 app 端 `VerificationCodeDialog.display`)。
     *
     * @param url 图片验证码 url
     * @param source 书源 (用于取 key/tag/sourceType 传给 Dialog)
     */
    fun showVerificationCodeDialog(url: String, source: BaseSource)

    /**
     * 启动内置浏览器 (对应 app 端 `appCtx.startActivity<WebViewActivity>`)。
     *
     * @param source 书源 (用于取 key/tag/sourceType 传给 Activity)
     * @param url 要打开的链接
     * @param title 浏览器页面标题
     * @param saveResult 是否保存网页源代码到数据库
     * @param refetchAfterSuccess 成功后是否重新抓取
     */
    fun startBrowser(
        source: BaseSource,
        url: String,
        title: String,
        saveResult: Boolean?,
        refetchAfterSuccess: Boolean?
    )
}

/**
 * [VerificationUiProvider] 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `VerificationUiProviders.get().showVerificationCodeDialog(...)`
 * 替代原 `VerificationCodeDialog.display(...)`, 行为完全一致, 仅多一层 provider 间接。
 */
object VerificationUiProviders {
    @Volatile
    private var impl: VerificationUiProvider? = null

    /** 宿主启动早期注册一次 (任何 SourceVerificationHelp 调用之前)。 */
    fun register(impl: VerificationUiProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): VerificationUiProvider = impl ?: error("VerificationUiProviders not registered")
}
