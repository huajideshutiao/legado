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
                    └── AppTheme { IosBookshelfScreen() }
                          └── SharedBookshelfScreen (shared/sharedUiMain)
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

- App: `io.legado.app.ios`
- 共享 framework: `io.legado.shared` (shared 模块 namespace)

## 签名

`project.yml` 默认 `CODE_SIGN_STYLE: Automatic`, `DEVELOPMENT_TEAM: ""` (空)。

- 本地调试: 用 Personal Team (Apple ID 免费账号即可, 7 天签名)
- 上架 App Store: 在 Xcode Signing & Capabilities 中填 Apple Developer 团队 ID

## KP3 接入状态 (2026-07-22)

iOS 端已接入 (详见 `shared/src/iosMain/`):

| 模块 | 实现 | 状态 |
|------|------|------|
| UI 入口 | `MainViewController.kt` (ComposeUIViewController + IosBookshelfScreen) | ✅ 已接入 |
| 书架 | `IosBookshelfScreen.kt` (包装 shared/sharedUiMain BookshelfScreen) | ✅ 已接入 |
| 封面加载 | `IosBookCover.kt` (UIImage + Skia ImageBitmap 桥接) | ✅ 已接入 |
| JS 引擎 | `IosJsEngine.kt` (JavaScriptCore, 替代 quickjs JNI) | ✅ 已接入 |
| HTTP 层 | `IosHttpProvider.kt` (Ktor CIO 包装 KmpHttpClient) | ✅ 已接入 |
| 数据库 | `IosDatabaseDriver.kt` (Room KMP + BundledSQLiteDriver) | ✅ 已接入 |
| TTS | `IosSystemTtsEngine.kt` (AVSpeechSynthesizer) | ✅ 已接入 |
| 数据访问 | `IosAppDbAccessor.kt` / `IosBookHelpAccessor.kt` / `IosSourceHelpAccessor.kt` | ✅ 已接入 |
| Provider 注册 | `IosProviderRegistry.kt` (9 步顺序, 与 desktop Main.kt 对齐) | ✅ 已接入 |

## KP4 接入状态 (2026-07-24)

iOS 端阅读流子路由已接入 (对照 desktop `DesktopApp.kt` 的 `when(currentRoute)` 路由模式,
由 `MainViewController.kt` 调用 `IosNavHost()` 替代原直接调 `IosBookshelfScreen()`):

| 子路由 | 实现 | 包装的 shared/sharedUiMain Composable | 状态 |
|------|------|------|------|
| 路由宿主 | `ui/IosNavHost.kt` (5 路由枚举 BOOKSHELF/READER/SEARCH/BOOK_INFO/BOOK_SOURCE) | - | ✅ 已接入 |
| 阅读页 (READER) | `ui/reader/IosReaderScreen.kt` | `ReadViewComposable` | ✅ 已接入 (最小可读, 无菜单/目录/TTS) |
| 搜索页 (SEARCH) | `ui/search/IosSearchScreen.kt` | `SearchScreen` | ✅ 已接入 (含 SearchScopeDialog/AppLogDialog/清空历史确认) |
| 详情页 (BOOK_INFO) | `ui/bookinfo/IosBookInfoScreen.kt` | `BookInfoScreen` | ✅ 已接入 (含 SourceLoginDialog/GroupManageDialog/VariableDialog/AppLogDialog) |
| 书源管理 (BOOK_SOURCE) | `ui/booksource/IosBookSourceScreen.kt` | `BookSourceListScreen` | ✅ 已接入 (核心数据操作, 校验/导入/编辑/调试 留 TODO) |

跳转关系 (与 desktop 一致):
- 书架点击书籍 → READER (携带 Book)
- 书架长按书籍 → BOOK_INFO (携带 BaseBook)
- 书架顶栏搜索按钮 → SEARCH
- 书架溢出菜单"书源管理" → BOOK_SOURCE
- 搜索结果点击书籍 → BOOK_INFO (携带 BaseBook)
- 详情页"开始阅读" → READER (携带 Book)
- 书源管理单项菜单"搜索书籍" → SEARCH
- 各子路由 onBack → BOOKSHELF (READER/SEARCH/BOOK_INFO/BOOK_SOURCE 默认回书架)

## KP4 provider 真实化 (2026-07-24)

4 个 stub provider 已真实化 (详见 `shared/src/iosMain/`):

| 模块 | 实现 | 状态 |
|------|------|------|
| 图片操作 | `IosImageOps.kt` (UIImage + UIGraphics 真实像素操作: decode/encode/split/stitch/crop/size) | ✅ 已接入 |
| Toast | `Toaster.ios.kt` (dispatch_async 主线程 + UIAlertController present, NSLog 兜底) | ✅ 已接入 |
| 进度通知 | `NotificationProgress.ios.kt` (UNUserNotificationCenter 本地通知 + 权限请求, NSLog 兜底) | ✅ 已接入 |
| 服务调度 | `ServiceLauncher.ios.kt` (kotlinx.coroutines 协程 + CacheBookShared; UpdateBook 待 commonMain 下沉) | ✅ 已接入 (UpdateBook 部分 stub) |

> 注: 上述 4 项 iOS target 代码在 Windows 上无法编译验证, 真实编译验证必须在 macOS 上进行
> (`./gradlew :shared:compileKotlinIosArm64`)。UIAlertController/UNNotificationRequest 工厂方法
> 与 NS_OPTIONS 位运算等少数 ObjC 桥接细节如遇编译报错, 按文件内 TODO 注释微调即可。

## 后续 KP5 待接入

- ReadMenuOverlay (阅读菜单层: 顶栏/底栏/进度条/子菜单, 依赖 ReadMenuState iOS actual 实现)
- TocDrawerContent (阅读页目录侧栏)
- TtsControlPanel (阅读页 TTS 控制面板)
- ChangeCoverDialog (详情页换封面, 依赖 ChangeCoverPlatform iOS actual 实现)
- 书源校验 (BookSourceChecker, 依赖 NotificationProgresses iOS actual)
- 书源编辑/调试/登录子路由 (BOOK_SOURCE_EDIT/BOOK_SOURCE_DEBUG)
- 目录/换源子路由 (TOC/CHANGE_SOURCE)
- 音频/漫画/视频/RSS 阅读路由 (AUDIO_PLAYER/MANGA_READER/VIDEO_PLAYER/RSS_*)
