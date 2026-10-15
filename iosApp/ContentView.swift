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
//          └── LegadoRootViewController (本文件: 状态栏图标明暗的决定者)
//                └── MainViewControllerKt.MainViewController() (Kotlin, shared framework)
//                      └── ComposeUIViewController
//                            └── AppTheme { LegadoApp(navigator, screenModelStore) }
//                                  └── shared RouteContent 按 AppRoute 分发全部路由
//                                        (零薄壳: 平台薄壳 Composable 已删, 见 README.md)
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
import UIKit
import shared  // Kotlin Multiplatform shared framework

/// Compose 根容器: 唯一职责是持有状态栏图标明暗的决定权。
///
/// iOS 的状态栏底色应用无法设置 (系统栏区域恒为内容透出), 可控的只有图标明暗与显隐:
/// - 显隐: SwiftUI 根视图的 `.statusBarHidden` (见 iOSApp.swift)
/// - 明暗: 只能由视图控制器级 `preferredStatusBarStyle` 决定 (Apple TN3105); 而
///   `UIStatusBarStyle.default` 是按界面风格选的 (UIKit 头文件: "Automatically chooses
///   light or dark content based on the user interface style"), **不**采样状态栏下方的实际底色 ——
///   浅色界面 + 阅读页深色背景时图标会看不清, 必须由本应用显式指定。
///
/// SwiftUI 的宿主控制器把样式决定权交给子控制器 (UIHostingController override 了
/// `childForStatusBarStyle`), 而 Compose 的根控制器不提供该覆盖点, 故这里包一层:
/// 本容器不把决定权继续下传, 由 Kotlin 侧按阅读样式/深色覆盖层驱动。
final class LegadoRootViewController: UIViewController {

    /// 状态栏样式通道 (Kotlin → Swift): 与状态栏显隐同一套通知桥。
    /// Kotlin 常量: IosStatusBarStyleNotification = "legado.statusBarStyle",
    /// userInfo key "style" = Int (0=跟随界面风格 / 1=浅色图标 / 2=深色图标)
    private static let styleNotification = Notification.Name("legado.statusBarStyle")
    private static let styleKey = "style"

    private let content: UIViewController
    private var statusBarStyle: UIStatusBarStyle = .default

    init(content: UIViewController) {
        self.content = content
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("LegadoRootViewController 只支持代码创建")
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { statusBarStyle }

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(content)
        content.view.frame = view.bounds
        content.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(content.view)
        content.didMove(toParent: self)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applyStatusBarStyle(_:)),
            name: Self.styleNotification,
            object: nil
        )
    }

    @objc private func applyStatusBarStyle(_ note: Notification) {
        let raw = note.userInfo?[Self.styleKey] as? Int ?? 0
        let next: UIStatusBarStyle
        switch raw {
        case 1: next = .lightContent
        case 2: next = .darkContent
        default: next = .default
        }
        if next == statusBarStyle { return }
        statusBarStyle = next
        setNeedsStatusBarAppearanceUpdate()
    }
}

/// UI 根: 用 `UIViewControllerRepresentable` 把 Kotlin 端 `MainViewController()` 包装为 SwiftUI 视图。
///
/// - `makeUIViewController`: 创建一次 Compose 根容器 (内部承载 Kotlin 端 MainViewController)
/// - `updateUIViewController`: SwiftUI 更新时回调 (本场景无更新逻辑, 留空)
///
/// 用法: `ContentView()` 直接作为 SwiftUI 视图使用, 例如 `WindowGroup { ContentView() }`。
struct ContentView: UIViewControllerRepresentable {

    /// 创建 Compose 根容器 (调用 shared 模块导出的 MainViewController 作为其子控制器)。
    func makeUIViewController(context: Context) -> UIViewController {
        // MainViewControllerKt: Kotlin 顶层函数 MainViewController() 的 Swift 桥接类
        // (Kotlin 编译器自动生成 <FileName>Kt 类, 文件名为 MainViewController.kt)
        LegadoRootViewController(content: MainViewControllerKt.MainViewController())
    }

    /// SwiftUI 更新回调 (本场景 Compose 自管理状态, 无更新逻辑)。
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // No-op: Compose Multiplatform 内部自管理 UI 状态,
        // SwiftUI 侧无需在更新时干预 UIViewController
    }
}
