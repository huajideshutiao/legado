package io.legado.app.ui.root

import io.legado.app.utils.isAbsUrl
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
        /**
         * 搜索词, 可空 (对照 master SearchActivity.receiptIntent: key 空则聚焦输入框)。
         * 整段就是一条 http(s) 地址时不当搜索词用, 与分享文本走同一条自动识别导入分流
         * (判据见 [shareTextTarget])。
         */
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

    /**
     * 选中文本 / 分享纯文本 (原版 `AssociationActivity.dispose()` 的唯一载荷):
     * 空白什么都不做, 整段是一条 http(s) 地址则交深链"自动识别类型"导入链, 否则进搜索页
     * —— 判据见 [shareTextTarget]。
     */
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
 * routeName → [LaunchRequest.NavigateTo] 的统一构造点
 * (通知点击 / 桌面快捷方式 / 跨进程 alias 入口共用)。
 *
 * 各端的入参形态不同 (Android 是两个 Intent extra, iOS / 鸿蒙 是一个 `route:` 前缀串),
 * 故前缀剥离留在各端; 但"非空 routeName 才成请求"这条判定与请求构造只此一份
 * (路由名与 ArkTS 侧 `route:` 字面量对齐, 不得改)。
 *
 * @return routeName 为空时返回 null (不投递空路由)
 */
fun navigateToRoute(routeName: String?, bookUrl: String? = null): LaunchRequest.NavigateTo? =
    routeName?.takeIf { it.isNotEmpty() }?.let { LaunchRequest.NavigateTo(it, bookUrl) }

/**
 * 分享/处理文本的去向 —— 全端唯一判据, 只此一份: 各端入口只负责原样投递文本
 * ([LaunchRequest.ProcessText]), 端侧要直达路由时按本判据取 [ShareTextTarget.SEARCH];
 * 消费侧见 LegadoApp 的启动请求分发, 不得各自另立判据。
 *
 * 与原版的对应关系: 原版 `AssociationActivity.dispose()` 把文本按空白分词 (`text.split("\\s")`) 后
 * 逐 token 判 `http.+`, 命中只把主界面带回前台且从不使用该链接; 此处按已拍板改为"整段命中即把该链接
 * 交给深链自动识别类型导入", 但口径收到"整段就是一条 http(s) 绝对地址":
 * - [isAbsUrl] —— 必须带 `http://` / `https://` 协议头, 大小写不敏感。`http.+` 这种不看协议头
 *   的写法会把 `http不是地址` 这类以 http 开头的普通文本也算成链接。
 * - 整段不含空白 —— 空白在 RFC 3986 里必须百分号编码, 含空白就不是"一条链接": 前导空白与换行、
 *   "链接 + 一句推荐"、"标题↵链接" 这类投递全部落 [ShareTextTarget.SEARCH] (不 trim, 不替用户改原文本)。
 */
enum class ShareTextTarget {
    /** 文本空白: 原版 `finish()` 后直接 return —— 不打开任何页面, 也不提示。 */
    NOTHING,

    /**
     * 整段文本就是一条 http(s) 绝对地址: 交 legado:// 深链的自动识别链
     * (DeepLinkImportType.UNKNOWN, 消费侧见 DeepLinkImportHost.runSchemeImport):
     * 1. 先零网络按书源认领判书籍 (WebBook.matchBookSourceByUrl: UrlOption 的 origin /
     *    同 baseUrl 可添加书籍的源 / `bookUrlPattern` 整段匹配) —— 被认领就带着这个源抓详情进
     *    详情页;
     * 2. 不认领才下载内容嗅探类型: 排版配置 zip 一步导入; JSON 识别成书源/RSS源/替换规则/主题/
     *    词典规则/正文规则/HTTP TTS 后弹对应导入页。
     * 两步都不命中时按"格式不对"报错提示 —— 不会当书源网页去浏览。
     */
    AUTO_IMPORT,

    /** 其余: 整段文本送搜索页。 */
    SEARCH,
}

fun shareTextTarget(text: String): ShareTextTarget = when {
    text.isBlank() -> ShareTextTarget.NOTHING
    text.isAbsUrl() && text.none { it.isWhitespace() } -> ShareTextTarget.AUTO_IMPORT
    else -> ShareTextTarget.SEARCH
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
