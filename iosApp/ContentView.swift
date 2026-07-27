//
//  ContentView.swift
//  iosApp
//
//  legado KMP iOS UI 根: 用 UIViewControllerRepresentable 包装 shared 模块的
//  `MainViewController()` (Compose Multiplatform ComposeUIViewController)。
//
//  ## 调用链
//
//  ```
//  iOSApp (SwiftUI App)
//    └── ContentView (UIViewControllerRepresentable)
//          └── MainViewControllerKt.MainViewController() (Kotlin, shared framework)
//                └── ComposeUIViewController
//                      └── AppTheme { IosBookshelfScreen() }
//                            └── SharedBookshelfScreen (shared/sharedUiMain)
//  ```
//
//  ## Kotlin / Swift 互操作
//
//  - Kotlin `fun MainViewController(): UIViewController` 导出为 Swift `MainViewControllerKt.MainViewController()`
//  - Kotlin `platform.UIKit.UIViewController` 映射为 Swift `UIViewController`
//  - Compose Multiplatform `ComposeUIViewController` 即一个普通的 UIViewController,
//    内部用 Metal 渲染 Compose 树, 无需额外桥接
//

import SwiftUI
import shared  // Kotlin Multiplatform shared framework

/// UI 根: 用 `UIViewControllerRepresentable` 把 Kotlin 端 `MainViewController()` 包装为 SwiftUI 视图。
///
/// - `makeUIViewController`: 创建一次 UIViewController (调用 Kotlin 端 MainViewController)
/// - `updateUIViewController`: SwiftUI 更新时回调 (本场景无更新逻辑, 留空)
///
/// 用法: `ContentView()` 直接作为 SwiftUI 视图使用, 例如 `WindowGroup { ContentView() }`。
struct ContentView: UIViewControllerRepresentable {

    /// 创建 Compose UIViewController (调用 shared 模块导出的 MainViewController)。
    func makeUIViewController(context: Context) -> UIViewController {
        // MainViewControllerKt: Kotlin 顶层函数 MainViewController() 的 Swift 桥接类
        // (Kotlin 编译器自动生成 <FileName>Kt 类, 文件名为 MainViewController.kt)
        MainViewControllerKt.MainViewController()
    }

    /// SwiftUI 更新回调 (本场景 Compose 自管理状态, 无更新逻辑)。
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // No-op: Compose Multiplatform 内部自管理 UI 状态,
        // SwiftUI 侧无需在更新时干预 UIViewController
    }
}
