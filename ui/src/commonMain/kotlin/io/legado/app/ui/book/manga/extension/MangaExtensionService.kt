package io.legado.app.ui.book.manga.extension

import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * 漫画插件管理能力暴露 (平台接口, 模式同 MangaReaderScreenModel.Platform + Providers)。
 *
 * 接口只依赖 shared/ui 层数据类型, 不感知插件宿主实现 (DexClassLoader 装载仅 Android 可用);
 * app 端经 [MangaExtensionServiceProviders.register] 注册 (桥接 MangaExtensionManager),
 * getOrNull()==null 的平台 (desktop 等) 隐藏「我的」页入口, 未来可实现补齐。
 *
 * 插件源以虚拟 BookSource 行进入书源体系后, 取数走 WebBook 的 MangaSourceDelegates 委派;
 * 本接口另暴露插件源的筛选器访问, 供搜索页筛选条与委派共享同一份 Filter 状态。
 */
interface MangaExtensionService {

    /**
     * 幂等初始化 (插件管理 UI 首入口调用一次): 装载已装插件、同步虚拟 BookSource 行、
     * 拉取仓库可用插件列表。
     */
    fun init()

    /** 插件管理页状态 (已装/未装载/可用/仓库/安装进度)。 */
    val state: StateFlow<MangaExtensionUiState>

    /** 刷新仓库并重拉可用插件列表 (对照 findAvailableExtensions)。 */
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

    /** 当前展示语言过滤 (空集=全部; 持久化于插件偏好, 随备份进出)。 */
    val selectedLanguages: Set<String>

    /** 设置展示语言过滤并触发可用列表重算。 */
    fun setLanguages(languages: Set<String>)

    /** 添加插件仓库 (名称与指纹由仓库索引元数据带回)。 */
    suspend fun addRepo(url: String): Result<MangaRepoItem>

    /** 删除插件仓库 (按仓库索引地址 indexUrl)。 */
    suspend fun removeRepo(indexUrl: String)

    /** 该书源 URL 是否为插件漫画源 (搜索页筛选条显隐 + 委派识别)。 */
    fun isPluginSource(bookSourceUrl: String): Boolean

    /**
     * 插件源当前筛选器 (带 UI 可回填的可变状态; 非插件源返回 null)。
     * 返回实例与取数委派搜索时使用的是同一份, 回填即生效。
     */
    suspend fun getFilterList(bookSourceUrl: String): FilterList?
}

/** 插件内容分级 (与插件宿主 ContentWarning 对齐)。 */
enum class MangaContentWarning { SAFE, MIXED, NSFW }

/** 已装/未装载/可用插件的 UI 快照 (与插件宿主实体解耦, 平台接口只暴露本文件类型)。 */
data class MangaExtensionItem(
    val pkgName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val lang: String?,
    val contentWarning: MangaContentWarning,
    val isNsfw: Boolean,
    /** true=已装载出源 / true 表示条目来自已装列表 */
    val isInstalled: Boolean,
    val hasUpdate: Boolean = false,
    val isUntrusted: Boolean = false,
    val isObsolete: Boolean = false,
    /** 已装载出的源数量 (未装载=0) */
    val sourceCount: Int = 0,
    /** 未装载原因描述 (Untrusted/Unsigned/Failed...), 已装载为 null */
    val notLoadedReason: String? = null,
)

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
    val repos: List<MangaRepoItem> = emptyList(),
    val installSteps: Map<String, MangaInstallState> = emptyMap(),
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
