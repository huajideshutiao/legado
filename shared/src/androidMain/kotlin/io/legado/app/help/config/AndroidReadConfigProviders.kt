package io.legado.app.help.config

import io.legado.app.ui.compose.platform.PreferenceStoreProvider

/**
 * Android 端 [ReadConfigProviders] 实现（P0-2/3）。
 *
 * 与 [io.legado.app.help.config.DesktopReadConfigProviders] 对称:
 * 接收外部传入的 [prefs] (app 端传 [io.legado.app.ui.compose.platform.AndroidPreferenceStoreProvider],
 * 包装 `appCtx` 的 `defaultSharedPreferences`), 内部构造 [ReadBookConfigShared] 与 [ReadTipConfigShared]。
 *
 * # 设计权衡 (P0 阶段)
 * - app 端 `ReadBookConfig` 单例将纯样式字段 (textSize/padding/...) 序列化到 `readConfig.json`,
 *   仅 `autoReadSpeed` / `styleSelect` / `shareLayout` 等少量字段写 prefs;
 *   [ReadBookConfigShared] 走 prefs key 直读直写, 二者存储后端不同。
 * - P0 不做存储后端统一 (避免改动 app 端阅读行为), 仅注入 CompositionLocal 让 shared UI 可运行。
 *   shared UI 读到的纯样式字段可能为默认值, 后续 P1 再做真正双向同步。
 * - app 端 `AndroidPreferenceStoreProvider` 位于 app 模块 (依赖 splitties appCtx),
 *   本类放在 shared/androidMain 仅承接 shared 内的工厂逻辑, 不反向依赖 app 模块。
 *
 * 调用方在 app Compose 入口注入:
 * ```kotlin
 * val readConfigProviders = remember { AndroidReadConfigProviders(AndroidPreferenceStoreProvider()) }
 * CompositionLocalProvider(LocalReadConfigProviders provides readConfigProviders) {
 *     Content()
 * }
 * ```
 *
 * 模式参考 [DesktopReadConfigProviders]。
 */
class AndroidReadConfigProviders(
    prefs: PreferenceStoreProvider,
) : ReadConfigProviders {

    override val readBookConfig: ReadBookConfigShared = ReadBookConfigShared(prefs)

    override val readTipConfig: ReadTipConfigShared =
        ReadTipConfigShared(readBookConfig)
}
