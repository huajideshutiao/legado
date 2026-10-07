package io.legado.app.ui.book.manga.extension

import io.legado.app.model.webBook.AnimeFilterSession
import io.legado.app.model.webBook.MangaFilterSession
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * 漫画插件管理能力暴露 (平台接口, 模式同 MangaReaderScreenModel.Platform + Providers)。
 *
 * 接口只依赖 shared/ui 层数据类型, 不感知插件宿主实现 (APK/DEX 装载仅 JVM 可用);
 * 由 Android (AndroidMangaExtensionPlatform) 与桌面 (SharedMangaExtensionPlatform) 经
 * [MangaExtensionServiceProviders.register] 注册; 未注册端 getOrNull()==null, 「我的」页入口隐藏。
 *
 * 插件源以虚拟 BookSource 行进入书源体系后, 取数走 WebBook 的 PluginSourceDelegates 委派;
 * 本接口另暴露插件源筛选会话的新建入口 (实例由页面持有并经取数链透传, 不经缓存)。
 */
interface MangaExtensionService {

    /**
     * 幂等初始化 (插件管理 UI 首入口调用一次): 装载已装插件、同步虚拟 BookSource 行、
     * 拉取仓库可用插件列表。
     */
    fun init()

    /** 插件管理页状态 (已装/未装载/可用/仓库/安装进度)。 */
    val state: StateFlow<MangaExtensionUiState>

    /**
     * 刷新仓库并重拉可用插件列表 (对照 findAvailableExtensions)。
     * 实现应在执行期间把 [MangaExtensionUiState.refreshing] 置 true (不得复用 loading:
     * combine 重建 state 会把它覆盖回 false), 完成/异常后置 false。
     */
    suspend fun refresh()

    /** 检查更新, 返回有更新的插件名列表 (对照 checkForUpdates)。 */
    suspend fun checkForUpdates(): List<String>

    /** 安装可用插件 (按包名, 安装进度经 [MangaExtensionUiState.installSteps] 流出)。 */
    fun install(pkgName: String)

    /** 更新已装插件 (按包名; 仅 hasUpdate 项展示入口)。 */
    fun update(pkgName: String)

    /** 取消安装/更新 (按包名)。 */
    fun cancelInstall(pkgName: String)

    /** 卸载已装插件 (按包名; 已装/未装载均可能被卸载)。 */
    fun uninstall(pkgName: String)

    /** 信任未签名校验通过的插件 (按包名, 未信任确认弹窗确认后调用)。 */
    fun trust(pkgName: String)

    /** 设置展示语言过滤并触发可用列表重算。 */
    fun setLanguages(languages: Set<String>)

    /** 设置插件类型过滤 (只作用「可用」列表, 与语言过滤同域) 并持久化。 */
    fun setKindFilter(filter: MangaExtensionKindFilter)

    /** 设置内容分级过滤 (同 [setKindFilter]) 并持久化。 */
    fun setContentFilter(filter: MangaContentFilter)

    /** 添加插件仓库 (名称与指纹由仓库索引元数据带回)。 */
    suspend fun addRepo(url: String): Result<MangaRepoItem>

    /** 删除插件仓库 (按仓库索引地址 indexUrl)。 */
    suspend fun removeRepo(indexUrl: String)

    /**
     * 新建一份漫画插件源的默认筛选会话 (非漫画插件源返回 null)。
     *
     * 筛选状态不持久化: 实例由页面 (VM) 创建持有、经取数链透传, 随页面销毁即丢,
     * 不经任何全局缓存; 重置 = 再调一次本方法换新默认实例 (对齐 Mihon resetFilters)。
     */
    suspend fun createMangaFilterSession(bookSourceUrl: String): MangaFilterSession? = null

    /** 新建一份视频插件源的默认筛选会话 (同 [createMangaFilterSession])。 */
    suspend fun createAnimeFilterSession(bookSourceUrl: String): AnimeFilterSession? = null

    /**
     * 该插件是否提供自带配置界面 (源实例实现 `eu.kanade.tachiyomi.source.ConfigurableSource`
     * 或 `eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource`)。
     * 未装载出源 (NotLoaded) 与未安装 (Available) 恒为 false —— 配置契约只能从活实例上取。
     */
    fun isConfigurable(pkgName: String): Boolean = false

    /**
     * 读取插件自带配置项快照: 平台侧构造 shim `PreferenceScreen`、调
     * `setupPreferenceScreen(screen)`、再按插件自身 SharedPreferences 回填当前值,
     * 最终转成跨层数据类 [MangaPrefItem] (UI 层不引用 androidx.preference shim)。
     * 未实现配置契约返回空表。
     */
    suspend fun buildPreferenceItems(pkgName: String): List<MangaPrefItem> = emptyList()

    /**
     * 写入单个配置项 (对齐 androidx `Persistable.setXxx()`): 经 shim 的 `setText/setValue/setValues`
     * 落插件自身 SharedPreferences (`source_<sourceId>`, 与扩展侧 `keiyoushi.utils.getPreferencesLazy`
     * 同契约), 扩展下次读取即生效。
     */
    suspend fun setPreferenceValue(pkgName: String, key: String, value: MangaPrefValue) {}

    /**
     * 点击配置项 (对齐 androidx `Preference.performClick()`): 扩展挂的 `OnPreferenceClickListener`
     * 返回 true 即消费, 否则走默认 `onClick()` (开关类即切换)。按 [MangaPrefItem.index] 定位 shim 实例。
     * 未实现配置契约时静默无操作。
     */
    suspend fun performPreferenceClick(pkgName: String, index: Int) {}

    /**
     * 开关类配置项切到 [newValue] (对齐 androidx `TwoStatePreference`): `callChangeListener(newValue)`
     * 通过才 `setChecked` (含持久化), 未挂监听视为通过。
     */
    suspend fun applyPreferenceChange(pkgName: String, index: Int, newValue: Boolean) {}

    /**
     * 输入框绑定 (对齐 androidx `EditTextPreference.OnBindEditTextListener` 的触发时机:
     * 打开输入对话框时回调一次), 让扩展侧的输入框配置照常执行。
     */
    suspend fun bindEditTextPreference(pkgName: String, index: Int) {}
}

/** 插件内容分级 (与插件宿主 ContentWarning 对齐)。 */
enum class MangaContentWarning { SAFE, MIXED, NSFW }

/**
 * 插件类型: 漫画 (Tachiyomi/Mihon 系) / 视频 (Aniyomi 系)。
 * 已装载条目按 `Loaded.animeSources` 是否非空判定 (装载器已按源实例类型分流);
 * 未装载条目按仓库 kind; 可用条目按所属仓库 kind。
 */
enum class MangaExtensionKind { MANGA, VIDEO }

/** 插件类型过滤档位 (只作用「可用」列表; [ALL] 不过滤)。 */
enum class MangaExtensionKindFilter { ALL, MANGA, VIDEO }

/** 内容分级过滤档位 ([NSFW] 档含 MIXED, 与列表 NSFW 角标同判定; [ALL] 不过滤)。 */
enum class MangaContentFilter { ALL, SAFE, NSFW }

/**
 * 插件声明的源条目 (仓库索引的 `sources[]`; 已装载条目取活源实例, 未装载条目为空表)。
 * 供搜索匹配 (源名/源站点/源 id)、条目显示名与「打开网站」(首个非空 [homeUrl]) 使用。
 */
data class MangaExtensionSourceEntry(
    val id: Long,
    val name: String,
    /** 源站点地址 (索引 baseUrl/homeUrl; 活源取 HttpSource.getHomeUrl, 取不到为空串)。 */
    val homeUrl: String = "",
)

/** 未装载原因 (与插件宿主 NotLoaded.Reason 对齐; 文案由 Composable 按语言资源渲染)。 */
enum class MangaNotLoadedReason {
    FILTERED,
    UNSIGNED,
    UNSUPPORTED_LIB_VERSION,
    MALFORMED,
    FAILED,
}

/** 已装/未装载/可用插件的 UI 快照 (与插件宿主实体解耦, 平台接口只暴露本文件类型)。 */
data class MangaExtensionItem(
    val pkgName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val lang: String?,
    val contentWarning: MangaContentWarning,
    /** 源条目 (已装载=活源实例, 可用=仓库索引, 未装载=空表)。 */
    val sources: List<MangaExtensionSourceEntry> = emptyList(),
    /** true=已装载出源 / true 表示条目来自已装列表 */
    val isInstalled: Boolean,
    val hasUpdate: Boolean = false,
    val isUntrusted: Boolean = false,
    val isObsolete: Boolean = false,
    /** 已装载出的源数量 (未装载=0) */
    val sourceCount: Int = 0,
    /** 未装载原因 (已装载为 null; Untrusted 单列一区不展示原因) */
    val notLoadedReason: MangaNotLoadedReason? = null,
    /** [MangaNotLoadedReason.FAILED] 的装载器原始消息 */
    val notLoadedDetail: String? = null,
    /**
     * 插件图标 URL (仓库索引 `Available.iconUrl`)。
     * 可用条目直接取自身条目; 已装/未装载条目按同 pkgName 在仓库索引里反查回填
     * (已装实体不带 iconUrl), 索引无该包时 null。
     */
    val iconUrl: String? = null,
    /** 插件类型 (漫画/视频), 见 [MangaExtensionKind]。 */
    val kind: MangaExtensionKind = MangaExtensionKind.MANGA,
    /** 是否提供自带配置界面 (ConfigurableSource/ConfigurableAnimeSource), 仅已装载条目可能为 true。 */
    val isConfigurable: Boolean = false,
) {

    /**
     * 扩展站点地址 (首个非空源站点)。
     * 打开网站入口只在拿得到地址时展示 (对齐 Mihon 取扩展首个源 baseUrl); 同一扩展多个源
     * 通常同站, 首个源缺地址时取后续源。
     */
    val websiteUrl: String?
        get() = sources.firstNotNullOfOrNull { it.homeUrl.takeIf { url -> url.isNotBlank() } }

    /**
     * 条目显示名: 源名 (扩展声明的第一个源), 无源信息回退扩展名。
     * 中文站点的扩展名多为拉丁文 (仓库 `tachiyomix.name`, 如 Dm5 / Jinman Tiantang),
     * 源名 (如 禁漫天堂) 才是用户认得的名字。
     */
    val sourceName: String?
        get() = sources.firstNotNullOfOrNull { it.name.takeIf { name -> name.isNotBlank() } }
}

/** 插件仓库的 UI 快照。 */
data class MangaRepoItem(
    val name: String,
    /** 仓库索引地址 (增删以它为身份) */
    val indexUrl: String,
    val signingKeyFingerprint: String? = null,
)

/** 单插件安装进度状态 (与插件宿主 InstallStep sealed 对齐)。 */
enum class MangaInstallState {
    PENDING,
    DOWNLOADING,
    INSTALLING,
    INSTALLED,
    ERROR,
}

/** [MangaExtensionService.state] 的载荷。 */
data class MangaExtensionUiState(
    val loading: Boolean = false,
    val installed: List<MangaExtensionItem> = emptyList(),
    /** 已装但未装载出源 (未信任/被过滤/失败...), 卸载/信任操作对其生效 */
    val notLoaded: List<MangaExtensionItem> = emptyList(),
    val available: List<MangaExtensionItem> = emptyList(),
    /** 可用插件的语言全集 (未按语言筛选, 语言 chips 数据源恒完整)。 */
    val availableLanguages: Set<String> = emptySet(),
    /** 当前语言筛选 (空集=全部, 单选语义; 语言 chips 选中态与可用列表过滤同源)。 */
    val selectedLanguages: Set<String> = emptySet(),
    /** 当前类型筛选 (只作用「可用」列表, 与语言筛选同域同生命周期)。 */
    val kindFilter: MangaExtensionKindFilter = MangaExtensionKindFilter.ALL,
    /** 当前内容分级筛选 (同 [kindFilter])。 */
    val contentFilter: MangaContentFilter = MangaContentFilter.ALL,
    val repos: List<MangaRepoItem> = emptyList(),
    val installSteps: Map<String, MangaInstallState> = emptyMap(),
    /**
     * 刷新仓库索引中 (点右上角刷新按钮后置位)。
     * 与首屏加载 [loading] 分开持有: 平台 combine 每次发射都会重建整个 state,
     * 若复用 [loading] 会被 combine 立即覆盖回 false (本字段由独立 StateFlow 参与 combine,
     * 故不会被覆盖)。整页转圈由 `loading || refreshing` 取或呈现。
     */
    val refreshing: Boolean = false,
)

object MangaExtensionServiceProviders {
    @Volatile
    private var impl: MangaExtensionService? = null

    /** 宿主启动早期注册一次 (app 端 MainActivity.initializePlatform)。 */
    fun register(impl: MangaExtensionService) {
        this.impl = impl
    }

    /** 未注册端 (desktop 等) 返回 null, UI 据此隐藏入口。 */
    fun getOrNull(): MangaExtensionService? = impl
}
