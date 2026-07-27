package io.legado.desktop.ui.main

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.DefaultDataShared
import io.legado.app.help.book.BookHelpShared
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.service.UpdateBookCallback
import io.legado.app.help.service.UpdateBookShared
import io.legado.app.help.storage.BackupShared
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.jvmGetString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 桌面端 MainViewModel 等价物 (对照 app 端 `io.legado.app.ui.main.MainViewModel`)。
 *
 * # 业务编排下沉说明 (UpdateBookShared)
 *
 * 原本目录更新 / 强制刷新 / 自动更新 / 预下载调度等编排逻辑全部在本类内实现
 * (upToc / forceRefresh / scheduleAutoUpdate / refreshBook / updateToc / addDownload /
 * cacheBook / startUpTocJob / onUpTocJobCompleted / addToWaitUp / pollWaitUpTocBook /
 * upPool / updateProgress 等), 与 app 端 `MainViewModel` 高度重复。本类已把这些**非平台特有**
 * 的编排逻辑下沉到 shared commonMain 的 [UpdateBookShared], 本类仅保留:
 * - **平台特有方法**: [postLoad] (桌面端含缓存清理 + WebDav 同步, 与 app 端 MainViewModel.postLoad
 *   + App.kt onCreate Coroutine.async 块对齐) / [restoreWebDav] (调 shared AppWebDavShared)
 * - **桌面通知实现**: [DesktopUpdateBookCallback] inner class, 桥接 [UpdateBookCallback]
 *   到桌面端 UI (StateFlow) + `Toasters` (toast 反馈)
 * - **转发方法**: [upToc] / [forceRefresh] / [scheduleAutoUpdate] / [cancelRefreshJobs] /
 *   [isUpdate] / [markGroupAutoUpdated] / [onCleared] 直接转发到 [updateBookShared]
 * - **StateFlow 暴露**: [isRefreshing] / [progressText] 转发 [updateBookShared] 的 StateFlow
 *
 * 与 app 端 `MainViewModel` 改造对齐 (app 端 callback 用 NotificationManagerCompat +
 * context.toastOnUi, 本类 callback 用 StateFlow + Toasters)。
 *
 * # 生命周期
 *
 * 桌面端无 ViewModelStore / lifecycleScope, 本类自带 [scope] (SupervisorJob + Default),
 * 由 [io.legado.desktop.ui.bookshelf.BookshelfScreen] 通过 `remember` 持有, 窗口退出时
 * `DisposableEffect.onDispose { onCleared() }` 取消 (对照 app 端 ViewModel.onCleared)。
 *
 * 构造无参, 所有依赖经 provider 间接获取 (AppDbProviders / AppConfigProviders / Toasters),
 * 与桌面端 BookshelfViewModel 构造模式一致。
 */
class DesktopMainViewModel {

    /** VM 自管 scope, 桌面端无 lifecycleScope; onCleared 时取消即可 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * UpdateBook 编排核心 (shared commonMain 下沉件), 持有实例并转发公共方法。
     *
     * scope 用本类自管 [scope] (与原 DesktopMainViewModel 一致);
     * callback 用 [DesktopUpdateBookCallback] (桥接桌面 UI StateFlow + Toasters)。
     */
    private val updateBookShared: UpdateBookShared by lazy {
        UpdateBookShared(scope, DesktopUpdateBookCallback())
    }

    // region UI 状态暴露 (转发 UpdateBookShared 的 StateFlow, 替代原 _isRefreshing / _progressText)

    /** 是否正在刷新 (upToc 或 refresh 任一在跑), UI 用于禁用刷新按钮 / 显示 loading */
    val isRefreshing: StateFlow<Boolean> get() = updateBookShared.isRefreshing

    /** 进度文案 (如 "更新目录 3/10"), null 表示无任务; UI 可选订阅显示在顶栏 */
    val progressText: StateFlow<String?> get() = updateBookShared.progressText
    // endregion

    /**
     * 取消刷新 / 更新目录任务, 用于退出应用或切换路由时清理 (对照 app 端 cancelRefreshJobs)。
     *
     * 转发到 [updateBookShared.cancelRefreshJobs], 内部取消协程 Job + 清空等待队列 +
     * 重置计数器 + 调 callback.onProgressCancel (桌面端 no-op, 无系统通知)。
     */
    fun cancelRefreshJobs() {
        updateBookShared.cancelRefreshJobs()
    }

    /**
     * threadCount 改变时重建线程池 (对照 app 端 `MainViewModel.upPool`)。
     *
     * 转发到 [updateBookShared.upPool], 内部读取最新 threadCount 并在无 job 运行时重建 upTocPool。
     * 桌面端目前无 PreferKey.threadCount 的 EventBus 监听 (DesktopOtherConfigScreen 写 prefs 后不发事件),
     * 故通常由 [UpdateBookShared.refreshBook] / [UpdateBookShared.startUpTocJob] 在下一批次前自查重建;
     * 此方法供后续如需接入配置变更监听时直接调用, 与 app 端保持 API 对齐。
     */
    fun upPool() {
        updateBookShared.upPool()
    }

    fun isUpdate(bookUrl: String): Boolean {
        return updateBookShared.isUpdate(bookUrl)
    }

    /**
     * 主动更新目录, 不做时间窗判断 (用于下拉刷新 / 菜单项)。
     * 对照 app 端 `MainViewModel.upToc(books)`。
     */
    fun upToc(books: List<Book>) {
        updateBookShared.upToc(books)
    }

    /**
     * 强制刷新书籍信息, 无视 canUpdate 属性 (用于菜单项)。
     * 对照 app 端 `MainViewModel.forceRefresh(books)`。
     */
    fun forceRefresh(books: List<Book>) {
        updateBookShared.forceRefresh(books)
    }

    /**
     * 自动更新目录, 跳过最近已检查过的书籍 (对照 app 端 `MainViewModel.scheduleAutoUpdate`)。
     */
    fun scheduleAutoUpdate(books: List<Book>) {
        updateBookShared.scheduleAutoUpdate(books)
    }

    /** 标记分组已自动更新 (避免 Composable 重组后重复触发), 转发到 [updateBookShared]。 */
    fun markGroupAutoUpdated(groupId: Long): Boolean {
        return updateBookShared.markGroupAutoUpdated(groupId)
    }

    /**
     * 启动期默认数据加载 + 缓存清理 + WebDav 进度同步
     * (对照 app 端 `MainViewModel.postLoad` + app 端 `App.kt` onCreate 的 Coroutine.async 块)。
     *
     * # 默认数据加载 (对照 app 端 MainViewModel.postLoad)
     *
     * app 端检查 httpTTSDao.count() == 0 时插入 DefaultData.httpTTS
     * (从 assets/defaultData/httpTTS.json 加载, 依赖 Android assets 目录);
     * 桌面端通过 [DefaultDataShared.importDefaultHttpTTS] 加载, 资源从
     * commonMain/composeResources/files/defaultData/httpTTS.json 经
     * [io.legado.desktop.help.DesktopDefaultDataResourceProvider] 读取 (Main.kt 已注册),
     * 跨端单一数据源。同样检查 count() == 0 才插入, 避免重置用户已修改的默认项。
     *
     * # 缓存清理 + WebDav 进度同步 (对照 app 端 App.kt 行 136-152 的 Coroutine.async 块)
     *
     * app 端在 `App.onCreate` 用两个独立 `Coroutine.async` 块并行执行:
     * 1. 缓存过期清理 (受 `LocalConfig.lastBackup + 1天` 时间窗控制):
     *    - `appDb.cacheDao.clearDeadline(now)` 清理过期 cache 表记录
     *    - `BookHelp.clearInvalidCache()` 清理无效书缓存目录
     *    - `Backup.clearCache()` 清理备份临时文件
     *    - `ReadBookConfig.clearBgAndCache()` 清理阅读背景缓存
     *    - `ThemeConfig.clearBg()` 清理主题背景缓存
     * 2. WebDav 阅读进度同步 (受 `AppConfig.syncBookProgress` 控制):
     *    - `AppWebDav.downloadAllBookProgress()` 拉取云端进度写回本地
     *
     * 桌面端在本方法内用两个独立 [scope.launch] 并行执行上述两块, 行为对齐。
     *
     * # 平台差异 (缓存清理项可用性)
     *
     * - **cacheDao.clearDeadline**: shared CacheDao 已下沉, 直接调用 [appDb.cacheDao]
     * - **BookHelp.clearInvalidCache**: shared [BookHelpShared.clearInvalidCache] 已下沉,
     *   直接调用 (四步编排: 删失效书目录 → 漫画缓存超限淘汰 → 大变量清理 → clearCacheExtra)
     * - **Backup.clearCache**: shared [BackupShared.clearCache] 已下沉, 直接调用
     * - **ReadBookConfig.clearBgAndCache**: shared `ReadBookConfigShared.clearBgAndCache`
     *   已下沉 (基于 AppFilesDirs + BackupFileOps), 经 [ReadBookConfigProviders.get] 调用
     * - **ThemeConfig.clearBg**: shared `ThemeConfigProvider.clearBg` 已暴露 (default 实现,
     *   基于 AppFilesDirs + BackupFileOps), 经 [ThemeConfigProviders.get] 调用
     *
     * 所有 provider 调用均用 `runCatching` 包裹, 避免未注册 / 方法缺失导致启动崩溃
     * (对照 app 端 Coroutine.async 内部异常吞没语义)。
     * 实现已下沉到顶层 [DesktopStartupTasks.run], 供 Main.kt 启动期不持有 VM 实例直接调用,
     * 避免创建临时 VM 泄漏其内部 scope。
     */
    fun postLoad() {
        DesktopStartupTasks.run(scope)
    }

    /**
     * 从 WebDav 恢复备份 (对照 app 端 `MainViewModel.restoreWebDav`, 不简化)。
     *
     * 直接调用 shared commonMain 已下沉的 [AppWebDavShared.restoreWebDav],
     * 行为与 app 端 `AppWebDav.restoreWebDav` 完全一致 (AppWebDav 是 AppWebDavShared
     * 的 app 端 actual 包装, 桌面端直接用 shared 通用版)。
     *
     * @param name 备份文件名 (含扩展名)
     */
    fun restoreWebDav(name: String) {
        scope.launch(Dispatchers.Default) {
            AppWebDavShared.restoreWebDav(name)
        }
    }

    /**
     * 宿主销毁时调用, 取消所有协程 + 关闭线程池 (对照 app 端 ViewModel.onCleared)。
     *
     * 转发到 [updateBookShared.onCleared] (内部 cancelRefreshJobs + close upTocPool),
     * 再 scope.cancel() 兜底取消所有子协程。
     *
     * 平台差异:
     * - app 端 onCleared 仅 close upTocPool (cacheBookJob 由 viewModelScope 取消间接取消,
     *   CacheBook 单例跨 Activity 保留)
     * - 桌面端无 ViewModelStore, scope.cancel() 会自动取消所有子协程 (包括 cacheBookJob),
     *   cancelRefreshJobs 已显式 cancel cacheBookJob, 这里再 scope.cancel() 兜底
     * - 不调 CacheBookShared.close() (单例跨 VM 保留, 与 app 端 CacheBook 语义一致;
     *   进程退出时 JVM 自动回收, 无需显式清理)
     */
    fun onCleared() {
        updateBookShared.onCleared()
        scope.cancel()
    }

    /**
     * [UpdateBookCallback] 的桌面 actual 实现 (inner class, 持有 DesktopMainViewModel this)。
     *
     * 桥接 [UpdateBookShared] 的进度回调到桌面端 SystemTray 通知 + Toasters:
     * - [onProgressUpdate]: 桌面端不发系统进度通知 (Windows 无安卓式进度渠道), 仅通过 StateFlow 暴露给 UI
     * - [onProgressCancel]: no-op (无系统通知需取消)
     * - [toastForceRefreshBusy]: `Toasters.get().toast("正在刷新中, 请稍后再试")`
     * - [toastForceRefreshStart]: `Toasters.get().toast("开始强制刷新 N 本")`
     * - [toastForceRefreshDone]: `Toasters.get().toast("刷新完成")`
     *
     * # StateFlow 暴露
     * 原 `updateProgress` 还更新 `_isRefreshing` / `_progressText` StateFlow, 现由
     * [updateBookShared] 内部 updateProgress 直接更新自己的 StateFlow, 本类通过
     * [isRefreshing] / [progressText] 转发暴露, 行为对齐。
     *
     * # 防御
     */
    private inner class DesktopUpdateBookCallback : UpdateBookCallback {

        override fun onProgressUpdate(active: Boolean, title: String, content: String, count: Int, total: Int) {
            // 桌面端不发系统进度通知 (Windows 无安卓式进度通知渠道, 仅文本通知每次重新发体验差)
            // 进度通过 isRefreshing/progressText StateFlow 暴露给 UI 内显示, 见类 KDoc StateFlow 暴露段
        }

        override fun onProgressCancel() {
            // 桌面端无系统进度通知需取消 (onProgressUpdate 不再发通知)
        }

        override fun toastForceRefreshBusy() {
            Toasters.get().toast(jvmGetString("force_refresh_busy"))
        }

        override fun toastForceRefreshStart(count: Int) {
            Toasters.get().toast(jvmGetString("force_refresh_start", count))
        }

        override fun toastForceRefreshDone() {
            Toasters.get().toast(jvmGetString("refresh_complete"))
        }
    }
}

/**
 * 桌面端启动期异步任务集合 (默认 HttpTTS 加载 + 缓存过期清理 + WebDav 进度同步)。
 *
 * 对照 app 端 `MainViewModel.postLoad` + `App.kt` onCreate 的 Coroutine.async 块。
 * 提取为顶层 object 供 Main.kt 用独立 scope 调用, 避免创建临时 [DesktopMainViewModel]
 * 实例泄漏其内部 scope (临时 VM 无宿主调 onCleared, scope 永不取消)。
 *
 * @param scope 调用方提供的协程 scope, 由调用方管理生命周期
 */
object DesktopStartupTasks {

    /**
     * 启动默认 HttpTTS 加载 / 缓存过期清理 / WebDav 阅读进度同步三组并行任务。
     */
    fun run(scope: CoroutineScope) {
        // 1. 默认 HttpTTS 加载 (对照 app 端 MainViewModel.postLoad)
        //    首次启动 httpTTSDao.count() == 0 时, 调 DefaultDataShared.importDefaultHttpTTS
        //    插入默认 HttpTTS 列表 (资源经 DesktopDefaultDataResourceProvider 从
        //    commonMain/composeResources/files/defaultData/httpTTS.json 读取, Main.kt 已注册)
        scope.launch(Dispatchers.Default) {
            val appDb = AppDbProviders.get()
            if (appDb.httpTTSDao.count() == 0) {
                runCatching { DefaultDataShared.importDefaultHttpTTS() }
                    .onFailure { AppLog.put(jvmGetString("load_default_http_tts_failed", it.localizedMessage), it) }
            }
        }
        // 2. 缓存过期清理 (对照 app 端 App.kt 行 136-144 的 Coroutine.async 块)
        //    受 LocalConfig.lastBackup + 1天 时间窗控制, 不足 1 天则跳过
        scope.launch(Dispatchers.Default) {
            val lastBackup = runCatching {
                PreferenceProviders.get().getLong(LocalConfigKeys.lastBackup, 0L)
            }.getOrDefault(0L)
            if (lastBackup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()) {
                val appDb = AppDbProviders.get()
                // cacheDao.clearDeadline: 清理 cache 表中过期记录 (对照 app 端 appDb.cacheDao.clearDeadline)
                runCatching { appDb.cacheDao.clearDeadline(System.currentTimeMillis()) }
                // BookHelp.clearInvalidCache: 清理无效书缓存目录 (shared BookHelpShared 已下沉,
                // 四步编排与 app 端一致, 不再直调 BookStorageProviders 单步)
                runCatching { BookHelpShared.clearInvalidCache() }
                // Backup.clearCache: 清理备份临时文件 (shared BackupShared.clearCache 已下沉)
                runCatching { BackupShared.clearCache() }
                // ReadBookConfig.clearBgAndCache: 清理阅读背景缓存图片 + readConfig 临时缓存
                // (shared ReadBookConfigShared.clearBgAndCache 已下沉, 基于 AppFilesDirs + BackupFileOps)
                runCatching { ReadBookConfigProviders.get().clearBgAndCache() }
                // ThemeConfig.clearBg: 清理主题背景图片缓存
                // (shared ThemeConfigProvider.clearBg 已暴露 default 实现, 基于 AppFilesDirs + BackupFileOps)
                runCatching { ThemeConfigProviders.get().clearBg() }
            }
        }
        // 3. WebDav 阅读进度同步 (对照 app 端 App.kt 行 145-152 的 Coroutine.async 块)
        //    受 AppConfig.syncBookProgress 控制
        scope.launch(Dispatchers.Default) {
            if (AppConfigProviders.get().syncBookProgress) {
                runCatching { AppWebDavShared.downloadAllBookProgress() }
                    .onFailure { AppLog.put(jvmGetString("webdav_sync_progress_failed", it.localizedMessage), it) }
            }
        }
    }
}
