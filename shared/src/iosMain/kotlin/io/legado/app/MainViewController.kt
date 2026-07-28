package io.legado.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import io.legado.app.help.config.registerIosProviders
import io.legado.app.ui.IosNavHost
import io.legado.app.ui.SourceUiEventBridgeHost
import io.legado.app.ui.compose.platform.IosAppConfigProvider
import io.legado.app.ui.compose.platform.IosEventBusProvider
import io.legado.app.ui.compose.platform.IosPreferenceStoreProvider
import io.legado.app.ui.compose.platform.IosThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import platform.UIKit.UIViewController

/**
 * iOS 端 Compose 入口: 用 [ComposeUIViewController] 把 shared/sharedUiMain 下沉的
 * Composable 树包装为 [UIViewController], 由 SwiftUI / UIKit 宿主直接展示。
 *
 * # 调用方
 *
 * iOS app (SwiftUI): 在 `WindowGroup` 顶层用 `UIViewControllerRepresentable` 包装
 * [MainViewController] 返回值; 或 UIKit AppDelegate 直接 `rootViewController = MainViewController()`。
 * 这是 KMP iOS 标准入口模式 (参考 CMP 官方模板)。
 *
 * # 启动序列 (与 desktop `Main.kt` 对齐)
 *
 * 1. 调用 [registerIosProviders] 注册 commonMain 业务 provider
 *    (AppFilesDir / Preference / AppConfig / HTTP / Database / BookStorage /
 *     AppDbAccessor / BookHelpAccessor / SourceHelpAccessor / JsEngines / TtsEngine 等,
 *     详见 [io.legado.app.help.config.IosProviderRegistry] 注册顺序约束)
 * 2. 通过 [CompositionLocalProvider] 注入 4 个 Compose UI Provider
 *    (ThemeStore / AppConfig / EventBus / PreferenceStore)
 *    (对照 desktop Main.kt line 117-125 的注入模式)
 * 3. 用 [AppTheme] 包装内容, 提供主题色 + EInk 标记 + 文本样式
 * 4. 顶部 48dp 着色条: 用 themeStoreProvider.accentColor 让窗口顶部
 *    与主题色融洽 (对照 desktop Main.kt line 131-135), 不再呈现系统默认标题栏样式
 * 5. 主区域: 调用 [IosNavHost] (KP4 起替代原直接调 IosBookshelfScreen 的位置),
 *    内部用 when(currentRoute) 切换 5 个子路由 (BOOKSHELF/READER/SEARCH/BOOK_INFO/BOOK_SOURCE)
 *
 * # 当前 KP3/KP4 接入状态
 *
 * - iOS Compose Multiplatform 编译链路 (compose.compiler 已对 iOS 生效)
 * - Provider 注入链路 (4 个 iOS stub Provider + registerIosProviders)
 * - AppTheme 主题包装 (ThemeStoreProvider + AppConfigProvider + EventBusProvider)
 * - 真实业务 UI: [io.legado.app.ui.bookshelf.IosBookshelfScreen] (书架网格列表 + 封面加载 + 顶栏溢出菜单)
 * - 封面加载: [io.legado.app.ui.bookshelf.IosBookCover] (Coil3 + Ktor3 网络后端 + Skia 解码)
 * - JS 引擎: [io.legado.app.model.script.IosJsEngine] (quickjs cinterop 编译 C 源码, 与 Android/Desktop 端 quickjs 统一)
 * - HTTP 层: [io.legado.app.help.http.IosHttpProvider] (Ktor CIO 包装 KmpHttpClient)
 * - 数据库: [io.legado.app.data.IosDatabaseDriver] (Room KMP + NativeSQLiteDriver)
 * - TTS: [io.legado.app.help.tts.IosSystemTtsEngine] (AVSpeechSynthesizer)
 *
 * KP4 已接入阅读流子路由 (详见 [io.legado.app.ui.IosNavHost]):
 * - 阅读页: [io.legado.app.ui.reader.IosReaderScreen] (包装 shared/sharedUiMain ReadViewComposable)
 * - 搜索页: [io.legado.app.ui.search.IosSearchScreen] (包装 shared/sharedUiMain SearchScreen)
 * - 详情页: [io.legado.app.ui.bookinfo.IosBookInfoScreen] (包装 shared/sharedUiMain BookInfoScreen)
 * - 书源管理: [io.legado.app.ui.booksource.IosBookSourceScreen] (包装 shared/sharedUiMain BookSourceListScreen)
 *
 * 由 [IosNavHost] 顶层用 when(currentRoute) 切换 5 个子路由 (BOOKSHELF/READER/SEARCH/BOOK_INFO/BOOK_SOURCE),
 * 对照 desktop `DesktopApp.kt` 的路由组织模式。
 *
 * # macOS 编译命令 (Windows 无法编译 iOS target)
 *
 * ```
 * ./gradlew :shared:compileKotlinIosArm64
 * ./gradlew :shared:compileKotlinIosSimulatorArm64
 * ./gradlew :shared:linkDebugFrameworkIosArm64
 * ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
 * ```
 *
 * @return iOS [UIViewController], 由 SwiftUI / UIKit 宿主包装展示
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    // 1. 注册 commonMain 业务 provider (启动早期, 在任何业务代码访问 provider 之前)
    // 重复注册幂等 (provider 注册用 @Volatile var 覆盖, 详见各 registerIosXxx 实现)
    registerIosProviders()

    // 2. 注入 4 个 iOS Compose UI Provider (对照 desktop Main.kt line 117-125)
    val themeStoreProvider = remember { IosThemeStoreProvider() }
    val appConfigProvider = remember { IosAppConfigProvider() }
    val eventBusProvider = remember { IosEventBusProvider() }
    val preferenceStoreProvider = remember { IosPreferenceStoreProvider() }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStoreProvider,
        LocalAppConfigProvider provides appConfigProvider,
        LocalEventBusProvider provides eventBusProvider,
        LocalPreferenceStoreProvider provides preferenceStoreProvider,
    ) {
        AppTheme {
            // 3. 顶部 48dp 着色条 + 主区域 (对照 desktop Main.kt line 131-138)
            // accentColor 默认 #165DFF (arcoblue-6), 与 app 端 ThemeStore 兜底一致
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    color = themeStoreProvider.accentColor,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {}
                // 4. iOS 路由宿主 (KP4: 接入阅读流子路由, 包装 shared/sharedUiMain 各 Screen)
                // IosNavHost 内部用 when(currentRoute) 切换 BOOKSHELF/READER/SEARCH/BOOK_INFO/BOOK_SOURCE
                // 5 个子路由, 详见 io.legado.app.ui.IosNavHost
                IosNavHost()
            }
            // 5. 书源 UI 事件桥: 订阅 SOURCE_UI_REQUEST, 承接 JS 的 showLoginDialog/
            // showSourceVariableDialog 弹窗 (对照 desktop DesktopApp 的 SourceUiEventBridgeHost)
            SourceUiEventBridgeHost()
        }
    }
}
