package io.legado.desktop.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppLog
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.about.AboutScreen as SharedAboutScreen
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.compose.MarkdownContentSelectable
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.browseUrl
import io.legado.desktop.constant.DesktopAppInfo
import io.legado.desktop.help.DesktopAppUpdate
import io.legado.desktop.help.UpdateInfo
import java.io.File
import java.lang.management.ManagementFactory
import java.text.SimpleDateFormat
import java.util.Date
import io.legado.desktop.ui.component.FileDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端"关于"页 Screen 入口 (包装 shared/sharedUiMain 的 [SharedAboutScreen])。
 *
 * # 职责
 *
 * - 在 [SharedAboutScreen] 之上加 [AppTitleBar] (标题"关于" + 返回按钮)
 * - 注入 4 个 DesktopXxxProvider 供 commonMain 的 [AppTheme] / [SharedAboutScreen]
 *   内部 [io.legado.app.ui.compose.preference.PreferenceScreen] 通过 LocalXxx 取依赖
 * - 装配 10 个回调 + updateLogSummary, 与 app 端 AboutActivity 逐条对齐
 *
 * # shared 端签名说明
 *
 * shared/sharedUiMain 的 [SharedAboutScreen] 为 stateless 列表页, 直接接收 10 个参数
 * (updateLogSummary + 10 个 onXxx 回调), 无 UiState 数据类 / UiActions 接口 / slot 参数,
 * 故桌面端无需 DesktopAboutActions 类与 AboutContent state 拆分, 仅在 [AboutContent] 内
 * 构造 updateLogSummary 与各回调后位置传参调用。
 *
 * # 简化项 / 桌面端适配
 *
 * - 检查更新: 调 [io.legado.desktop.help.DesktopAppUpdate.check] (方案 B: GitHub Release API
 *   比对版本号), 新版本时回调弹 AlertDialog 提示用户手动下载 (java.awt.Desktop.browse 打开下载 URL);
 *   替代 app 端 [io.legado.app.help.update.AppUpdate] (走自建更新源 + DownloadManager 自动安装)
 * - 加入 Telegram 群: app 端 openUrl(Intent), 桌面端用 [java.awt.Desktop.browse] 替代
 * - 贡献者: 同上, 用 [java.awt.Desktop.browse] 打开 contributors_url
 * - 开源许可/免责声明: app 端读 assets 下 .md 文件, 用 TextDialog(MD 模式) +
 *   Markwon 渲染显示; 桌面端无 assets, 改从 shared/commonMain/resources classpath 用
 *   getResourceAsStream 读同一份 .md 文件, 用 AlertDialog + shared MarkdownContentSelectable
 *   (基于 mikepenz/multiplatform-markdown-renderer) 渲染 MD, verticalScroll 包裹保证长文可滚动,
 *   SelectionContainer 支持选择复制 (替代之前 TextDialog TEXT 模式纯文本显示, MD 标记已正确渲染)
 * - 崩溃日志: onCrashLog 接入 [AppLogDialog] (shared/sharedUiMain 下沉, 替代 app 端 CrashLogsDialog)
 * - 保存日志: onSaveLog 用 [JFileChooser] 选保存文件, 格式化 [AppLog.logs] 写入文本
 *   (替代 app 端 copy logs/crash/logcat 到 backupPath 打包 logs.zip; 桌面端无 externalCache/CrashHandler)
 * - 堆转储: onCreateHeapDump 用 [com.sun.management.HotSpotDiagnosticMXBean.dumpHeap]
 *   (替代 app 端 CrashHandler.doHeapDump; 桌面端 JVM 内置 API, OpenJDK/Oracle JDK 均可用)
 * - updateLogSummary: app 端为 "版本 x.y.z" (AppConst.appInfo.versionName),
 *   桌面端 AppConst.appInfo 为 Android 端扩展 (AppConstAndroid.kt) 不可用, 改用
 *   [io.legado.desktop.constant.DesktopAppInfo.versionName] (仅版本号, 无 "版本" 前缀,
 *   因 "version" i18n key 在桌面端 registerDesktopAppStringProvider 兜底返回 key 名 "version")
 *
 * @param onBack 返回回调 (切回 SETTINGS 路由, 由 DesktopApp 注入)
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
) {
    // 桌面端 Provider 注入: 供 commonMain 的 PreferenceScreen 通过 LocalPreferenceStoreProvider 取依赖
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AppTitleBar(
                        title = rememberString("about"),
                        onBack = onBack,
                    )
                    // 顶部卡片: app 名 + 简介 (对照 app 端 AboutActivity.AboutHeaderCard, app 端有 desktop 原缺)
                    AboutHeaderCard()
                    AboutContent()
                }
            }
        }
    }
}

/**
 * 持有 updateLogSummary state 并构造各回调, 位置传参调用 [SharedAboutScreen]。
 *
 * 与 app 端 AboutActivity.Content 内 AboutScreen(...) 调用逐条对齐, 差异见顶层 KDoc。
 */
@Composable
private fun AboutContent() {
    // 桌面端 AppConst.appInfo (Android 扩展) 不可用, 改用 DesktopAppInfo.versionName
    // (读 MANIFEST.MF Implementation-Version / 回落 1.0.0); app 端为 "版本 x.y.z", 桌面端仅版本号
    val updateLogSummary = DesktopAppInfo.versionName
    // 通用协程作用域 (onSaveLog / onCreateHeapDump / onCheckUpdate 异步 IO 用)
    val scope = rememberCoroutineScope()

    // 检查更新结果状态 (onCheckUpdate 触发 DesktopAppUpdate.check, 新版本时回调 set; 末尾 AlertDialog 渲染)
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    // 应用日志对话框状态 (false=隐藏, true=显示; onCrashLog 触发, 末尾 AppLogDialog 渲染)
    var showLogDialog by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var privacyPolicyText by remember { mutableStateOf<String?>(null) }

    val licenseText = remember {
        try {
            {}::class.java.getResourceAsStream("/LICENSE.md")?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) { "" }
    }
    val disclaimerText = remember {
        try {
            {}::class.java.getResourceAsStream("/disclaimer.md")?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) { "" }
    }

    // 文案标签 (rememberString 是 @Composable, 顶层缓存; key 对齐 shared AboutScreen)
    val licenseTitle = rememberString("license")
    val disclaimerTitle = rememberString("disclaimer")
    val privacyPolicyTitle = rememberString("privacy_policy")

    SharedAboutScreen(
        updateLogSummary = updateLogSummary,
        onContributors = { browseUrl(CONTRIBUTORS_URL) },
        onTelegramGroup = { browseUrl(TELEGRAM_GROUP_URL) },
        onCheckUpdate = {
            // 调 DesktopAppUpdate.check (GitHub Release API): 新版本时回调 set updateInfo 弹窗;
            // 已是最新版本 / 网络失败由 check 内部 toast, 不回调 (替代 app 端 AppUpdate.check)
            scope.launch {
                DesktopAppUpdate.check { info ->
                    updateInfo = info
                }
            }
        },
        onCrashLog = {
            // 显示应用日志对话框 (替代 app 端 CrashLogsDialog, 桌面端用 AppLog 单例)
            showLogDialog = true
        },
        onSaveLog = {
            // 桌面端: JFileChooser 选保存文件, 格式化 AppLog.logs 写入
            // 对照 app 端 saveLog: copy logs/crash/logcat 到 backupPath 打包 logs.zip
            // 桌面端无 externalCache/CrashHandler, 简化为直接导出 AppLog 内存日志为文本
            scope.launch {
                withContext(Dispatchers.IO) {
                    val target = FileDialogs.pickSaveFile(defaultName = "legado_log.txt")
                    if (target != null) {
                        runCatching {
                            val logs = AppLog.logs
                            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                            val text = logs.joinToString("\n\n") { (time, msg, throwable) ->
                                val timeStr = if (time > 0) dateFormat.format(Date(time)) else ""
                                buildString {
                                    append("[$timeStr] $msg")
                                    throwable?.let { append("\n${it.stackTraceToString()}") }
                                }
                            }
                            target.writeText(text)
                        }.onSuccess {
                            Toasters.get().toast(jvmGetString("log_saved"))
                        }.onFailure {
                            AppLog.put(jvmGetString("save_log_failed") + "\n" + it.localizedMessage, it)
                            Toasters.get().toast(it.localizedMessage ?: jvmGetString("save_log_failed"))
                        }
                    }
                }
            }
        },
        onCreateHeapDump = {
            // 桌面端: JFileChooser 选保存 .hprof, 用 HotSpotDiagnosticMXBean.dumpHeap
            // 对照 app 端 createHeapDump: CrashHandler.doHeapDump + 拷贝到 backupPath
            // 桌面端无 CrashHandler, 直接用 JVM 内置 HotSpotDiagnosticMXBean 生成堆转储
            scope.launch {
                withContext(Dispatchers.IO) {
                    val target = FileDialogs.pickSaveFile(
                        defaultName = "legado_heap.hprof",
                        extensions = listOf("hprof"),
                    )
                    if (target != null) {
                        runCatching {
                            val bean = ManagementFactory.getPlatformMXBean(
                                com.sun.management.HotSpotDiagnosticMXBean::class.java
                            )
                            bean.dumpHeap(target.absolutePath, true)
                        }.onSuccess {
                            Toasters.get().toast(jvmGetString("heap_dump_saved"))
                        }.onFailure {
                            AppLog.put(jvmGetString("save_heap_dump_failed") + "\n" + it.localizedMessage, it)
                            Toasters.get().toast(it.localizedMessage ?: jvmGetString("save_heap_dump_failed"))
                        }
                    }
                }
            }
        },
        onPrivacyPolicy = {
            runCatching {
                {}::class.java.getResourceAsStream("/privacyPolicy.md")
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("privacyPolicy.md not found on classpath")
            }.onSuccess {
                privacyPolicyText = it
            }.onFailure {
                AppLog.put("Failed to load privacyPolicy.md\n${it.localizedMessage}", it)
                Toasters.get().toast(it.localizedMessage ?: jvmGetString("can_not_open"))
            }
        },
        onLicense = {
            // 显示开源许可 MD 对话框 (从 classpath 读 LICENSE.md)
            showLicense = true
        },
        onDisclaimer = {
            // 显示免责声明 MD 对话框 (从 classpath 读 disclaimer.md)
            showDisclaimer = true
        },
    )

    // ---- 应用日志对话框 (onCrashLog 触发, 调用 shared/sharedUiMain 下沉的 AppLogDialog) ----
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    privacyPolicyText?.let { content ->
        AppAlertDialog(
            onDismissRequest = { privacyPolicyText = null },
            title = privacyPolicyTitle,
            okButton = AlertButton(rememberString("ok")),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                MarkdownContentSelectable(content = content)
            }
        }
    }

    // ---- 开源许可对话框 (onLicense 触发) ----
    if (showLicense) {
        AppAlertDialog(
            onDismissRequest = { showLicense = false },
            title = licenseTitle,
            okButton = AlertButton(rememberString("ok")),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                MarkdownContentSelectable(content = licenseText)
            }
        }
    }

    // ---- 免责声明对话框 (onDisclaimer 触发) ----
    if (showDisclaimer) {
        AppAlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = disclaimerTitle,
            okButton = AlertButton(rememberString("ok")),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                MarkdownContentSelectable(content = disclaimerText)
            }
        }
    }

    // ---- 检查更新对话框 (onCheckUpdate 触发, DesktopAppUpdate.check 回调有新版本时显示) ----
    // 对照 app 端 AppUpdate.check 弹更新对话框 + 自动下载; 桌面端无 Intent, 仅 browse 打开下载 URL
    updateInfo?.let { info ->
        AppAlertDialog(
            onDismissRequest = { updateInfo = null },
            title = rememberString("found_new_version"),
            message = buildString {
                append(jvmGetString("latest_version") + ": " + info.latestVersion)
                if (info.releaseNotes.isNotBlank()) {
                    appendLine()
                    appendLine()
                    append(info.releaseNotes)
                }
            },
            okButton = AlertButton(rememberString("download_now"), dismissOnClick = false) {
                updateInfo = null
                // 打开下载 URL (Windows MSI 直链); 无 MSI 资源时回落到 releases 页面
                val url = info.downloadUrl.ifBlank { RELEASES_PAGE_URL }
                browseUrl(url)
            },
            cancelButton = AlertButton(rememberString("cancel")),
        )
    }
}

/**
 * 顶部卡片: app 名 + 简介 (对照 app 端 AboutActivity.AboutHeaderCard, 迁 activity_about ll_about)。
 *
 * 与 app 端差异:
 * - stringResource(R.string.xxx) → rememberString("xxx") (桌面端无 Android resources,
 *   registerDesktopAppStringProvider 兜底返回 key 名; 待桌面端引入 ResourceBundle 后自动生效)
 * - 圆角 8dp = Arco Design arco_radius_default (与 app 端 DesignTokens.shapeDefault 一致)
 * - 无 elevation (Arco Design 规范)
 */
@Composable
private fun AboutHeaderCard() {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(DesignTokens.shapeDefault)
            .background(colors.bottomBackground)
            .padding(16.dp),
    ) {
        Text(
            text = rememberString("app_name"),
            color = colors.primaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            text = rememberString("about_description"),
            color = colors.primaryText,
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// app 端 strings.xml 中对应 string 资源 (translatable="false"), 桌面端无 i18n 直接用字面量
private const val CONTRIBUTORS_URL = "https://github.com/huajideshutiao/legado/graphs/contributors"
private const val TELEGRAM_GROUP_URL = "https://t.me/+mT22ceIeiSllM2U1"
// 检查更新无 MSI 直链时回落到此 releases 页面 (与 DesktopAppUpdate 用的 GitHub repo 一致)
private const val RELEASES_PAGE_URL = "https://github.com/gedoor/legado/releases/latest"
