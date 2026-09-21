package io.legado.app.ui.root

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * AppNavigator 全局访问点 (供非 Composable 代码取 navigator)。
 * 各端入口在 LegadoApp 组合时注册。
 */
object AppNavigatorProviders {
    private val _navigator = MutableStateFlow<AppNavigator?>(null)

    val navigatorFlow: StateFlow<AppNavigator?> = _navigator.asStateFlow()

    fun register(navigator: AppNavigator) {
        _navigator.value = navigator
    }

    fun get(): AppNavigator = _navigator.value
        ?: error("AppNavigator must be registered by the system entry")

    fun getOrNull(): AppNavigator? = _navigator.value

    /**
     * 挂起等待系统入口完成 navigator 注册。
     * 适用于启动阶段与首帧组合并发执行的异步任务 (如启动静默检查更新)。
     */
    suspend fun awaitNavigator(): AppNavigator =
        _navigator.filterNotNull().first()
}
