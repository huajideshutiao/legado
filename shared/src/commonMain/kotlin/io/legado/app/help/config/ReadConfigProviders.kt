package io.legado.app.help.config

import io.legado.app.ui.compose.platform.PreferenceStoreProvider

/**
 * 阅读配置 Provider 容器接口（KMP 共用）。
 *
 * 包装 [ReadBookConfigShared] 和 [ReadTipConfigShared] 两个实例，供 Compose UI
 * 通过 [LocalReadConfigProviders] 一次性注入。各平台 actual 实现负责构造：
 * - Android: 后续 app 端迁移到 Shared 版时，由 `App.onCreate` 用
 *   `AndroidPreferenceStoreProvider` 包装 defaultSharedPreferences 构造。
 * - 桌面 jvm: [io.legado.app.help.config.DesktopReadConfigProviders] 用
 *   `DesktopPreferenceStoreProvider` 构造。
 * - iOS/鸿蒙: stub 实现（后续 KP3/KP4 替换）。
 *
 * 模式参考 `AppConfigProviders` / `ThemeStoreProvider` / `PreferenceStoreProvider`，
 * 用 interface 而非 expect/actual，避免 shared androidMain 反向依赖 app 模块。
 *
 * KP5: [LocalReadConfigProviders] (Compose 依赖) 已拆分到 sharedUiMain 的 ReadConfigProvidersUi.kt,
 * 本文件保留 interface 定义和工厂函数 (工厂依赖的 PreferenceStoreProvider 是纯接口, 在 commonMain),
 * 让 ohos/linuxArm64 不依赖 Compose 也能编译。
 */
interface ReadConfigProviders {
    /** 阅读界面配置（字体/间距/边距/翻页动画等）。 */
    val readBookConfig: ReadBookConfigShared

    /** tip 显示配置（页眉页脚 6 槽位 + 模式 + 颜色）。 */
    val readTipConfig: ReadTipConfigShared
}

/**
 * 便捷工厂：用 [prefs] 构造一个最小化的 [ReadConfigProviders] 实现，
 * 供各平台 actual 复用（避免重复样板代码）。
 *
 * 调用方可以在此基础上包装成自己平台特有的 Provider（如桌面端附加状态管理）。
 */
fun ReadConfigProviders(prefs: PreferenceStoreProvider): ReadConfigProviders =
    object : ReadConfigProviders {
        override val readBookConfig: ReadBookConfigShared = ReadBookConfigShared(prefs)
        override val readTipConfig: ReadTipConfigShared =
            ReadTipConfigShared(readBookConfig)
    }
