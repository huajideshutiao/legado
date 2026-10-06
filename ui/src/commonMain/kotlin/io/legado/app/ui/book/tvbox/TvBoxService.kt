package io.legado.app.ui.book.tvbox

import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * TVBox 影视源管理能力暴露 (平台接口, 模式同 MangaExtensionService 与其 Providers)。
 *
 * 接口只依赖 shared/ui 层数据类型, 不感知 TvBoxManager 与 jar 装载实现 (平台差异经
 * [io.legado.app.help.tvbox.TvBoxPlatforms] 注入: Android=DexClassLoader, 桌面=URLClassLoader);
 * app 端 MainActivity 与桌面端 DesktopCore 都经 [TvBoxServiceProviders.register] 注册,
 * getOrNull()==null 的未注册平台隐藏「我的」页入口。
 *
 * 站点以虚拟 BookSource 行 (bookSourceType=video) 进入书源体系后, 取数走 WebBook 的
 * PluginSourceDelegates 委派, 搜索/详情/书内播放复用既有路径 —— 本接口刻意不提供
 * 独立搜索/播放入口 (用户走既有链路: 搜索页 → 书籍详情 → 视频播放页)。
 */
interface TvBoxService {

    /** 幂等初始化 (管理页首入口调用一次): 重载已持久化配置并同步虚拟书源行。 */
    fun init()

    /** 管理页状态 (配置来源/站点/jar 状态/导入错误)。 */
    val state: StateFlow<TvBoxUiState>

    /** 从 URL 拉取配置并切为当前配置 (同时记入来源列表并持久化)。 */
    suspend fun importConfig(url: String): Result<Unit>

    /** 重拉当前来源配置 (对照导入; 失败返回 Result.failure, 原因写进 state.error)。 */
    suspend fun refresh(): Result<Unit>

    /** 删除配置来源 (删除当前生效源时同时清理虚拟书源行)。 */
    suspend fun removeSource(url: String): Result<Unit>

    /** 切换到已存在的其它配置来源 (已持久化, 拉取失败时保留原状态并写 state.error)。 */
    suspend fun activateSource(url: String): Result<Unit>

    /**
     * 站点添加/移除: 落到虚拟书源行的存在与否 (移除 = 删行, 书源界面随之不再显示);
     * 是否参与搜索由书源界面的启用开关管理 (本接口不写行的 enabled)。
     */
    suspend fun setSiteEnabled(siteKey: String, enabled: Boolean)

    /** 站点虚拟书源 URL (不支持的站点返回 null)。 */
    fun sourceUrlOf(siteKey: String): String?

    /**
     * 重探全部 spider jar (首次取 Spider 会触发下载+装载, 失败原因写进
     * [TvBoxUiState.jars]); 各 jar 并行探测, 结果随新的状态发射。
     */
    fun probeJars()

    /** 清错 (state.error 一次展示完后由 UI 调用)。 */
    fun clearError()
}

/**
 * 站点形态 (由 TVBox 配置 sites[].type 与宿主当前支持度共同决定):
 * UI 直接取它决定类型标记与是否允许开关。
 */
enum class TvBoxSiteKind {
    /** csp_ 前缀: JAR Spider 站点 */
    JAR,

    /** api 含 .js: JS Spider 站点 */
    JS,

    /** http 开头: 苹果 CMS 直连站 */
    CMS,

    /** 其余 (如 Python 遗留站), 不落虚拟行 */
    OTHER,
}

/** spider jar 状态 (按配置里 distinct 的 jar 规格串)。 */
enum class TvBoxJarStatus { IDLE, LOADING, READY, FAILED }

/** 单个 spider jar 的 UI 快照。 */
data class TvBoxJarItem(
    /** jar 规格串 "url;md5;<md5>" (身份即它) */
    val spec: String,
    val status: TvBoxJarStatus,
    /** 装载失败原因 (LOADING/READY 为 null) */
    val message: String? = null,
    val siteCount: Int = 0,
)

/** 单个 TVBox 站点的 UI 快照。 */
data class TvBoxSiteItem(
    val key: String,
    val name: String,
    val kind: TvBoxSiteKind,
    val api: String,
    val searchable: Boolean,
    val filterable: Boolean,
    /** 是否受支持 (是否在落虚拟书源行) */
    val supported: Boolean,
    /** 是否已添加 (已落虚拟书源行; 不支持的行恒 false) */
    val added: Boolean,
    /** 所在 jar 规格串 (CMS/不支持站点为空) */
    val jar: String? = null,
)

/** [TvBoxService.state] 的载荷。 */
data class TvBoxUiState(
    /** 配置拉取/解析中 */
    val loading: Boolean = false,
    /** 已保存的配置来源 URL (含当前) */
    val sources: List<String> = emptyList(),
    /** 当前生效的配置来源 URL (无配置时为 null) */
    val activeSource: String? = null,
    val sites: List<TvBoxSiteItem> = emptyList(),
    val jars: List<TvBoxJarItem> = emptyList(),
    /** 最后一次错误 (导入/切换/刷新/jar 装载), null 表示无错 */
    val error: String? = null,
)

object TvBoxServiceProviders {
    @Volatile
    private var impl: TvBoxService? = null

    /** 宿主启动早期注册一次 (app 端 MainActivity.initializePlatform)。 */
    fun register(impl: TvBoxService) {
        this.impl = impl
    }

    /** 未注册端返回 null, UI 据此隐藏入口。 */
    fun getOrNull(): TvBoxService? = impl
}
