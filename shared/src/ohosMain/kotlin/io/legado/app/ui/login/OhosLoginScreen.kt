package io.legado.app.ui.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.ui.OpenUrlProviders
import io.legado.app.ui.book.source.SourceLoginDialog

/**
 * 鸿蒙端书源登录界面入口 (包装 shared/sharedUiMain 的 [SourceLoginDialog])。
 *
 * # 背景
 *
 * iOS 端无独立 LoginScreen, 登录功能通过 [SourceLoginDialog] (shared/sharedUiMain 下沉)
 * 在 BookInfoScreen / BookSourceScreen / ReaderScreen 内部以 Dialog 形式触发;
 * app 端原 `SourceLoginDialog` (FragmentDialog) 也由各 Activity 内部触发。
 *
 * 鸿蒙端为对齐"登录界面"路由化需求 (Login.ets 历史), 提供 [OhosLoginScreen] 作为
 * 独立路由入口, 内部直接渲染 [SourceLoginDialog], 由 OhosNavHost 在 LOGIN 路由分支调用:
 *
 * - 调用方 (如 BookSourceScreen / BookInfoScreen / ReaderScreen) 检测到需要登录时,
 *   通过路由切换携带 [BaseSource] (登录目标书源) 跳转到本 Screen
 * - 本 Screen 渲染 [SourceLoginDialog], 用户登录成功/取消后回调 [onDismiss] 返回原路由
 *
 * # 职责
 *
 * - **登录 UI 渲染**: 全部下沉到 shared/sharedUiMain 的 [SourceLoginDialog]
 *   (DialogTitleBar + GridPackLayout 表单 + 登录 JS 执行 + 登录头管理 + 日志对话框)
 * - **平台适配**: [onOpenUrl] 桥接到鸿蒙端 [OpenUrlProviders] (ohos MainAbility startAbility)
 * - **JS 上下文**: 可选绑定 [book] / [chapter] (对应 app 端 IntentData.book / IntentData.chapter)
 * - **URL 登录分流**: 当 source.loginUi 为空且 source.loginUrl 非空时 (URL 登录场景),
 *   调用 [onOpenWebView] 切到 WEB_VIEW 路由 (对照 app 端 SourceLoginDialogExtensions.showLoginDialog
 *   直接 startActivity<WebViewActivity>); 否则渲染 [SourceLoginDialog] 表单
 *
 * # 简化项
 *
 * - **URL 打开**: 当前 [OpenUrlProviders] 鸿蒙端实现是 stub (println 占位),
 *   真实实现需经 tsfn 桥接到 ArkTS (startAbility), 后续接入
 * - **WebView 登录**: [onOpenWebView] 切到 WEB_VIEW 路由后, OhosWebViewScreen 当前为 stub,
 *   真实 WebView 需通过 @ohos.web.webview napi 桥接, 后续接入
 *
 * @param source 待登录书源 (由调用方从 BookSource 完整记录传入, 包含 header/loginUrl/loginUi)
 * @param onDismiss 关闭回调 (登录成功 / 用户取消, 由 OhosNavHost 注入切回原路由)
 * @param book JS 上下文 book 绑定 (可空, 对应 app 端 IntentData.book)
 * @param chapter JS 上下文 chapter 绑定 (可空, 对应 app 端 IntentData.chapter)
 * @param onOpenWebView URL 登录场景切到 WEB_VIEW 路由回调 (携带 loginUrl), 由 OhosNavHost 注入
 */
@Composable
fun OhosLoginScreen(
    source: BaseSource,
    onDismiss: () -> Unit,
    book: BaseBook? = null,
    chapter: BookChapter? = null,
    onOpenWebView: (String) -> Unit = {},
) {
    // URL 登录分流: loginUi 为空且 loginUrl 非空时, 切到 WEB_VIEW 路由 (对照 app 端
    // SourceLoginDialogExtensions.showLoginDialog 直接 startActivity<WebViewActivity>)
    if (source.loginUi.isNullOrEmpty() && !source.loginUrl.isNullOrBlank()) {
        val loginUrl = source.loginUrl!!
        // 鸿蒙端 WebView stub 入口, 由 OhosNavHost 注入切到 WEB_VIEW 路由
        onOpenWebView(loginUrl)
        return
    }

    // 鸿蒙端 URL 打开桥接: OpenUrlProviders (ohos actual 是 stub, 真实实现需 tsfn 桥接 startAbility)
    val onOpenUrl: (String) -> Unit = remember {
        { url ->
            OpenUrlProviders.get().openUrl(url, mimeType = null, sourceKey = null, sourceTag = null, sourceType = 0)
        }
    }

    SourceLoginDialog(
        source = source,
        onDismiss = onDismiss,
        onOpenUrl = onOpenUrl,
        book = book,
        chapter = chapter,
    )
}
