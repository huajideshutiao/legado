package io.legado.app.help

/**
 * app 端 HomeTabHelp 别名, 转发到 commonMain 下沉的 [HomeTabHelpShared]。
 *
 * 原 object 实现已下沉到 shared/commonMain (HomeTabHelpShared.kt), 改用
 * [io.legado.app.ui.compose.platform.PreferenceStoreProvider] 抽象 SP 访问,
 * 消除对 splitties.appCtx / Context 扩展的直接依赖。app 端在 App.onCreate
 * 注入 AndroidPreferenceStoreProvider (包装 defaultSharedPreferences) 后,
 * 行为与下沉前完全一致。
 *
 * 调用方 (HomeViewModel / HomeTabManageDialog / HomeTabEditDialog /
 * HomeSectionManageDialog / HomeSectionEditDialog / HomeTab) 继续使用
 * `HomeTabHelp.xxx()`, 无需任何改动。
 */
typealias HomeTabHelp = HomeTabHelpShared
