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
