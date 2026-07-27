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

/// SwiftUI App 入口 (iOS 14+ 生命周期)。
///
/// 用 `@main` 声明为 App 启动点, SwiftUI 框架自动调用 `body` 渲染根视图。
/// 根视图为 `ContentView` (用 `UIViewControllerRepresentable` 包装 Kotlin 端 `MainViewController`)。
@main
struct iOSApp: App {
    /// SwiftUI App 入口, 根视图为 ContentView。
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
