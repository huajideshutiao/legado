//
//  iOSApp.swift
//  iosApp
//
//  legado KMP iOS 宿主入口 (SwiftUI App 生命周期)。
//  本文件 + ContentView.swift + Info.plist 构成最小 Xcode 工程骨架,
//  在 macOS 上用 Xcode 打开 iosApp.xcodeproj 即可编译运行。
//
//  ## 工程结构 (KMP 官方模板)
//
//  ```
//  iosApp/
//  ├── iOSApp.swift           <- 本文件: SwiftUI App 入口, @main 声明
//  ├── ContentView.swift      <- UI 根: UIViewControllerRepresentable 包装 MainViewController
//  ├── Info.plist             <- iOS App 配置 (权限/方向/启动屏)
//  ├── project.yml            <- XcodeGen 配置 (生成 .xcodeproj, 避免二进制冲突)
//  └── README.md              <- 在 macOS 上的构建说明
//  ```
//
//  ## 与 shared 模块的关系
//
//  - shared 模块 (Kotlin Multiplatform) 编译产出 `shared.framework`,
//    路径: `ui/build/bin/iosArm64/debugFramework/shared.framework`
//    (或 `iosSimulatorArm64` for 模拟器)
//  - iosApp 通过 CocoaPods / Framework 依赖引用 shared.framework
//  - `MainViewController()` 是 shared 模块导出的 Kotlin 函数,
//    在 Swift 端调用 `MainViewControllerKt.MainViewController()` 取 UIViewController
//
//  ## macOS 构建命令 (Windows 无法编译 iOS target)
//
//  ```bash
//  # 1. 编译 shared framework (在项目根目录)
//  ./gradlew :ui:linkDebugFrameworkIosArm64
//  ./gradlew :ui:linkDebugFrameworkIosSimulatorArm64
//
//  # 2. 用 XcodeGen 生成 .xcodeproj (如使用 project.yml)
//  brew install xcodegen
//  cd iosApp
//  xcodegen generate
//
//  # 3. 用 Xcode 打开并运行
//  open iosApp.xcodeproj
//  ```
//
//  ## Windows 开发说明
//
//  Windows 上无法编译 iOS target (需要 macOS + Xcode + Kotlin/Native iOS 工具链)。
//  Windows 开发者仅做 jvm/android/desktop target 时, 建议用
//  `-PenableIosTarget=false` 关闭 iOS target 以加速 gradle 配置阶段:
//
//  ```powershell
//  .\gradlew :app:assembleGithubDebug -PenableIosTarget=false
//  ```
//

import SwiftUI
import shared  // Kotlin Multiplatform shared framework (Kotlin 侧入口与 provider 注册)

/// shared 侧外部请求约定 (Swift 侧入口共用一份字面量, 避免拼错):
/// `IosLaunchRequests` 只把 `route:` 前缀的 uri 当路由, 而快捷项 userInfo 里存的是
/// 裸 routeName (与 app 端 intent extra `route` 同名), 前缀在 Swift 侧补。
private let legadoRouteUriPrefix = "route:"
/// 快捷项 userInfo 里承载裸 routeName 的键 (与 app 端 intent extra 同名)。
/// 通知那条链的 userInfo 键由 Kotlin `IosNotificationRouteKey` 独占, Swift 不再参与。
private let legadoRouteUserInfoKey = "route"

/// 外部 URI 投递闸门 (deep link / 文档打开 的全部系统投递入口都汇到 `handle`)。
///
/// # 为什么要闸门
///
/// 「把外部东西交给 legado」在 iOS 上有三个可能的投递点, 且本仓环境无法实测
/// (无 macOS/Xcode):
/// - SwiftUI `View.onOpenURL` —— 现有 legado:// 深链已经跑在这条路上
/// - `AppDelegate.application(_:open:options:)` —— 文档类型打开的传统投递点
/// - `UISceneDelegate.scene(_:openURLContexts:)` —— 补上自定义 scene delegate 后
///   (为接主屏快捷项) 才会出现的第三个点, 见 [LegadoSceneDelegate]
///
/// 三者到底"只走一条"还是"都发"无本地依据, 所以**都接**。同一次打开被多条通道各投一次时,
/// 由 shared 导航层确定性去重: `LegadoApp.pushIfNeeded` 比对栈顶路由与来路是否结构性相等,
/// 相等即不再叠第二层。这里**不做时间窗抑制** —— 上一版用 8 秒窗口抹, 会把"退出后立刻
/// 再开同一个文件"与"8 秒内重点同一个 legado:// 深链"一起吞掉 (表现为什么也不会发生),
/// 拿时间窗解"同一载荷到了两次"是兜底而不是方案。
/// ⚠️ 未查证: 三个点是否真会重复投, 真机上以 `external URI arrived again` 日志行为准;
///
/// # 为什么不做 stop 配对 security-scoped 授权
///
/// 就地打开 (`LSSupportsOpeningDocumentsInPlace=true`) 的 file URL 指向本 App 沙盒外,
/// 不 `startAccessingSecurityScopedResource()` 就读不到。而消费方是 Kotlin 异步队列
/// (播放器要贯穿整个播放会话), Swift 侧没有"读完了"的回调可配对 stop ——
/// 所以**故意不配对**: 把 URL 收进 `scopedURLs` 持有, 授权随会话有效, 进程退出自然回收。
/// 如果反过来在 `handle` 里 start 完就 stop, 会把能播的场景做成必失败。
///
/// 所有入口 (onOpenURL / application(_:open:) / scene(_:openURLContexts:)) 都在主线程,
/// 所以本闸门无需加锁。
private enum ExternalUriIngress {

    /// 取证用: 上一次投递的键与时刻。**只用来打时间差日志**, 不参与任何判定 ——
    /// 真机上 “同一地址二次到达的间隔” 就是“系统到底走一条还是两条”的唯一证据。
    private static var lastKey: String?
    private static var lastAt: TimeInterval = 0
    private static var scopedURLs: [Foundation.URL] = []

    /// 系统投递一条外部 URI (URL 形态: onOpenURL / AppDelegate / scene openURLContexts)。
    static func handle(_ url: Foundation.URL) {
        let now = ProcessInfo.processInfo.systemUptime
        let key = dedupeKey(url)
        let gap: TimeInterval = key == lastKey ? now - lastAt : -1
        lastKey = key
        lastAt = now
        if gap >= 0 {
            NSLog("[Legado] external URI arrived again after %.3fs: %@", gap, key)
        }
        route(url, uri: url.absoluteString)
    }

    /// 去重键: file URL 取标准化路径 —— 同一个文件在不同投递路径下可能给出
    /// percent-encoding 大小写不同的写法, 用 absoluteString 比会漏认成两条。
    private static func dedupeKey(_ url: Foundation.URL) -> String {
        url.isFileURL ? "file:" + url.standardizedFileURL.path : "uri:" + url.absoluteString
    }

    /// 按 scheme 分流到 shared 的两个入口。
    private static func route(_ url: Foundation.URL, uri: String) {
        let scheme = url.scheme?.lowercased() ?? ""
        if scheme == "legado" || scheme == "yuedu" {
            // 既有深链导入链 (commonMain LegadoDeepLinkHandler), 行为零变更
            _ = LegadoDeepLinkIosKt.handleLegadoDeepLink(url: uri)
            return
        }
        if url.isFileURL {
            // 沙盒内路径 (分享面板拷贝进 Inbox) 会返 false = 本就不需要 scope, 忽略返回值即可
            if url.startAccessingSecurityScopedResource() {
                scopedURLs.append(url)
            }
        }
        // file|content|app → LaunchRequest.ImportFile → shared 自动嗅探视频并改推播放页;
        // route: → NavigateTo; 其余 → DeepLink (见 IosPlatformServices.kt IosLaunchRequests.parse)
        _ = IosPlatformServicesKt.handleIosLaunchRequest(uri: uri)
    }
}

/// 主屏快捷项与场景 URL 的投递闸门。
///
/// # 为什么需要它
/// 主屏快捷项 (`UIApplicationShortcutItems`) 在场景化生命周期下**不走** AppDelegate:
/// - 应用未加载: 快捷项随 `UIScene.ConnectionOptions.shortcutItem` 到达;
/// - 应用已在后台: 系统调 `UIWindowSceneDelegate.windowScene(_:performActionFor:completionHandler:)`。
/// SwiftUI 生命周期对两者都没有等价入口, 所以只能补一个 `UIWindowSceneDelegate`
/// (Apple `UIApplicationDelegateAdaptor` 文档明确写了这条路径, 且只需在
/// `configurationForConnecting` 里填 `delegateClass`、不填 sceneClass/storyboard,
/// 场景本体仍由 SwiftUI 托管)。
///
/// # 场景 URL 一并接管
/// `scene(_:openURLContexts:)` 也接上同一闸门 —— 本仓无法在 Windows 上实测
/// "补了自定义 scene delegate 之后 SwiftUI 的 `onOpenURL` 还会不会发", 故两处都接;
/// 同一载荷到两次由 [ExternalUriIngress] 的去重链与 shared 导航层抹掉。
/// ⚠️ 未查证: `onOpenURL` 与 `scene(_:openURLContexts:)` 是否会被 `ExternalUriIngress`
/// 的"上次键 + 上次时刻"日志行报成连续两次, 以真机日志为准。
private enum ShortcutIngress {

    /// 冷启动相位已投递过的快捷项 (type + route 结构相等即视为同一枚)。
    /// 冷启动时同一枚快捷项可能同时经 `configurationForConnecting` 与
    /// `scene(_:willConnectTo:options:)` 到达 (Apple 未保证只走一条)。
    /// 用载荷结构比较去重, **不做时间窗** —— 时间窗会把"退出后立刻再点同一个快捷项"
    /// 一起吞掉 (与 [ExternalUriIngress] 删时间窗同一理由)。
    private static var lastColdStartPayload: String?

    /// 快捷项 → 既有 `route:` 投递口 (与深链/文件关联同一个 Swift→Kotlin 入口)。
    ///
    /// 无 `route` 的项 (书架) 就是"打开 App"本身, 与 app 端书架快捷项
    /// (`ShortCuts.buildBookShelfShortCutInfo` 不带任何 extra) 语义一致, 直接算已处理。
    @discardableResult
    static func handle(_ shortcutItem: UIApplicationShortcutItem, coldStart: Bool) -> Bool {
        let route = routeOf(shortcutItem)
        if coldStart {
            let payload = shortcutItem.type + "|" + (route ?? "")
            if payload == lastColdStartPayload {
                NSLog("[Legado] cold-start shortcut arrived again, dropped: %@", payload)
                return true
            }
            lastColdStartPayload = payload
        }
        guard let route, !route.isEmpty else {
            // 书架项: 打开 App 即完成, 不需要导航
            return true
        }
        _ = IosPlatformServicesKt.handleIosLaunchRequest(uri: legadoRouteUriPrefix + route)
        return true
    }

    /// 快捷项里携带的路由串: 静态项取 `UIApplicationShortcutItemUserInfo` 的
    /// route 键 (与 app 端 intent extra 同名)。
    private static func routeOf(_ shortcutItem: UIApplicationShortcutItem) -> String? {
        shortcutItem.userInfo?[legadoRouteUserInfoKey] as? String
    }
}

/// UIWindowSceneDelegate 适配器: 只补 SwiftUI 生命周期没有等价入口的两件事
/// (主屏快捷项 + 场景 URL 投递), 场景本体仍由 SwiftUI 托管。
/// 由 [AppDelegate.application(_:configurationForConnecting:options:)] 关联。
final class LegadoSceneDelegate: NSObject, UIWindowSceneDelegate {

    /// 新场景连接 (冷启动): 快捷项与 URL 都随 `connectionOptions` 到来。
    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        if let shortcutItem = connectionOptions.shortcutItem {
            ShortcutIngress.handle(shortcutItem, coldStart: true)
        }
        for context in connectionOptions.urlContexts {
            ExternalUriIngress.handle(context.url)
        }
    }

    /// 应用已在运行时点快捷项。
    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        completionHandler(ShortcutIngress.handle(shortcutItem, coldStart: false))
    }

    /// 场景 URL 投递点 (与 `onOpenURL` 同一闸门, 重复由 shared 导航层抹)。
    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        for context in URLContexts {
            ExternalUriIngress.handle(context.url)
        }
    }
}

/// UIApplicationDelegate 适配器。
///
/// `BGTaskScheduler.registerForTaskWithIdentifier` 必须在 `didFinishLaunching` 返回前调用,
/// SwiftUI 生命周期没有等价钩子, 故用 `@UIApplicationDelegateAdaptor` 把 delegate 挂回来。
///
/// 本类**不**实现 `UNUserNotificationCenterDelegate`: 通知点击回调完整归 Kotlin
/// (`IosNotificationDelegate`), Swift 侧只负责在对的时机把它装上 (见下)。
class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // commonMain 业务 provider 注册 (数据库/prefs/HTTP/JS 引擎等) + 缓存书籍后台续跑
        // 注册 (退后台收尾窗口 + BGProcessingTask 链式续约, 见 core iosMain
        // help/service/IosBackgroundTasks.kt), 进程级一次。对照 Android App.onCreate /
        // desktop main; 必须先于 Compose 场景创建 (BG 冷启动唤起也经本方法, 故后台路径
        // 同样拿到 provider)。BGTaskScheduler 的注册必须在 didFinishLaunching 返回前完成,
        // 故后台任务注册收在 registerIosProviders 内部而非 Swift 侧另起一行。
        IosProviderRegistryKt.registerIosProviders()
        // 通知中心 delegate 必须在 didFinishLaunching 返回前装好 (Apple 硬要求: 任何可能与
        // delegate 交互的操作之前就要赋好; 进程被回收后用户点通知的冷启动那一次投递最容易
        // 丢)。逻辑全在 Kotlin, 这里只把 Kotlin 的 delegate 提前装上;
        // MainViewController() 里那次同槽位重复赋值是保底, 无副作用。
        IosPlatformServicesKt.installIosNotificationDelegate()
        return true
    }

    /// 关联 [LegadoSceneDelegate]: 只填 `delegateClass` (不填 sceneClass/storyboard),
    /// 场景本体仍由 SwiftUI 托管 (Apple `UIApplicationDelegateAdaptor` 文档的官方写法)。
    ///
    /// 未查证项: 若设备上存着上一版本 (无自定义 scene delegate) 的 `UISceneSession`,
    /// 系统可能复用它而不调本方法 —— 该次启动的快捷项因此接不到; 本仓无 macOS 无法实测,
    /// 真机验证时需先删掉 App 重装一次再试长按快捷项。
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(
            name: nil,
            sessionRole: connectingSceneSession.role
        )
        if connectingSceneSession.role == .windowApplication {
            configuration.delegateClass = LegadoSceneDelegate.self
        }
        // 冷启动走快捷项时这里也能拿到同一枚项 (与 scene(_:willConnectTo:options:) 二取一,
        // 两处都接、由 ShortcutIngress 按载荷抹掉重复)
        if let shortcutItem = options.shortcutItem {
            ShortcutIngress.handle(shortcutItem, coldStart: true)
        }
        return configuration
    }

    /// 文档类型 / URL scheme 的传统投递点。
    ///
    /// 与 `onOpenURL` / `scene(_:openURLContexts:)` 是同一扇门的三张不同票: 在场景化生命周期下
    /// Apple 到底只发哪几张无本地依据, 故都接; 同一次打开被投多次时由 shared 导航层抹掉
    /// (`LegadoApp.pushIfNeeded` 比栈顶路由), **不在这里做时间窗抑制**。详见闸门自述。
    func application(
        _ application: UIApplication,
        open url: Foundation.URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        ExternalUriIngress.handle(url)
        return true
    }
}

/// SwiftUI App 入口 (iOS 14+ 生命周期)。
///
/// 用 `@main` 声明为 App 启动点, SwiftUI 框架自动调用 `body` 渲染根视图。
/// 根视图为 `ContentView` (用 `UIViewControllerRepresentable` 包装 Kotlin 端 `MainViewController`)。
@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    /// 状态栏显隐: 阅读页 hideStatusBar 配置 / 全屏路由由 Kotlin 侧 IosWindowController.setSystemBars
    /// 经 NSNotificationCenter 桥接驱动 (Kotlin 常量: IosStatusBarHiddenNotification = "legado.statusBarHidden",
    /// userInfo key "hidden" = Bool)。SwiftUI 宿主下 VC 的 prefersStatusBarHidden 不生效,
    /// .statusBarHidden modifier 是唯一可靠的系统栏控制方式 (iOS 13+)。
    @State private var statusBarHidden = false

    /// SwiftUI App 入口, 根视图为 ContentView。
    var body: some Scene {
        WindowGroup {
            ContentView()
                // Compose 表面 edge-to-edge: shared UI 按 Android edge-to-edge 模型设计,
                // 安全区回避由 Compose 内部 WindowInsets padding 单次承担 (各页顶栏
                // statusBarsPadding / 底栏 navigationBarsPadding)。SwiftUI 默认把
                // representable 排在安全区内, 与 Compose 内部回避叠加成双倍留白
                // (顶栏双倍状态栏高 / 底栏双倍 home indicator), 沉浸式背景 (详情页模糊
                // 封面 / 阅读页背景) 也无法延伸到系统栏底下。
                .ignoresSafeArea()
                .statusBarHidden(statusBarHidden)
                .onReceive(
                    NotificationCenter.default.publisher(
                        for: Notification.Name("legado.statusBarHidden")
                    )
                ) { note in
                    statusBarHidden = note.userInfo?["hidden"] as? Bool ?? false
                }
                // 外部 URI 入口之一 (另两个见上面 AppDelegate.application(_:open:options:)
                // 与 LegadoSceneDelegate.scene(_:openURLContexts:), 共三处):
                // 三处都接不是为了重复处理, 而是本仓无 macOS 可实测"系统到底走哪条";
                // 重复投递由 shared 的 LegadoApp.pushIfNeeded 按"栈顶已是同一载荷"抹掉
                // (时间窗已删 —— 它会把 8 秒内重同一个 legado:// 深链一起吞掉)。
                // 分流: legado:// 与 yuedu:// → 既有深链导入链 (行为零变更);
                // 其余 (含 CFBundleDocumentTypes 命中的 file:// 文档打开) → shared 启动请求解析器
                // IosLaunchRequests.parse: file|content|app → LaunchRequest.ImportFile,
                // 再由 LegadoApp.handleLaunchRequest 嗅探视频并改推
                // AppRoute.VideoPlay(VideoPlayTarget.Direct) (不查书/不落进度/不写书签)。
                .onOpenURL { url in
                    ExternalUriIngress.handle(url)
                }
        }
    }
}
