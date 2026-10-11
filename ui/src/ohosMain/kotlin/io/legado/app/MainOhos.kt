package io.legado.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.ComposeArkUIViewController
import io.legado.app.constant.AppLog
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadConfigProviders
import io.legado.app.help.config.registerOhosProviders
import io.legado.app.help.registerComposeDefaultDataResourceProvider
import io.legado.app.help.StartupMaintenanceShared
import io.legado.app.help.AppVersionProvider
import io.legado.app.help.AppVersionProviders
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.browser.OhosWebViewSlot
import io.legado.app.ui.OhosPlatformCapabilities
import io.legado.app.ui.about.notifyAppCrash
import io.legado.app.ui.about.preferenceUpVersionStore
import io.legado.app.ui.about.upVersion
import io.legado.app.ui.about.versionNameToVersionCode
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.audio.SharedAudioPlayPlatformProvider
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.manga.OhosMangaReaderPlatform
import io.legado.app.ui.book.read.OhosReaderPlatformProvider
import io.legado.app.ui.book.read.ReaderPlatformProviders
import io.legado.app.ui.book.source.SourceUiEventBridgeHost
import io.legado.app.ui.book.video.OhosVideoPlayPlatformProvider
import io.legado.app.ui.book.video.VideoPlayPlatformProviders
import io.legado.app.ui.association.DeepLinkImportHost
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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.initMainHandler
import kotlinx.coroutines.withContext
import platform.ArkTS.ArkTS_Napi_NativeModule.napi_env
import platform.ArkTS.ArkTS_Napi_NativeModule.napi_value
import kotlin.experimental.ExperimentalNativeApi

/**
 * 鸿蒙端 Compose 入口 (零薄壳: 直接调用 shared LegadoApp)。
 *
 * [MainArkUIViewController] 由 CPF 融合渲染宿主创建并接入 ArkUI RenderNode。
 */
@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("MainArkUIViewController")
fun MainArkUIViewController(env: napi_env): napi_value {
    initMainHandler(env)
    return ComposeArkUIViewController(env) {
        MainOhos()
    }
}

@Composable
fun MainOhos() {
    // provider 注册 (首次组合时执行一次, 幂等)
    // 注: 字符串通道与默认数据 provider 的实现都在 :ui commonMain (经 composeResources
    // 生成的 Res 取数), 字符串通道随 registerOhosProviders() 注册 (外部启动请求可能早于
    // 首帧组合取串), 默认数据 provider 须在其之前注册 (core 序列里的 directLinkUpload
    // 依赖 DefaultDataResourceProvider)。
    remember { registerComposeDefaultDataResourceProvider() }
    remember { registerOhosProviders() }
    // 注册平台能力 (供 shared LegadoApp 经 PlatformCapabilityProviders.get() 取能力)
    remember { PlatformCapabilityProviders.register(OhosPlatformCapabilities) }
    // 注册平台服务 (11 项能力经 napi 桥接 ArkTS, 供 shared LegadoApp 经 PlatformServiceProviders.get() 取用)
    remember { PlatformServiceProviders.register(OhosPlatformServices) }
    // 注册 4 个媒体平台 Provider (Reader/Audio/Manga/Video, 均为真实实现)
    remember { ReaderPlatformProviders.register(OhosReaderPlatformProvider) }
    // 当前版本号 provider (供启动期默认数据补齐按版本推进; 鸿蒙无 PackageManager,
    // 取 ArkTS 注入的版本名经 versionNameToVersionCode 解析 —— 与下方 "更新日志" 弹窗同一口径)。
    // getAppVersionName() 可能在首次调用时才注入就绪, 故这里只注册 provider、把取值留到实际读取时
    // (provider 的 versionCode 是 getter, 每次读都重新取): 取不到返回 0, 启动维护按"版本未推进"
    // 直接返回, 下次启动再判 —— 不会把"当前版本 0"写进配置。
    remember {
        AppVersionProviders.register(object : AppVersionProvider {
            override val versionCode: Long
                get() = OhosPlatformCapabilities.getAppVersionName()
                    ?.let { versionNameToVersionCode(it) } ?: 0L
        })
    }
    remember { AudioPlayPlatformProviders.register(SharedAudioPlayPlatformProvider) }
    remember { MangaReaderScreenModel.Providers.register(OhosMangaReaderPlatform) }
    remember { VideoPlayPlatformProviders.register(OhosVideoPlayPlatformProvider) }

    // 零薄壳导航: AppNavigator 替代原平台导航宿主的 20+ 并行状态字段
    val navigator = remember { AppNavigator(AppRoute.Main()) }
    val screenModelStore = remember { ScreenModelStore() }

    // 显示密度回灌 (isTablet 的判据用): 屏幕尺寸桥只给物理像素 (vp × densityPixels),
    // 而"是否平板"要和安卓端同口径 (屏幕短边 vp 档位), 换算所需的密度只有 Compose 侧拿得到。
    // 与 shared AppDialogSizes 用 LocalDensity 把屏幕物理像素换成 dp 同款口径;
    // fontScale (应用内字体缩放) 不参与 —— 那是个阅读偏好, 不是设备形态。
    val displayDensity = LocalDensity.current.density
    SideEffect { OhosPlatformCapabilities.updateDisplayDensity(displayDensity) }

    // 注入 3 个鸿蒙 Compose UI Provider
    val themeStoreProvider = remember { SharedThemeStoreProvider() }
    val appConfigProvider = remember { SharedAppConfigProvider() }
    val eventBusProvider = remember { SharedEventBusProvider() }

    // 阅读页注入点: 未注入时 LocalReadConfigProviders 取值即 error,
    // 阅读页与 EffectiveReplaces 路由会崩 (默认值为 error 而非兜底实现);
    // 实例来自全局注册 (须在 registerOhosProviders 之后), 与设置弹窗读写同一份配置
    val readConfigProviders = remember { ReadConfigProviders() }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStoreProvider,
        LocalAppConfigProvider provides appConfigProvider,
        LocalEventBusProvider provides eventBusProvider,
        LocalReadConfigProviders provides readConfigProviders,
        LocalWebViewSlot provides { config, modifier, callbacks ->
            OhosWebViewSlot(config, modifier, callbacks)
        },
    ) {
        // 字体缩放: 把"界面设置→字体大小"档位接到 LocalDensity (安卓由 AppContextWrapper.wrap
        // 写进 Configuration.fontScale 生效, 不需要这一层)。未设档位时不覆写, 保留平台自身
        // fontScale (iOS 跟系统 Dynamic Type, 桌面/鸿蒙恒 1) —— 语义对照原版 getFontScale
        AppFontScaleScope {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = AppTheme.colors.background) {
                    // 零薄壳: shared LegadoApp 统一管理导航栈 + ScreenModel 生命周期,
                    // 所有路由由 shared RouteContent 直接渲染; 根级键盘焦点由 shared 内部处理
                    // (handleBackKey 与焦点节点同链, 无控件持焦时键盘事件仍可达)
                    LegadoApp(
                        navigator = navigator,
                        screenModelStore = screenModelStore,
                    )
                    // 启动期维护 (下沉件 StartupMaintenanceShared, 与 Android/桌面/iOS 同一份):
                    // 默认数据补齐 + 缓存清理 + WebDav 进度同步。必须排在下方"升级更新日志"之前
                    // —— 维护的"这一版要不要补默认数据"与弹窗"这一版要不要展示"读的是**同一份存储的
                    // 同一个 key** (`appVersionCode`), 而写回它的只有弹窗: 弹窗先跑就会把维护的门关死,
                    // 那一版要补的预置分组/默认资源就再也补不上了 (与 app 端 App.onCreate 的
                    // DefaultData.upVersion() 先于 MainActivity 同一时序)。
                    // 异步、不阻塞首帧 (LaunchedEffect 自身就是后台协程)。
                    // 升级更新日志 (对照原版 MainActivity.upVersion): 首启弹帮助, 升级弹更新日志;
                    // 鸿蒙无构建类型感知, isDebug 恒 false (对齐 release 行为);
                    // 版本名未注入 (ArkTS 注入时机竞态) 时不推进版本号, 下次启动再判。
                    //
                    // 启动期维护必须在同一协程内排在弹窗之前: 弹窗会写回 appVersionCode,
                    // 而维护也拿这个键当门 (两个 LaunchedEffect 并发时谁先写谁关门, 结果是预置项
                    // 可能被永久跳过)。Android (onCreate → onPostCreate) 与桌面
                    // (注册内含 initDesktopDefaultData → 再 upVersion) 本来就是这个串行序。
                    LaunchedEffect(Unit) {
                        StartupMaintenanceShared.initDefaultData()
                        // cacheExtraMayHaveWork=false: 鸿蒙的 clearCacheExtra 是空实现
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
                            // 弹框本体在平台能力对象里 (鸿蒙对话框桥是同步阻塞的, 调用方不可在主线程调,
                            // 该方法自带 IO 作用域)
                            OhosPlatformCapabilities.presentWebDavNewBackupConfirm(newBackup.name)
                        }
                        OhosPlatformCapabilities.getAppVersionName()?.let { versionName ->
                            upVersion(
                                versionNameToVersionCode(versionName),
                                isDebug = false,
                                preferenceUpVersionStore(),
                            )
                        }
                        // 崩溃后重启提示 (对照原版 MainActivity.notifyAppCrash): 排在版本更新之后,
                        // 与 app 端同一位置; 标记由 OhosCrashHandler 落盘时置位。
                        // 鸿蒙无构建类型感知, isDebug 恒 false (对齐 release 行为)
                        notifyAppCrash(isDebug = false)
                    }
                    // legado:// deep link 导入宿主
                    DeepLinkImportHost()
                }
                // 书源 UI 事件桥
                SourceUiEventBridgeHost()
                // 阅读页长按文本的自绘浮动操作菜单 (四端同一份, 见 shared ReaderTextActionMenu)
                OhosReaderPlatformProvider.TextSelectionHost()
                // 阅读页长按图片的自绘浮动操作菜单 (与文本菜单同款样式)
                ReaderImageActionMenu.Host()
                // 阅读页文本操作菜单查词宿主 (四端同一份, 见 shared ReaderDictWord)
                ReaderDictWord.Host()
            }
        }
    }
}
