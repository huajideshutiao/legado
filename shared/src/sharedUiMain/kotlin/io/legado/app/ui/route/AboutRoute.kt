package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.widget.dialog.MdDocDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.about
import legado.shared.generated.resources.app_share_description
import legado.shared.generated.resources.check_update
import legado.shared.generated.resources.contributors_url
import legado.shared.generated.resources.donate_qrcode
import legado.shared.generated.resources.donate_thanks
import legado.shared.generated.resources.ic_share
import legado.shared.generated.resources.is_latest_version
import legado.shared.generated.resources.share
import legado.shared.generated.resources.telegram_group_url
import legado.shared.generated.resources.version
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 关于页 shared 路由入口 (四端唯一实现; app 端原 AboutActivity/AboutFragment 已删)。
 * 通过 [ScreenModelStore] 复用 [AboutScreenModel], 渲染 [AboutScreen]。
 *
 * 检查更新与内置文档 (许可证/免责声明/隐私政策) 四端同一条链, 无平台分支:
 * 前者 [AboutScreenModel.checkUpdate] → AppUpdateManager, 后者 [MdDocDialog] 读
 * composeResources。真正平台专属的只剩崩溃日志/保存日志/堆转储, 经
 * [PlatformCapabilityProviders] 委托各端实现。
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
    // 对照原版 AppUpdate.check 的两条 toast: R.string.is_latest_version 与
    // getString(R.string.check_update) + 换行 + 异常信息
    val strLatestVersion = stringResource(Res.string.is_latest_version)
    val strCheckFailed = stringResource(Res.string.check_update)
    val strDonateThanks = stringResource(Res.string.donate_thanks)
    val strDonateQr = stringResource(Res.string.donate_qrcode)
    LaunchedEffect(Unit) {
        val versionName = PlatformCapabilityProviders.get().getAppVersionName().orEmpty()
        screenModel.updateState(
            AboutUiState(
                updateLogSummary = if (versionName.isEmpty()) "" else "$strVersion $versionName",
                contributorsUrl = strContributorsUrl,
                telegramGroupUrl = strTelegramGroupUrl,
                // 入口 gate: 已注册 AppUpdateEnvironment 的端才显示 (当前 Android + desktop;
                // iOS/鸿蒙未注册, 与原版一致 —— 原版也只有 Android 有这个入口)
                showCheckUpdate = AppUpdateManager.isAvailable(),
            )
        )
    }

    val scope = rememberCoroutineScope()
    // 待显示的内置文档 (标题 to composeResources files/ 下相对路径)
    var mdDoc by remember { mutableStateOf<Pair<String, String>?>(null) }
    val actions = object : AboutUiActions {
        // 外链统一走平台 BrowserService
        override fun onOpenUrl(url: String) {
            PlatformServiceProviders.get().browser.openUrl(url)
        }

        // 分享关于页: 内容与 app 端 share(app_share_description, app_name) 一致 (subject 由平台 share 自行处理)
        override fun onShare() {
            PlatformServiceProviders.get().sharing.shareText(strAppShareDescription)
        }

        // 检查更新: 四端同一条 shared 链路 (AppUpdateManager 检测 → updateDialog Overlay),
        // 进行中置灰入口 (代替原版 AppUpdate.check 的 WaitDialog)
        override fun onCheckUpdate() {
            scope.launch { screenModel.checkUpdate(strLatestVersion, strCheckFailed) }
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

        // 显示 MD 文件: 四端同一条通道 (内置文档在 shared composeResources files/,
        // 经 Res.readBytes 通用读取)
        override fun onShowMdFile(title: String, fileName: String) {
            mdDoc = title to fileName
        }

        // 捐赠二维码: 成功写入并回读校验后才弹全屏大图和感谢；失败明确提示。
        override fun onShowDonateQr() {
            scope.launch {
                runCatching { withContext(IoDispatcher) { donateQrFilePath() } }
                    .onSuccess { path ->
                        navigator.showOverlay(AppOverlay.Dialog(key = "photo", payload = path))
                        Toasters.get().toast(strDonateThanks)
                    }
                    .onFailure { error ->
                        Toasters.get().toast("$strDonateQr: ${error.message.orEmpty()}")
                    }
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

    // 检查更新等待框 (对照原版 AppUpdate.check 的 WaitDialog.from(activity).show() +
    // onFinally dismissSafe): 转圈期间不响应返回键与点击外部, 检测协程跑完自行消失
    // (原版 WaitDialog 只在设了 onCancelListener 的场景才可取消, 检查更新没设)
    WaitDialog(visible = state.checkingUpdate, onDismissRequest = {})

    // 隐私政策/许可证/免责声明 (对照原版 AboutFragment.showMdFile: 读内置 md 后弹 MD 对话框)
    mdDoc?.let { (title, path) ->
        MdDocDialog(title = title, assetPath = path, onDismiss = { mdDoc = null })
    }
}

/** 内置捐赠二维码在 composeResources 里的路径 (与 drawable 文件名一致)。 */
private const val DONATE_QR_RES_PATH = "drawable/image_donate_qrcode.jpg"

/** 缓存文件名前缀 (后缀拼内容摘要, 见 [donateQrFilePath])。 */
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
 * 文件名带内容摘要, 换图后自然指向新文件, 不会因相同字节数读到旧缓存。
 * 不放 `image_cache` —— 那是 [io.legado.app.help.image.ImageBytesCache] 的私有目录,
 * 它的清理逻辑会扫整个目录。
 *
 * 写盘失败直接抛出, 不静默回退。
 */
private suspend fun donateQrFilePath(): String = donateQrWriteLock.withLock {
    val bytes = Res.readBytes(DONATE_QR_RES_PATH)
    check(bytes.isNotEmpty()) { "捐赠二维码资源为空" }
    val digest = bytes.fold(0xcbf29ce484222325UL) { hash, byte ->
        (hash xor byte.toUByte().toULong()) * 0x100000001b3UL
    }.toString(16)
    val path = FileUtilsCommon.getPath(
        FileUtilsCommon.getCachePath(), "$DONATE_QR_CACHE_PREFIX$digest.jpg"
    )
    val cached = FileUtilsCommon.readBytes(path)
    if (cached == null || !cached.contentEquals(bytes)) {
        check(FileUtilsCommon.writeBytes(path, bytes)) { "捐赠二维码落盘失败: $path" }
    }
    check(FileUtilsCommon.readBytes(path)?.contentEquals(bytes) == true) {
        "捐赠二维码校验失败: $path"
    }
    path
}
