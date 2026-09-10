package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.help.update.AppUpdateManager
import io.legado.app.ui.about.AboutHeaderCard
import io.legado.app.ui.about.AboutScreen
import io.legado.app.ui.about.AboutScreenModel
import io.legado.app.ui.about.AboutUiActions
import io.legado.app.ui.about.AboutUiState
import io.legado.app.ui.about.UpdateAvailableDialog
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.about
import legado.shared.generated.resources.already_latest_version
import legado.shared.generated.resources.app_share_description
import legado.shared.generated.resources.check_update_failed_no_msg
import legado.shared.generated.resources.contributors_url
import legado.shared.generated.resources.donate_thanks
import legado.shared.generated.resources.ic_share
import legado.shared.generated.resources.share
import legado.shared.generated.resources.telegram_group_url
import legado.shared.generated.resources.version
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 关于页 shared 路由入口 (四端唯一实现; app 端原 AboutActivity/AboutFragment 已删)。
 * 通过 [ScreenModelStore] 复用 [AboutScreenModel], 渲染 [AboutScreen]。
 *
 * 平台专属能力 (检查更新/崩溃日志/保存日志/堆转储/MD 文件) 通过 [PlatformCapabilityProviders]
 * 委托各端实现: app 端由 AndroidPlatformCapabilities 复刻原版 AboutFragment 的分支逻辑,
 * desktop/iOS/鸿蒙按需 override。
 *
 * 页面外壳对照原版 activity_about.xml 自上而下: TitleBar (menu_share_it → 分享按钮)
 * + ll_about ([AboutHeaderCard]) + 条目列表 ([AboutScreen], 迁 about.xml)。
 * 版本号/URL 在 LaunchedEffect 内由 [PlatformCapabilityProviders.getAppVersionName]
 * + 资源字符串拼出后推入 [AboutScreenModel.updateState]。
 */
@Composable
fun AboutRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) { AboutScreenModel() }
    val state by screenModel.state.collectAsState()

    // 平台资源 (版本号/URL) 在 shared 层无法直读 R.string / AppConst.appInfo,
    // 由平台能力拼装后推入 ScreenModel
    // (update_log summary 对照原版 AboutFragment.onCreatePreferences 里的 "${version} ${versionName}")
    val strVersion = stringResource(Res.string.version)
    val strContributorsUrl = stringResource(Res.string.contributors_url)
    val strTelegramGroupUrl = stringResource(Res.string.telegram_group_url)
    val strAbout = stringResource(Res.string.about)
    val strShare = stringResource(Res.string.share)
    val strAppShareDescription = stringResource(Res.string.app_share_description)
    val strLatestVersion = stringResource(Res.string.already_latest_version)
    val strCheckFailed = stringResource(Res.string.check_update_failed_no_msg)
    val strDonateThanks = stringResource(Res.string.donate_thanks)
    LaunchedEffect(Unit) {
        val versionName = PlatformCapabilityProviders.get().getAppVersionName().orEmpty()
        screenModel.updateState(
            AboutUiState(
                version = versionName,
                updateLogSummary = if (versionName.isEmpty()) "" else "$strVersion $versionName",
                contributorsUrl = strContributorsUrl,
                telegramGroupUrl = strTelegramGroupUrl,
                // 入口 gate: 平台声明能力 (app 端 = 原版 AppUpdate.check 链路) 或
                // 已注册 AppUpdateEnvironment 的端 (desktop 走 shared AppUpdateManager 链路)
                showCheckUpdate = AppUpdateManager.isAvailable() ||
                    PlatformCapabilityProviders.get().checkUpdateSupported,
            )
        )
    }

    val scope = rememberCoroutineScope()
    val actions = object : AboutUiActions {
        // 外链统一走平台 BrowserService
        override fun onOpenUrl(url: String) {
            PlatformServiceProviders.get().browser.openUrl(url)
        }

        // 分享关于页: 内容与 app 端 share(app_share_description, app_name) 一致 (subject 由平台 share 自行处理)
        override fun onShare() {
            PlatformServiceProviders.get().sharing.shareText(strAppShareDescription)
        }

        // 检查更新: 优先平台能力 (app 端 = 原版 AppUpdate.check 链路 WaitDialog/UpdateDialog/toast);
        // 未声明能力的端 (desktop) 回落 shared AppUpdateManager 检测链路, 弹 UpdateAvailableDialog
        override fun onCheckUpdate() {
            val capabilities = PlatformCapabilityProviders.get()
            if (capabilities.checkUpdateSupported) {
                capabilities.checkUpdate()
            } else {
                scope.launch { screenModel.checkUpdate(strLatestVersion, strCheckFailed) }
            }
        }

        // 显示崩溃日志: 委托平台能力 (app: CrashLogsDialog Fragment; desktop: 共享 CrashLogsDialog)
        override fun onShowCrashLogs() {
            PlatformCapabilityProviders.get().showCrashLogs()
        }

        // 保存日志到备份目录: 委托平台能力 (app: copyLogs+copyHeapDump; desktop: 文件选择器导出)
        override fun onSaveLog() {
            PlatformCapabilityProviders.get().saveLog()
        }

        // 创建堆转储: 委托平台能力 (app: CrashHandler.doHeapDump; desktop: HotSpotDiagnosticMXBean)
        override fun onCreateHeapDump() {
            PlatformCapabilityProviders.get().createHeapDump()
        }

        // 显示 MD 文件: 委托平台能力 (app: assets+TextDialog.Mode.MD; desktop: classpath+MarkdownContent)
        override fun onShowMdFile(title: String, fileName: String) {
            PlatformCapabilityProviders.get().showMdFile(title, fileName)
        }

        // 捐赠二维码: 内置 drawable 按原始字节塞进普通缓存目录, 拿绝对路径走现成的
        // 全屏大图 overlay (key="photo"), 与阅读页/评论区看图同一条通道。
        override fun onShowDonateQr() {
            Toasters.get().toast(strDonateThanks)
            scope.launch(IoDispatcher) {
                navigator.showOverlay(
                    AppOverlay.Dialog(key = "photo", payload = donateQrFilePath())
                )
            }
        }
    }

    // 页面外壳对照原版 activity_about.xml: TitleBar (分享按钮 = menu_share_it) + ll_about + 条目列表
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = strAbout,
            onBack = { navigator.pop() },
            actions = {
                IconButton(onClick = { actions.onShare() }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_share),
                        contentDescription = strShare,
                        tint = AppTheme.colors.primaryText,
                    )
                }
            },
        )
        AboutHeaderCard(onHeaderClick = screenModel::onHeaderClick)
        AboutScreen(state = state, actions = actions)
    }

    val pendingUpdate by screenModel.pendingUpdate.collectAsState()
    pendingUpdate?.let { pending ->
        UpdateAvailableDialog(
            info = pending.info,
            action = pending.action,
            onDismiss = { screenModel.dismissUpdate() },
            onConfirm = { scope.launch { screenModel.confirmUpdate() } },
        )
    }
}

/** 内置捐赠二维码在 composeResources 里的路径 (与 drawable 文件名一致)。 */
private const val DONATE_QR_RES_PATH = "drawable/image_donate_qrcode.jpg"

/** 缓存文件名前缀 (后缀拼字节数, 见 [donateQrFilePath])。 */
private const val DONATE_QR_CACHE_PREFIX = "donate_qrcode_"

/** 连点/重复打开时防两个协程同时写同一文件。 */
private val donateQrWriteLock = Mutex()

/**
 * 把内置捐赠二维码按原始字节塞进普通缓存目录, 返回可直接喂给大图查看器的绝对路径。
 *
 * 大图查看器 ([io.legado.app.ui.widget.dialog.PhotoDialogContent]) 的加载链只认
 * http(s):// / file:// / 绝对路径 / data URI 四种形态, 内置 drawable 不属于其中任何一种;
 * 落成缓存文件后走绝对路径分支, 四端 ImageBitmapLoader 都已支持。
 *
 * 目录取 [FileUtilsCommon.getCachePath] (Android externalCacheDir, 其余端 cacheDir):
 * 这张图只是"点开看一眼"的临时载体, 不需要用户找得到、也不需要跨启动存活, 系统随时可回收。
 * 文件名带字节数 (同图集 `<字节数>.<扩展名>` 约定), 换图后自然指向新文件, 不会读到旧缓存。
 * 不放 `image_cache` —— 那是 [io.legado.app.help.image.ImageBytesCache] 的私有目录,
 * 它的清理逻辑会扫整个目录。
 *
 * 写盘失败直接抛出, 不静默回退。
 */
private suspend fun donateQrFilePath(): String = donateQrWriteLock.withLock {
    val bytes = Res.readBytes(DONATE_QR_RES_PATH)
    val path = FileUtilsCommon.getPath(
        FileUtilsCommon.getCachePath(), "$DONATE_QR_CACHE_PREFIX${bytes.size}.jpg"
    )
    if (!FileUtilsCommon.exist(path)) {
        check(FileUtilsCommon.writeBytes(path, bytes)) { "捐赠二维码落盘失败: $path" }
    }
    path
}
