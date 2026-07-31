package io.legado.app.help.config

import io.legado.app.ui.compose.platform.PreferenceStoreProvider

/**
 * Android 端 [ReadConfigProviders] 实现（P0-2/3）。
 *
 * 与 [io.legado.app.help.config.DesktopReadConfigProviders] 对称:
 * 接收外部传入的 [prefs] (app 端传 [io.legado.app.ui.compose.platform.AndroidPreferenceStoreProvider],
 * 包装 `appCtx` 的 `defaultSharedPreferences`), 内部构造 [ReadBookConfigShared] 与 [ReadTipConfigShared]。
 *
 * # 存储后端
 * app 端 `ReadBookConfig` 已收敛为薄壳, 全部转发到本类构造的 [ReadBookConfigShared],
 * 因此 app 端与 shared UI 读写的是同一份 `readConfig.json` / 同一套 prefs, 不再有双份状态。
 * app 在 `App.onCreate` 用 [io.legado.app.help.config.ReadBookConfigProviders] 注册本实例。
 *
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
