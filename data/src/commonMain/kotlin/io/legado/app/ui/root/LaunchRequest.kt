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
    data class SearchBook(
        /** 搜索词, 可空 (对照 master SearchActivity.receiptIntent: key 空则聚焦输入框) */
        val key: String? = null,
        /** 搜索范围 (格式 "名称::书源URL", 对齐 AppRoute.Search.searchScope) */
        val searchScope: String? = null,
        val submit: Boolean = false,
    ) : LaunchRequest

    @Serializable
    data class OpenBook(val bookUrl: String, val chapterIndex: Int? = null) : LaunchRequest

    @Serializable
    data class OpenBookInfo(val bookUrl: String) : LaunchRequest

    @Serializable
    data class OpenBookSource(val sourceUrl: String) : LaunchRequest

    /** 发现show 入口 (对照 master ExploreShowActivity: exploreUrl/exploreName/sourceUrl extra) */
    @Serializable
    data class ExploreShow(
        val sourceUrl: String,
        val exploreName: String? = null,
        val exploreUrl: String? = null,
    ) : LaunchRequest

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
    data class NavigateTo(
        val routeName: String,
        /** 可选携带的书籍地址: 供 audio_play 等路由在内存 book 缺失(冷启动)时回落 DB 解析 */
        val bookUrl: String? = null,
    ) : LaunchRequest

    /**
     * 进程内直投的完整路由: 载荷就在 [route] 里 (BookRef.Stored 是引用, 不拷贝不序列化),
     * 无需查库、无需全局侧信道, 也不存在"名字到了载荷被别人取走"的第二取件人。
     *
     * @param asRoot 冷启动直达语义: 该路由直接作为导航栈初始路由, 书架不进栈
     * (对照 master 各页独立 Activity 直开, back 即退回调用方)。只在 UI 首次组合期
     * 取件时有意义 (见 Android MainActivity.Content), 之后消费一律 push。
     */
    @Serializable
    data class OpenRoute(
        val route: AppRoute,
        val asRoot: Boolean = false,
    ) : LaunchRequest

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

    /**
     * 组合期同步取件 (队列为空返回 null)。与 [requests] 是同一个 Channel, 元素只交付一次 ——
     * "谁来取"因此只是实现细节, 不可能出现两个消费点各自取到一半。
     * UI 首帧需要把 [LaunchRequest.OpenRoute] 当初始路由时用它, 避免"先渲染书架再滑入"的闪帧。
     */
    fun tryReceive(): LaunchRequest? = channel.tryReceive().getOrNull()
}
