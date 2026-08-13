package io.legado.app.ui.root

import io.legado.app.constant.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/** 共享页面状态持有者；不允许持有 Activity、View 或平台控制器。 */
interface ScreenModel {
    fun onCleared() = Unit

    /**
     * 将被移除前的通知（导航动画开始前触发，见 [ScreenModelStore.notifyPreRemoved]）。
     *
     * 与 [onCleared] 的区别：本方法在页面仍留在组合中（返回动画播放期间）即被调用，
     * 用于把「退出即保存」的耗时操作（如阅读页落库）提前到动画窗口内执行——
     * 对照原版返回键按下即 onPause → saveRead 落库的即时性，避免落库等到动画播完后
     * retain → onCleared 才发生。默认空实现；需要提前保存的 ScreenModel 覆写。
     */
    fun onPreRemoved() = Unit
}

/**
 * 自管 scope 的统一异常兜底，对应原版 `BaseViewModel.execute{}` (= `Coroutine`) 的
 * `catch (e: Throwable)`：任何异常都记日志而不外泄。
 *
 * SupervisorJob 的直接子协程是 root coroutine，异常不上传而直接进
 * `Thread.getDefaultUncaughtExceptionHandler` → CrashHandler 转交系统默认 handler → 杀进程。
 *
 * @param name 出错日志前缀，通常为页面名
 * @param onError 额外的收尾动作（如复位 loading），异常同样被吞掉不外泄
 */
fun screenModelExceptionHandler(
    name: String,
    onError: ((Throwable) -> Unit)? = null,
): CoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
    // 正常取消不算错误（root coroutine 的 CancellationException 本不会到这里，防御性放行）
    if (e is CancellationException) return@CoroutineExceptionHandler
    AppLog.put("$name 出错\n${e.message}", e)
    onError?.let { cb -> runCatching { cb(e) } }
}

/**
 * ScreenModel / 共享 VM 的自管 scope 统一入口，见 [screenModelExceptionHandler]。
 *
 * 各处一律走本函数创建，不要再手写 `CoroutineScope(SupervisorJob() + xxx)`。
 */
fun screenModelScope(
    name: String,
    context: CoroutineContext = Dispatchers.Default,
    onError: ((Throwable) -> Unit)? = null,
): CoroutineScope = CoroutineScope(
    SupervisorJob() + context + screenModelExceptionHandler(name, onError)
)

object EmptyScreenModel : ScreenModel

/**
 * ScreenModel 生命周期与 RouteEntryId 绑定，而不是由各平台页面重复 remember/ViewModelProvider。
 *
 * 每个 [RouteContent] 分支按路由类型自行决定 factory, 不再依赖构造期单一 factory。
 */
class ScreenModelStore {
    private val models = mutableMapOf<RouteEntryId, ScreenModel>()

    fun getOrCreate(entry: RouteEntry, factory: () -> ScreenModel): ScreenModel =
        models.getOrPut(entry.id) { factory() }

    inline fun <reified T : ScreenModel> getOrCreateTyped(
        entry: RouteEntry,
        crossinline factory: () -> T,
    ): T = getOrCreate(entry) { factory() } as T

    fun retain(entries: List<RouteEntry>) {
        val activeIds = entries.mapTo(mutableSetOf()) { it.id }
        val removed = models.keys.filterNot { it in activeIds }
        removed.forEach { id -> models.remove(id)?.let(::clearSafely) }
    }

    /**
     * 通知即将被 [retain] 移除的 ScreenModel 执行预清理（默认空，见 [ScreenModel.onPreRemoved]）。
     *
     * 由 LegadoApp 在导航动画开始前调用，把落库等耗时操作提前到动画窗口；
     * 不删除 model（页面仍在组合中播返回动画，提前 retain 会让页面重建 ViewModel 重载数据），
     * 动画结束后的 [retain] 仍会正常触发 [ScreenModel.onCleared]（幂等落库 + 仅一次的上传）。
     * 异常隔离与 [clearSafely] 一致，不影响 Recomposer。
     */
    fun notifyPreRemoved(entries: List<RouteEntry>) {
        val activeIds = entries.mapTo(mutableSetOf()) { it.id }
        models.forEach { (id, model) ->
            if (id !in activeIds) {
                runCatching { model.onPreRemoved() }
                    .onFailure { AppLog.put("ScreenModel.onPreRemoved 异常: ${model::class.simpleName}", it) }
            }
        }
    }

    fun clear() {
        models.values.forEach(::clearSafely)
        models.clear()
    }

    /**
     * retain/clear 由 LegadoApp 的 LaunchedEffect 调用, onCleared 抛出会连坐整个 Recomposer
     * (桌面端表现为窗口能重绘但键鼠全失灵), 故逐个隔离并记下是哪个 ScreenModel。
     */
    private fun clearSafely(model: ScreenModel) {
        runCatching { model.onCleared() }
            .onFailure { AppLog.put("ScreenModel.onCleared 异常: ${model::class.simpleName}", it) }
    }

    val size: Int get() = models.size
}
