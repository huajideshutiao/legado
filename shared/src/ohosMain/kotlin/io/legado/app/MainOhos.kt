package io.legado.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.legado.app.help.config.registerOhosProviders
import io.legado.app.ui.AppBackground
import io.legado.app.ui.OhosNavHost

/**
 * 鸿蒙端 Compose 入口 (对标 iOS `MainViewController` / desktop `Main.kt`)。
 *
 * 由 EntryAbility 通过 napi 桥接调用, 顶层提供 Surface 容器 + [OhosNavHost] 路由。
 *
 * # 启动序列
 *
 * 1. [registerOhosProviders] 注册 commonMain 业务 provider (AppFilesDir / Preference /
 *    Database / BookStorage / JsEngine / Tts / WebServer 等, 详见
 *    [io.legado.app.help.config.OhosProviderRegistry] 注册顺序约束)
 * 2. [Surface] 容器铺满全屏, 背景色 [AppBackground] (ohosMain 不继承 sharedUiMain,
 *    无 AppTheme 可用, 复用 OhosIndexScreen 内部 Arco 色板)
 * 3. [OhosNavHost] 用 when(currentRoute) 切换全部 34 个子路由
 *
 * # 与 iOS / desktop 入口的差异
 *
 * - iOS `MainViewController` 用 `ComposeUIViewController` 包装, 注入 4 个 CompositionLocal
 *   Provider (ThemeStore / AppConfig / EventBus / PreferenceStore); 鸿蒙端 OhosXxxScreen
 *   不走 CompositionLocal 模式, 直接调 `AppDbProviders.get()` 等单例 provider, 故无需注入
 * - desktop `Main.kt` 在 `application { }` 顶层注册 provider; 鸿蒙端 provider 注册由
 *   [registerOhosProviders] 统一入口完成, 此处仅触发一次
 * - ohosMain 不继承 sharedUiMain (Compose UI 隔离), 无 `AppTheme` 包装, 主题色由各
 *   OhosXxxScreen 内部 Arco 色板 (ArcoBlue6 / ArcoGray3 / AppBackground 等) 自治
 */
@Composable
fun MainOhos() {
    // provider 注册 (首次组合时执行一次, 幂等; 对照 iOS MainViewController 中 registerIosProviders())
    remember { registerOhosProviders() }

    Surface(modifier = Modifier.fillMaxSize(), color = AppBackground) {
        OhosNavHost()
    }
}
