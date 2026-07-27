package io.legado.app.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 鸿蒙端内置浏览器 Screen 入口 (对照 app 端 [WebViewActivity])。
 *
 * # 背景
 *
 * app 端 [WebViewActivity] 用 AndroidView 白名单承载 [VisibleWebView], 用于书源登录 /
 * 章节付费 / 源验证 (Cloudflare challenge) / Cookie 同步等场景; 标题栏由 pageTitle/subTitle
 * 驱动, 顶栏右侧有刷新/确认按钮 + 溢出菜单 (浏览器打开/复制 URL/全屏/禁用删除源)。
 *
 * 鸿蒙端 Compose 无 WebView 组件, 真实桥接需通过 `@ohos.web.webview` napi (tsfn) 接入,
 * 工作量大, 本入口仅做 stub 让 WEB_VIEW 路由可用, 后续接入鸿蒙平台 WebView。
 *
 * # Stub 内容
 *
 * - 顶部 TitleBar 显示 URL (无真实 pageTitle, 暂以 URL 兜底)
 * - 中间显示提示文本 "WebView 待接入鸿蒙端 @ohos.web.webview"
 * - 底部导航按钮 (后退/前进/刷新) 触发时 toast 提示, 无实际效果
 *
 * # 与 app 端差异
 *
 * - **WebView 主体**: app 用 AndroidView 承载; 鸿蒙端 stub Box 占位
 * - **顶栏 actions**: app 端有刷新 IconButton + 溢出菜单; 鸿蒙端将刷新移到底部导航, 溢出菜单未接入
 * - **Cookie/源验证**: app 端在 onPageFinished 内同步 CookieStore / saveVerificationResult; 鸿蒙端无
 * - **全屏视频**: app 端有 customViewContainer 覆盖层; 鸿蒙端无
 *
 * @param url 待加载 URL (由 OhosNavHost 在路由跳转时注入)
 * @param onBack 返回回调 (切回原路由, 由 OhosNavHost 注入)
 * @param onTitleChanged 页面标题变更回调 (无真实 WebView, 暂以 URL 兜底回调一次)
 */
@Composable
fun OhosWebViewScreen(
    url: String,
    onBack: () -> Unit,
    onTitleChanged: (String) -> Unit = {},
) {
    val notImplementedText = rememberString("ohos_webview_not_implemented")
    val refreshText = rememberString("refresh")
    val forwardText = rememberString("web_forward")
    val backText = rememberString("web_back")

    // 无真实 WebView, 暂以 URL 兜底回调页面标题
    LaunchedEffect(url) {
        onTitleChanged(url)
    }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = url,
            onBack = onBack,
        )
        // 中间提示
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = notImplementedText,
                color = AppTheme.colors.secondaryText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp),
            )
        }
        // 底部导航按钮 (前进/后退/刷新)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { Toasters.get().toast(backText) }) {
                Text(backText)
            }
            Button(onClick = { Toasters.get().toast(forwardText) }) {
                Text(forwardText)
            }
            Button(onClick = { Toasters.get().toast(refreshText) }) {
                Text(refreshText)
            }
        }
    }
}
