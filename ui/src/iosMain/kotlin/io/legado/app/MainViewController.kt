package io.legado.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.ComposeUIViewController
import io.legado.app.constant.AppLog
import io.legado.app.help.config.NativeSystemTheme
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadConfigProviders
import io.legado.app.help.StartupMaintenanceShared
import io.legado.app.help.AppVersionProvider
import io.legado.app.help.AppVersionProviders
import io.legado.app.help.versionNameToVersionCode
import io.legado.app.help.AppWebDavShared
import io.legado.app.ui.book.info.LocalBlurCoverBgSlot
import io.legado.app.ui.book.info.SharedBlurCoverBgCoil
import io.legado.app.ui.browser.IosWebViewSlot
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.IosPlatformCapabilities
import io.legado.app.ui.IosPlatformServices
import io.legado.app.ui.IosNotificationPublisher
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.audio.SharedAudioPlayPlatformProvider
import io.legado.app.ui.book.manga.IosMangaReaderPlatform
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.read.IosReaderPlatformProvider
import io.legado.app.ui.book.read.ReaderPlatformProviders
import io.legado.app.ui.book.source.SourceUiEventBridgeHost
import io.legado.app.ui.book.video.IosVideoPlayPlatformProvider
import io.legado.app.ui.book.video.VideoPlayPlatformProviders
import io.legado.app.ui.association.DeepLinkImportHost
import io.legado.app.ui.about.notifyAppCrash
import io.legado.app.ui.about.preferenceUpVersionStore
import io.legado.app.ui.about.upVersion
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.SharedAppConfigProvider
import io.legado.app.ui.compose.platform.SharedEventBusProvider
import io.legado.app.ui.compose.platform.SharedThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.reader.ReaderDictWord
import io.legado.app.ui.reader.ReaderImageActionMenu
import io.legado.app.ui.root.AppFontScaleScope
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.LegadoApp
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.ScreenModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.UIKit.UIViewController

/**
 * iOS 端 Compose 入口: 用 [ComposeUIViewController] 把 shared/sharedUiMain 下沉的
 * Composable 树包装为 [UIViewController], 由 SwiftUI / UIKit 宿主直接展示。
 *
 * 零薄壳: shared [LegadoApp] 统一管理导航栈 + ScreenModel 生命周期,
 * 所有路由由 shared RouteContent 直接渲染, 不再维护并行状态字段。
 *
 * commonMain 业务 provider 由宿主在 didFinishLaunching 注册 (见 iosApp/iOSApp.swift 的
 * registerIosProviders), 本函数只补 UI 入口级平台 provider。
 */
fun MainViewController(): UIViewController {
    // 平台能力/服务 + 4 个媒体 Provider (对照 Android MainActivity.initializePlatform):
    // 均为 object 单例, 在组合之外注册, 系统深浅色切换重组时不重跑
    PlatformCapabilityProviders.register(IosPlatformCapabilities)
    PlatformServiceProviders.register(IosPlatformServices)
    ReaderPlatformProviders.register(IosReaderPlatformProvider)
    AudioPlayPlatformProviders.register(SharedAudioPlayPlatformProvider)
    MangaReaderScreenModel.Providers.register(IosMangaReaderPlatform)
    VideoPlayPlatformProviders.register(IosVideoPlayPlatformProvider)
    // 通知点击处理 (解出 userInfo 里的路由 → LaunchRequestBus 导航)。
    // 须在 UI 首帧之前挂上: 冷启动时用户点通知, 系统可能在首帧前就派发 response。
    IosNotificationPublisher.installDelegate()
    // 当前版本号 provider (供启动期默认数据补齐按版本推进; iOS 无 PackageManager,
    // 取 CFBundleShortVersionString 经 versionNameToVersionCode 解析成单调版本号 —— 与下方
    // "更新日志" 弹窗用同一个值, 保证两边对"当前是哪个版本"的口径一致)。
    // 在这里 (组合之前, 非 @Composable 上下文) 注册: 下方 LaunchedEffect 内才注册会让"谁先读版本号"
    // 变成隐式约定, 而启动维护协程与弹窗协程是并发的。
    AppVersionProviders.register(object : AppVersionProvider {
        override val versionCode: Long
            get() = IosPlatformCapabilities.getAppVersionName()
                ?.let { versionNameToVersionCode(it) } ?: 0L
    })
    return ComposeUIViewController {
        // 注入 3 个 iOS Compose UI Provider (对照 desktop Main.kt 阶段2)
        val themeStoreProvider = remember { SharedThemeStoreProvider() }
        val appConfigProvider = remember { SharedAppConfigProvider() }
        val eventBusProvider = remember { SharedEventBusProvider() }

        // 阅读页注入点: 未注入时 LocalReadConfigProviders 取值即 error,
        // 阅读页与 EffectiveReplaces 路由会崩 (默认值为 error 而非兜底实现);
        // 实例来自全局注册 (iOSApp.swift didFinishLaunching 的 registerIosProviders),
        // 与设置弹窗读写同一份配置
        val readConfigProviders = remember { ReadConfigProviders() }

        // 零薄壳: AppNavigator + ScreenModelStore 是唯一状态源 (对照 desktop Main.kt line 346-347)
        val navigator = remember { AppNavigator(AppRoute.Main()) }
        val screenModelStore = remember { ScreenModelStore() }

        // 系统深色跟随 (themeMode="0"): 使用 Compose 标准 isSystemInDarkTheme() API,
        // 在系统深浅色切换时触发 LaunchedEffect 回写业务层 NativeSystemTheme 缓存
        val isSystemDark = isSystemInDarkTheme()
        LaunchedEffect(isSystemDark) {
            NativeSystemTheme.update(isSystemDark)
        }

        CompositionLocalProvider(
            LocalThemeStoreProvider provides themeStoreProvider,
            LocalAppConfigProvider provides appConfigProvider,
            LocalEventBusProvider provides eventBusProvider,
            LocalReadConfigProviders provides readConfigProviders,
            LocalWebViewSlot provides { config, modifier, callbacks ->
                IosWebViewSlot(config, modifier, callbacks)
            },
            // 注入 Coil3 模糊封面背景到 shared 详情页路由, 覆盖 LocalBlurCoverBgSlot 兜底
            LocalBlurCoverBgSlot provides { book, coverTick, inBookshelf, isEInkMode, modifier, land ->
                SharedBlurCoverBgCoil(book, coverTick, inBookshelf, isEInkMode, modifier, land)
            },
        ) {
            // 字体缩放: 把"界面设置→字体大小"档位接到 LocalDensity (安卓由 AppContextWrapper.wrap
            // 写进 Configuration.fontScale 生效, 不需要这一层)。未设档位时不覆写, 保留平台自身
            // fontScale (iOS 跟系统 Dynamic Type, 桌面/鸿蒙恒 1) —— 语义对照原版 getFontScale
            AppFontScaleScope {
                // 对话框尺寸锚点取宿主窗口可用区 (对话框层读到的是对话框自己的窗口, 会与内容互相反馈)
                AppTheme(anchor = LocalWindowInfo.current.containerSize) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 零薄壳: shared LegadoApp 统一管理导航栈 + ScreenModel 生命周期,
                        // 所有路由由 shared RouteContent 直接渲染; 根级键盘焦点由 shared 内部处理
                        // (handleBackKey 与焦点节点同链, 无控件持焦时键盘事件仍可达)
                        LegadoApp(
                            navigator = navigator,
                            screenModelStore = screenModelStore,
                        )
                        // 启动期维护 (下沉件 StartupMaintenanceShared, 与 Android/桌面同一份):
                        // 默认数据补齐 + 缓存清理 + WebDav 进度同步。必须排在下方"升级更新日志"之前
                        // —— 维护的"这一版要不要补默认数据"与弹窗"这一版要不要展示"读的是**同一份
                        // 存储的同一个 key** (`appVersionCode`), 而写回它的只有弹窗: 弹窗先跑就会把
                        // 维护的门关死, 那一版要补的预置分组/默认资源就再也补不上了 (与 app 端
                        // App.onCreate 的 DefaultData.upVersion() 先于 MainActivity 同一时序)。
                        // 异步、不阻塞首帧 (LaunchedEffect 自身就是后台协程)。
                        // 升级更新日志 (对照原版 MainActivity.upVersion): 首启弹帮助, 升级弹更新日志;
                        // iOS 无构建类型感知, isDebug 恒 false (对齐 release 行为);
                        // 版本名取不到时不推进版本号, 下次启动再判。
                        //
                        // 启动期维护必须在同一协程内排在弹窗之前: 弹窗会写回 appVersionCode,
                        // 而维护也拿这个键当门 (两个 LaunchedEffect 并发时谁先写谁关门, 结果是预置项
                        // 可能被永久跳过)。Android (onCreate → onPostCreate) 与桌面
                        // (注册内含 initDesktopDefaultData → 再 upVersion) 本来就是这个串行序。
                        LaunchedEffect(Unit) {
                            StartupMaintenanceShared.initDefaultData()
                            // cacheExtraMayHaveWork=false: iOS 的 clearCacheExtra 是空实现
                            StartupMaintenanceShared.clearInvalidCacheIfDue(cacheExtraMayHaveWork = false)
                            StartupMaintenanceShared.syncBookProgressOnStartup()
                            // 云端有新备份时弹本端确认框 (对照 app 端 MainActivity.backupSync):
                            // 标题 restore / 正文 webdav_after_local_restore_confirm, 用户确认后走共享恢复
                            // 入口 AppWebDavShared.restoreWebDav; 判定函数在"判定成立"时已回写"已提示过的
                            // 时间戳"(用户这次拒绝也不会重复弹), 故这里只在拿到 NewBackup 时弹。
                            // lastBackUp 会走网络, 下沉件自身不切线程, 故判定与认证都留在 IO。
                            val newBackup = withContext(Dispatchers.IO) {
                                // 认证先补一次: lastBackUp 首行是 authorization ?: return, app 端那份认证
                                // 来自 AppWebDav 的初始化块, 本端只有 syncBookProgress 开启时上一行才认证过
                                // (关闭时为 null → 判定恒 None, 提示永不出现), 故这里与
                                // syncBookProgressOnStartup 同款补认证 (已认证则跳过, 不重复建目录)
                                if (AppWebDavShared.authorization == null) {
                                    runCatching { AppWebDavShared.upConfig() }
                                        .onFailure {
                                            AppLog.put("启动期 WebDav 认证失败\n${it.message}", it)
                                        }
                                }
                                StartupMaintenanceShared.checkNewWebDavBackup()
                            }
                            if (newBackup is StartupMaintenanceShared.NewBackupCheckResult.NewBackup) {
                                IosPlatformCapabilities.presentWebDavNewBackupConfirm(newBackup.name)
                            }
                            IosPlatformCapabilities.getAppVersionName()?.let { versionName ->
                                upVersion(
                                    versionNameToVersionCode(versionName),
                                    isDebug = false,
                                    preferenceUpVersionStore(),
                                )
                            }
                            // 崩溃后重启提示 (对照原版 MainActivity.notifyAppCrash): 排在版本更新
                            // 之后, 与 app 端同一位置; 标记由 IosCrashHandler 落盘时置位。
                            // iOS 无构建类型感知, isDebug 恒 false (对齐 release 行为)
                            notifyAppCrash(isDebug = false)
                        }
                    }
                    // 书源 UI 事件桥: 订阅 SOURCE_UI_REQUEST, 承接 JS 的 showLoginDialog/
                    // showSourceVariableDialog 弹窗 (对照 desktop Main.kt line 391)
                    SourceUiEventBridgeHost()
                    // legado:// deep link 导入对话框宿主 (投递侧: iOSApp.swift onOpenURL → handleLegadoDeepLink)
                    DeepLinkImportHost()
                    // 阅读页长按文本的自绘浮动操作菜单 (四端同一份, 见 shared ReaderTextActionMenu)
                    IosReaderPlatformProvider.TextSelectionHost()
                    // 阅读页长按图片的自绘浮动操作菜单 (与文本菜单同款样式)
                    ReaderImageActionMenu.Host()
                    // 阅读页文本操作菜单查词宿主 (四端同一份, 见 shared ReaderDictWord)
                    ReaderDictWord.Host()
                }
            }
        }
    }
}
