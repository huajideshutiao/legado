# iosApp - legado KMP iOS 宿主工程

legado iOS 端的 Xcode 工程骨架, 用 SwiftUI App 生命周期 (`@main`) + `UIViewControllerRepresentable`
包装 shared 模块的 `MainViewController()` (Compose Multiplatform `ComposeUIViewController`)。

## 工程结构

```
iosApp/
├── iOSApp.swift       # SwiftUI App 入口 (@main), WindowGroup { ContentView() }
├── ContentView.swift  # UIViewControllerRepresentable 包装 MainViewControllerKt.MainViewController()
├── Info.plist         # iOS App 配置 (屏幕方向/ATS/后台模式/Bundle ID)
├── project.yml        # XcodeGen 配置 (生成 .xcodeproj, 避免 git 二进制冲突)
└── README.md          # 本文件 (macOS 构建说明)
```

## 调用链

```
iOSApp (SwiftUI App)
  └── ContentView (UIViewControllerRepresentable)
        └── MainViewControllerKt.MainViewController()  [shared framework, Kotlin]
              └── ComposeUIViewController
                    └── AppTheme { LegadoApp(navigator, screenModelStore) }
                          └── shared RouteContent 统一分发 53 路由
```

## macOS 构建步骤 (Windows 无法编译 iOS target)

### 1. 环境要求

- macOS 12.0+ (Monterey 或更新)
- Xcode 14.0+ (App Store 安装)
- JDK 17 (项目根目录 `gradlew` 已配置)
- Kotlin Multiplatform iOS 工具链 (Kotlin 2.x 自带, 无需额外安装)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) (可选, 用于从 project.yml 生成 .xcodeproj)

### 2. 安装 XcodeGen (可选)

```bash
brew install xcodegen
```

### 3. 生成 Xcode 工程

```bash
cd iosApp
xcodegen generate
# 生成 iosApp.xcodeproj
```

> 如果不装 XcodeGen, 也可手动用 Xcode 创建工程 (File > New > Project > iOS App),
> 然后手动添加 iOSApp.swift / ContentView.swift / Info.plist, 配置 framework 搜索路径。

### 4. shared framework 直接集成

`project.yml` 使用 Kotlin 官方 `embedAndSignAppleFrameworkForXcode` 任务。Xcode 每次构建会根据
当前 `SDK_NAME`、`ARCHS` 和 Debug/Release 自动选择正确的 Kotlin target，复制 framework 到
`TARGET_BUILD_DIR` 并完成签名，不再依赖硬编码的 `shared/build/bin/...` 路径。

直接在 Xcode 构建即可；如由 Android Studio/IntelliJ 的 iOS 运行配置发起，脚本会通过
`OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED` 避免重复调用 Gradle。

### 5. 打开 Xcode 运行

```bash
open iosApp/iosApp.xcodeproj
# 在 Xcode 中选择模拟器或真机, 点 Run (⌘R)
```

## Windows 开发说明

Windows 上**无法**编译 iOS target (需要 macOS + Xcode + Kotlin/Native iOS 工具链)。

Windows 开发者做 jvm/android/desktop 时, 建议关闭 iOS target 加速 gradle 配置:

```powershell
.\gradlew :app:assembleGithubDebug -PenableIosTarget=false
```

iOS 端代码改动 (iosMain/) 在 Windows 上无法编译验证, 但 IDE (Android Studio / IDEA)
仍可做语法检查 / 跳转 / 自动补全。**真实编译验证必须在 macOS 上进行**。

## Bundle ID

- App: `shutiao.reader`（Debug 构建 `shutiao.reader.debug`，Release 构建 `shutiao.reader.release`，与安卓
  applicationId 对齐）
- 共享 framework: `io.legado.shared` (shared 模块 namespace)

## 签名

`project.yml` 默认 `CODE_SIGN_STYLE: Automatic`, `DEVELOPMENT_TEAM: ""` (空)。

- 本地调试: 用 Personal Team (Apple ID 免费账号即可, 7 天签名)
- 上架 App Store: 在 Xcode Signing & Capabilities 中填 Apple Developer 团队 ID

## iOS 端零薄壳架构

iOS 端零薄壳架构: `MainViewController.kt` 直接调用 shared `LegadoApp`, 53 路由由 shared
`RouteContent` 统一分发, 不再维护 `IosNavHost` / `IosBookshelfScreen` / `IosReaderScreen` /
`IosSearchScreen` / `IosBookInfoScreen` / `IosBookSourceScreen` 等平台薄壳 Composable。

### 内部调用关系 (shared/src/iosMain/.../MainViewController.kt)

```
ComposeUIViewController
  └── AppTheme
        ├── 顶部 48dp 着色条 (themeStoreProvider.accentColor)
        ├── Box (ESC/BackSpace 触发 navigator.pop())
        │     └── LegadoApp(navigator, screenModelStore)  [shared]
        │           └── RouteContent  [shared]  按 AppRoute 类型分发 53 路由
        ├── SourceUiEventBridgeHost()  (订阅 SOURCE_UI_REQUEST, 承接 JS 弹窗)
        └── DeepLinkImportHost()  (legado:// deep link 导入)
```

### iOS 平台能力 actual 实现 (shared/src/iosMain/)

| 模块                  | 实现                                                                                                            |
|---------------------|---------------------------------------------------------------------------------------------------------------|
| UI 入口               | `MainViewController.kt` (ComposeUIViewController + shared LegadoApp)                                          |
| 平台能力聚合              | `IosPlatformCapabilities.kt` + `IosProviderRegistry.kt` (与 desktop Main.kt 对齐)                                |
| Compose 平台 Provider | `IosProviders.kt` (ThemeStore / AppConfig / EventBus / PreferenceStore 4 个 Provider)                          |
| 数据库                 | `IosDatabaseDriver.kt` (Room KMP + BundledSQLiteDriver) + `IosAppDbAccessor.kt`                               |
| JS 引擎               | `RegisterNativeJsEngines.native.kt` (nativeMain, quickjs cinterop, iOS/鸿蒙共用)                                  |
| HTTP 层              | `IosHttpProvider.kt` (Ktor CIO 包装 KmpHttpClient)                                                              |
| TTS                 | `IosSystemTtsEngine.kt` (AVSpeechSynthesizer)                                                                 |
| 图片加载                | `IosBitmapProvider.kt` + `BookImageLoader.ios.kt` + `ImageBitmapLoader.ios.kt`                                |
| 图片操作                | `IosImageOps.kt` (UIImage + UIGraphics 真实像素操作: decode/encode/split/stitch/crop/size)                          |
| Toast               | `Toaster.ios.kt` (dispatch_async 主线程 + UIAlertController present, NSLog 兜底)                                   |
| 进度通知                | `NotificationProgress.ios.kt` (UNUserNotificationCenter 本地通知 + 权限请求, NSLog 兜底)                                |
| Crypto              | `NativeSignOps.ios.kt` / `NativeKryptoOps.ios.kt` / `NativeAsymmetricCryptoOps.ios.kt` / `IosCryptoNative.kt` |
| 其他                  | `IosFilePicker.ios.kt` / `IosImagePicker.ios.kt` / `IosOpenUrlProvider.kt` / `NativeUserAgentProvider.kt` 等   |

> 注: 上述 iOS target 代码在 Windows 上无法编译验证, 真实编译验证必须在 macOS 上进行
> (`./gradlew :shared:compileKotlinIosArm64`)。UIAlertController/UNNotificationRequest 工厂方法
> 与 NS_OPTIONS 位运算等少数 ObjC 桥接细节如遇编译报错, 按文件内 TODO 注释微调即可。
