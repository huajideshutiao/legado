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

/// UIApplicationDelegate 适配器。
///
/// `BGTaskScheduler.registerForTaskWithIdentifier` 必须在 `didFinishLaunching` 返回前调用,
/// SwiftUI 生命周期没有等价钩子, 故用 `@UIApplicationDelegateAdaptor` 把 delegate 挂回来。
class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // 缓存书籍的后台续跑 (退后台收尾窗口 + BGProcessingTask 链式续约),
        // 实现见 shared iosMain help/service/IosBackgroundTasks.kt
        IosBackgroundTasksKt.registerIosBackgroundTasks()
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
                .statusBarHidden(statusBarHidden)
                .onReceive(
                    NotificationCenter.default.publisher(
                        for: Notification.Name("legado.statusBarHidden")
                    )
                ) { note in
                    statusBarHidden = note.userInfo?["hidden"] as? Bool ?? false
                }
                // legado:// / yuedu:// deep link (Info.plist CFBundleURLTypes 注册, 见 project.yml):
                // 转发给 shared framework 的 Kotlin 共享解析器 (commonMain LegadoDeepLinkHandler),
                // 对照 app 端 AssociationActivity 一键导入书源/替换规则/主题等
                .onOpenURL { url in
                    _ = LegadoDeepLinkIosKt.handleLegadoDeepLink(url: url.absoluteString)
                }
        }
    }
}
