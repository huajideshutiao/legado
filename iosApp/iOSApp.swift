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
//    路径: `shared/build/bin/iosArm64/debugFramework/shared.framework`
//    (或 `iosSimulatorArm64` for 模拟器)
//  - iosApp 通过 CocoaPods / Framework 依赖引用 shared.framework
//  - `MainViewController()` 是 shared 模块导出的 Kotlin 函数,
//    在 Swift 端调用 `MainViewControllerKt.MainViewController()` 取 UIViewController
//
//  ## macOS 构建命令 (Windows 无法编译 iOS target)
//
//  ```bash
//  # 1. 编译 shared framework (在项目根目录)
//  ./gradlew :shared:linkDebugFrameworkIosArm64
//  ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
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
import shared  // Kotlin Multiplatform shared framework (deep link 入口)

/// 外部 URI 投递闸门 (deep link / 文档打开 的全部系统投递入口都汇到 `handle`)。
///
/// # 为什么要闸门
///
/// 「把外部东西交给 legado」在 iOS 上有两个可能的投递点, 且本仓环境无法实测
/// (无 macOS/Xcode, 也无法拉 Apple 文档原文核验):
/// - SwiftUI `View.onOpenURL` —— 现有 legado:// 深链已经跑在这条路上
/// - `AppDelegate.application(_:open:options:)` —— 文档类型打开的传统投递点
///
/// 两者到底是"只走一条"还是"两条都发"无本地依据 (Apple 文档页拓取 404, 也起不了模拟器),
/// 所以**两条都接**。同一次打开被两条通道各投一次时, 由 shared 导航层确定性去重:
/// `LegadoApp.pushIfNeeded` 比对栈顶路由与来路是否结构性相等, 相等即不再叠第二层。
/// 这里**不做时间窗抑制** —— 上一版用 8 秒窗口抹, 会把"退出后立刻再开同一个文件"
/// 与"8 秒内重点同一个 legado:// 深链"一起吞掉 (表现为什么也不会发生),
/// 拿时间窗解"同一载荷到了两次"是兜底而不是方案。
///
/// # 为什么不做 stop 配对 security-scoped 授权
///
/// 就地打开 (`LSSupportsOpeningDocumentsInPlace=true`) 的 file URL 指向本 App 沙盒外,
/// 不 `startAccessingSecurityScopedResource()` 就读不到。而消费方是 Kotlin 异步队列
/// (播放器要贯穿整个播放会话), Swift 侧没有"读完了"的回调可配对 stop ——
/// 所以**故意不配对**: 把 URL 收进 `scopedURLs` 持有, 授权随会话有效, 进程退出自然回收。
/// 如果反过来在 `handle` 里 start 完就 stop, 会把能播的场景做成必失败。
///
/// 两个入口都在主线程, 所以本闸门无需加锁。
private enum ExternalUriIngress {

    /// 取证用: 上一次投递的键与时刻。**只用来打时间差日志**, 不参与任何判定 ——
    /// 真机上 “同一地址二次到达的间隔” 就是“系统到底走一条还是两条”的唯一证据。
    private static var lastKey: String?
    private static var lastAt: TimeInterval = 0
    private static var scopedURLs: [URL] = []

    /// 系统投递一条外部 URI。两条通道都投递时两条都路由, 重复层由 shared 抹。
    static func handle(_ url: URL) {
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
    private static func dedupeKey(_ url: URL) -> String {
        url.isFileURL ? "file:" + url.standardizedFileURL.path : "uri:" + url.absoluteString
    }

    /// 按 scheme 分流到 shared 的两个入口。
    private static func route(_ url: URL, uri: String) {
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

/// UIApplicationDelegate 适配器。
///
/// `BGTaskScheduler.registerForTaskWithIdentifier` 必须在 `didFinishLaunching` 返回前调用,
/// SwiftUI 生命周期没有等价钩子, 故用 `@UIApplicationDelegateAdaptor` 把 delegate 挂回来。
class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // commonMain 业务 provider 注册 (数据库/prefs/HTTP/JS 引擎等), 进程级一次。
        // 对照 Android App.onCreate / desktop main; 必须先于下面的后台任务注册与
        // Compose 场景创建 (BG 冷启动唤起也经本方法, 故后台路径同样拿到 provider)。
        IosProviderRegistryKt.registerIosProviders()
        // 缓存书籍的后台续跑 (退后台收尾窗口 + BGProcessingTask 链式续约),
        // 实现见 shared iosMain help/service/IosBackgroundTasks.kt
        IosBackgroundTasksKt.registerIosBackgroundTasks()
        return true
    }

    /// 文档类型 / URL scheme 的传统投递点。
    ///
    /// 与 `onOpenURL` 开的是同一扇门的两张不同票: 在场景化生命周期下 Apple 到底只发一张
    /// 还是两张都发无本地依据 (Apple 文档页拓取 404, 本机无 Xcode 可实跑), 故两条都接;
    /// 同一次打开被投两次时由 shared 导航层抹掉 (`LegadoApp.pushIfNeeded` 比栈顶路由),
    /// **不在这里做时间窗抑制**。详见闸门自述。
    func application(
        _ application: UIApplication,
        open url: URL,
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
                // 外部 URI 入口之一 (另一个见上面 AppDelegate.application(_:open:options:)):
                // 两条都接不是为了重复处理, 而是本仓无 macOS 可实测"系统到底走哪条";
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
