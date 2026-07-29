package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.legado.app.ui.about.AboutHeaderCard
import io.legado.app.ui.about.AboutScreen
import io.legado.app.ui.about.AboutScreenModel
import io.legado.app.ui.about.AboutUiActions
import io.legado.app.ui.about.AboutUiState
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * 关于页 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [AboutScreenModel], 渲染 [AboutScreen]。
 *
 * 平台专属能力 (检查更新/崩溃日志/保存日志/堆转储/MD 文件) 通过 [PlatformCapabilityProviders]
 * 委托各端实现: app 端走 AboutActivity 自有逻辑 (不走本路由), desktop/iOS/鸿蒙按需 override。
 *
 * 页面外壳 (AppTitleBar + AboutHeaderCard) 与 [AboutActivity.Content] 一致, 版本号/URL
 * 通过 [rememberString] + [PlatformCapabilityProviders.getAppVersionName] 在 LaunchedEffect
 * 内推入 [AboutScreenModel.updateState]。
 */
@Composable
fun AboutRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) { AboutScreenModel() }
    val state by screenModel.state.collectAsState()

    // 平台资源 (版本号/URL) 在 shared 层无法直读 R.string / AppConst.appInfo, 用 rememberString +
    // 平台能力拼装后推入 ScreenModel (对照 AboutActivity 内 AboutUiState 构造)
    val strVersion = rememberString("version")
    val strContributorsUrl = rememberString("contributors_url")
    val strTelegramGroupUrl = rememberString("telegram_group_url")
    val strAbout = rememberString("about")
    val strShare = rememberString("share")
    val strAppShareDescription = rememberString("app_share_description")
    LaunchedEffect(Unit) {
        val versionName = PlatformCapabilityProviders.getOrNull()?.getAppVersionName().orEmpty()
        screenModel.updateState(
            AboutUiState(
                version = versionName,
                updateLogSummary = if (versionName.isEmpty()) "" else "$strVersion $versionName",
                contributorsUrl = strContributorsUrl,
                telegramGroupUrl = strTelegramGroupUrl,
            )
        )
    }

    val actions = object : AboutUiActions {
        // 外链统一走平台 BrowserService
        override fun onOpenUrl(url: String) {
            PlatformServiceProviders.getOrNull()?.browser?.openUrl(url)
        }

        // 分享关于页: 内容与 app 端 share(app_share_description, app_name) 一致 (subject 由平台 share 自行处理)
        override fun onShare() {
            PlatformServiceProviders.getOrNull()?.sharing?.shareText(strAppShareDescription)
        }

        // 检查更新: 委托平台能力 (app: AppUpdate.check; desktop: DesktopAppUpdate)
        override fun onCheckUpdate() {
            PlatformCapabilityProviders.getOrNull()?.checkUpdate()
        }

        // 显示崩溃日志: 委托平台能力 (app: CrashLogsDialog Fragment; desktop: 共享 CrashLogsDialog)
        override fun onShowCrashLogs() {
            PlatformCapabilityProviders.getOrNull()?.showCrashLogs()
        }

        // 保存日志到备份目录: 委托平台能力 (app: copyLogs+copyHeapDump; desktop: 文件选择器导出)
        override fun onSaveLog() {
            PlatformCapabilityProviders.getOrNull()?.saveLog()
        }

        // 创建堆转储: 委托平台能力 (app: CrashHandler.doHeapDump; desktop: HotSpotDiagnosticMXBean)
        override fun onCreateHeapDump() {
            PlatformCapabilityProviders.getOrNull()?.createHeapDump()
        }

        // 显示 MD 文件: 委托平台能力 (app: assets+TextDialog.Mode.MD; desktop: classpath+MarkdownContent)
        override fun onShowMdFile(title: String, fileName: String) {
            PlatformCapabilityProviders.getOrNull()?.showMdFile(title, fileName)
        }
    }

    // 页面外壳与 AboutActivity.Content() 一致: AppTitleBar (含分享按钮) + AboutHeaderCard + AboutScreen
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = strAbout,
            onBack = { navigator.pop() },
            actions = {
                IconButton(onClick = { actions.onShare() }) {
                    Icon(
                        painter = rememberPainter("ic_share"),
                        contentDescription = strShare,
                        tint = AppTheme.colors.primaryText,
                    )
                }
            },
        )
        AboutHeaderCard()
        AboutScreen(state = state, actions = actions)
    }
}
