package io.legado.app.ui.root

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable

/**
 * 统一外部请求入口抽象 (deep link / Intent extras / JS UI 事件 / 跨 Activity 大对象)。
 */
@Serializable
sealed interface LaunchRequest {

    @Serializable
    data class DeepLink(val url: String) : LaunchRequest

    @Serializable
    data class SearchBook(val key: String, val submit: Boolean = false) : LaunchRequest

    @Serializable
    data class OpenBook(val bookUrl: String, val chapterIndex: Int? = null) : LaunchRequest

    @Serializable
    data class OpenBookInfo(val bookUrl: String) : LaunchRequest

    @Serializable
    data class OpenBookSource(val sourceUrl: String) : LaunchRequest

    @Serializable
    data class OpenReader(
        val bookUrl: String,
        val chapterIndex: Int? = null,
        val chapterPos: Int? = null,
    ) : LaunchRequest

    @Serializable
    data class ProcessText(val text: String) : LaunchRequest

    @Serializable
    data class ImportFile(val filePath: String) : LaunchRequest

    /** JS 触发 UI 事件 (映射 SourceUiRequest, 避免直接引用非 serializable sealed class) */
    @Serializable
    data class SourceUi(
        val sourceUrl: String,
        val type: SourceUiType,
        val url: String? = null,
    ) : LaunchRequest

    /** 通知/外部入口投递的路由跳转请求 (routeName 对应 AppRoute 子类型别名) */
    @Serializable
    data class NavigateTo(val routeName: String) : LaunchRequest

    /** SourceUiRequest 三种子类型的可序列化映射 */
    @Serializable
    enum class SourceUiType { LOGIN, SOURCE_VARIABLE, VERIFICATION_CODE }
}

/**
 * 外部请求事件总线 (各端投递侧 → 共享 UI 消费侧)。
 * 无限队列保留订阅前请求，并按投递顺序交给唯一应用根消费者。
 */
object LaunchRequestBus {
    private val channel = Channel<LaunchRequest>(Channel.UNLIMITED)
    val requests: Flow<LaunchRequest> = channel.receiveAsFlow()

    fun dispatch(request: LaunchRequest) {
        check(channel.trySend(request).isSuccess) { "LaunchRequest queue is closed" }
    }
}
