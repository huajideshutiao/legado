package io.legado.app.help.config

/**
 * 阅读配置 Provider 容器接口（KMP 共用）。
 *
 * 包装 [ReadBookConfigShared] 和 [ReadTipConfigShared] 两个实例，供 Compose UI
 * 通过 [LocalReadConfigProviders] 一次性注入。
 *
 * 实例由 [ReadConfigProviders] 工厂从 [ReadBookConfigProviders] 的全局注册实例派生，
 * 各端宿主在启动早期注册一次（Android `App.onCreate` / 桌面 `registerDesktopConfig` /
 * `registerIosProviders` / `registerOhosProviders`）。
 *
 * 模式参考 `AppConfigProviders` / `ThemeStoreProvider`，
 * 用 interface 而非 expect/actual，避免 shared androidMain 反向依赖 app 模块。
 *
 * KP5: [LocalReadConfigProviders] (Compose 依赖) 已拆分到 sharedUiMain 的 ReadConfigProvidersUi.kt,
 * 本文件保留 interface 定义和工厂函数 (纯接口无 Compose 依赖),
 * 让 ohos/linuxArm64 不依赖 Compose 也能编译。
 */
interface ReadConfigProviders {
    /** 阅读界面配置（字体/间距/边距/翻页动画等）。 */
    val readBookConfig: ReadBookConfigShared

    /** tip 显示配置（页眉页脚 6 槽位 + 模式 + 颜色）。 */
    val readTipConfig: ReadTipConfigShared
}

/**
 * 便捷工厂：包装 [ReadBookConfigProviders] 已注册的 [ReadBookConfigShared] 实例。
 *
 * 阅读页的排版与绘制读本注入实例，而阅读设置弹窗（界面 / 版面 / 背景文字）读写全局
 * 注册实例；工厂若自建实例，两者分家会让字号 / 字距 / 行距 / 段距 / 边距等设置只落盘，
 * 阅读页当场与重新进入都不生效，冷启动才从 readConfig.json 读回。故此处只包装不新建。
 *
 * 须在宿主注册 [ReadBookConfigShared]（`App.onCreate` / `registerDesktopConfig` /
 * `registerIosProviders` / `registerOhosProviders`）之后调用，未注册即 error 早失败。
 */
fun ReadConfigProviders(): ReadConfigProviders {
    val readBookConfig = ReadBookConfigProviders.get()
    return object : ReadConfigProviders {
        override val readBookConfig: ReadBookConfigShared = readBookConfig
        override val readTipConfig: ReadTipConfigShared = ReadTipConfigShared(readBookConfig)
    }
}
