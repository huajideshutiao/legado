package io.legado.app.help.config

import kotlin.concurrent.Volatile

/**
 * [ReadBookConfigShared] 全局注册器 (模式参考 [ThemeConfigProviders])。
 *
 * # 背景
 * [ReadBookConfigShared] 在桌面端通过 [ReadConfigProviders] + Compose
 * [io.legado.app.ui.compose.platform.LocalReadConfigProviders] 注入到 UI 层,
 * 但非 UI 代码 (如 [io.legado.app.help.storage.BackupShared]) 需要全局访问
 * readBookConfig.configList / shareConfig 用于备份, 不能依赖 CompositionLocal。
 *
 * 本注册器提供一个全局访问点, 宿主启动早期 (App.onCreate / desktop Main.kt)
 * 经 [register] 注册一次, shared 内通过 [get] 获取。
 *
 * # 注册时机
 * - app 端: App.onCreate 中, 在 ReadBookConfig 初始化之后注册
 * - 桌面端: Main.kt 中, 构造 DesktopReadConfigProviders 之后注册
 *
 * 模式参考 [ThemeConfigProviders] / [io.legado.app.data.AppDatabaseProviders]。
 */
object ReadBookConfigProviders {

    @Volatile
    private var impl: ReadBookConfigShared? = null

    /**
     * 宿主启动早期注册一次 (App.onCreate / desktop Main.kt 内, 任何备份之前)。
     *
     * @param impl 已构造好的 [ReadBookConfigShared] 实例 (通常从
     *   [ReadConfigProviders.readBookConfig] 取得)
     */
    fun register(impl: ReadBookConfigShared) {
        this.impl = impl
    }

    /**
     * 获取已注册实例, 未注册抛出 [IllegalStateException]。
     *
     * 调用方 (如 [io.legado.app.help.storage.BackupShared]) 应在 runCatching 中
     * 调用本方法, 未注册时跳过相关备份项 (与 app 端 DirectLinkUpload.getConfig()
     * 为 null 时跳过 directLinkUploadRule.json 的行为一致)。
     */
    fun get(): ReadBookConfigShared =
        impl ?: error("ReadBookConfigProviders not registered")
}
