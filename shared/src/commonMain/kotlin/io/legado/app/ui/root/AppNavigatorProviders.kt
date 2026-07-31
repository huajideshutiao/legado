package io.legado.app.ui.root

import kotlin.concurrent.Volatile

/**
 * AppNavigator 全局访问点 (供非 Composable 代码取 navigator)。
 * 各端入口在 LegadoApp 组合时注册。
 */
object AppNavigatorProviders {
    @Volatile
    private var impl: AppNavigator? = null

    fun register(navigator: AppNavigator) {
        impl = navigator
    }

    fun get(): AppNavigator = impl
        ?: error("AppNavigator must be registered by the system entry")

    fun getOrNull(): AppNavigator? = impl
}
