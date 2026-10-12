package io.legado.app.ui

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.help.topMostViewController
import io.legado.app.utils.File
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * iOS 分享扩展取件: 扫 App Group 共享容器里的收件目录, 把文件交给既有的外部请求链。
 *
 * # 为什么需要它
 * 分享扩展**不能拉起宿主 App** (扩展进程里没有通往宿主的 openURL 通道, Apple 限制),
 * 唯一交付通道是"扩展写共享容器 → 宿主来取": 写件侧是
 * `iosApp/ShareExtension/ShareViewController.swift` 的 `deliver`, 取件侧就是本对象。
 * 取件的时机是宿主启动 ([deliverPending] ← `registerIosProviders`) 与应用从后台回到前台
 * ([watchForeground]): 分享发生时别的应用在前台, 本应用只能在后台, 用户切回来即导入,
 * 不必等下次冷启动。
 * 取出的文件走既有链路, 不另立一套:
 * [handleIosLaunchRequest] → `LaunchRequest.ImportFile` → `LaunchRequestBus`
 * → `LegadoApp` 消费 → `AppRoute.ImportBook` → 各端已有的导入分发。
 *
 * # 取件为什么先落"应用内接收目录"再投递
 * 1. 容器路径不在 App 沙盒内, 也不在用户经"文件"面板授权的目录里 →
 *    iOS 的授权层 (`IosSecurityScopedStorage.withAccess` / `withLocalDirAccess`)
 *    会直接判"没有访问权限"并抛错, 导入链读不到;
 * 2. 投给导入链的路径要活到用户确认导入那一刻 (导入链就地读这份文件), 投完不能删;
 * 3. 取件**不写"有效书籍保存位置"** ([io.legado.app.help.book.effectiveBookSaveDir]): 那是
 *    用户可配置、在"文件"App 里可见的位置, 而取件只是替用户先收下分享来的文件, 此时用户
 *    还没在应用里确认导入 —— 按原名写进去会覆盖用户的同名文件, 用户不导入又会留下副本。
 *    收件副本落在应用支持目录下的私有接收目录: 沙盒内 (导入链无需授权)、应用私有
 *    (不暴露给"文件"App)、不随"清空缓存"清理 (书文件仍要能长期读)。
 *    读容器时**不**经 `withLocalDirAccess`: 那层授权是给"用户选的容器外目录"用的,
 *    对自己的共享容器只会误判成越权。
 *
 * # 清理
 * - 容器里的原件在副本落盘成功后立即删掉 (下次取件不会重复收件);
 * - 收件目录里的残留临时件 (写件侧先拷临时名再原子改名, 拷贝中途被系统杀掉就停在临时名上)
 *   在启动取件时删掉;
 * - 接收目录里的副本在每次取件末尾对账: 本进程投递过的不动, 被书架书籍引用 (书籍地址即该文件)
 *   的保留, 其余删掉 —— 用户没导入的、格式不支持的、只播一次的分享视频都不在本地长期留存。
 *
 * # 生效前提 (未签名产物上不会生效, 且不会崩)
 * App Group 靠 entitlement 落地 (`iosApp/iosApp.entitlements` 与
 * `ShareExtension/ShareExtension.entitlements`), 而本仓 CI 产物是**未签名 ipa**
 * (`CODE_SIGNING_ALLOWED=NO`, 与 iCloud 容器同样原因) → [NSFileManager]
 * `containerURLForSecurityApplicationGroupIdentifier` 恒返回 null。此时两端都给用户
 * 明确说明: 分享面板那一步由扩展当场说明 (见 ShareViewController),
 * 宿主这里在应用进入活动态后说明一次 (见 notifyEntryUnavailable)。
 */
object IosShareInbox {

    /**
     * App Group 共享容器标识。
     *
     * ⚠️ 必须与 `iosApp/iosApp.entitlements` / `iosApp/ShareExtension/ShareExtension.entitlements`
     * 的 `com.apple.security.application-groups` 值、以及 Swift 侧 `legadoAppGroupId`
     * 逐字一致 (改一处要四处同改)。
     */
    const val APP_GROUP_ID = "group.shutiao.reader"

    /** 容器内的收件目录名 (与 Swift 侧 `legadoInboxDirName` 同一字面量)。 */
    private const val INBOX_DIR_NAME = "ShareInbox"

    /** 应用内接收目录名: 容器里的收件先落这里, 再由既有导入链就地读取。 */
    private const val STORE_DIR_NAME = "ShareImports"

    /**
     * 隐藏临时件的后缀 (写件侧与 [stage] 同一套: ".<原名>.part")。
     *
     * 这是与分享扩展 (`iosApp/ShareExtension/ShareViewController.swift` 的 `deliver`) 的跨进程命名
     * 契约, 两侧逐字一致, 不能换名; 它不属整份写入的共享临时件契约
     * ([io.legado.app.utils.ATOMIC_WRITE_TEMP_SUFFIX]), 因此不被按共享契约清点的方删除——残留件
     * 只由 [discardAbandonedTemps] 在启动取件时清。
     */
    private const val TEMP_SUFFIX = ".part"

    /** "分享入口不可用"提示一次的记忆键 (未签名 ipa / 签名未勾 App Groups 的产物上会用到)。 */
    private const val UNAVAILABLE_NOTIFIED_KEY = "iosShareInboxUnavailableNotified"

    /** 分享入口不可用时给用户看的说明 (原因 + 可用替代路径)。 */
    private const val SHARE_ENTRY_UNAVAILABLE =
        "分享入口当前不可用：安装包签名不含共享容器（App Groups），" +
            "从分享面板分享进来的文件收不到。\n" +
            "可换用带该能力的签名安装，或先把文件存到「文件」App，再用应用内的「导入本地书籍」导入。"

    /**
     * 取件扫描跑在 IO 线程: didFinishLaunching 里做同步文件拷贝会拖住启动
     * (分享进来的 epub/视频可能几十上百 MB), 而投递口 [handleIosLaunchRequest]
     * 只是往 [io.legado.app.ui.root.LaunchRequestBus] 的无限队列里投一条,
     * 队列保留订阅前的请求, 晚到不影响消费。
     * 回到前台那次同理: 主线程只挂通知观察者, 目录扫描与拷贝都不在主线程上做。
     */
    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    /** 取件与对账串行: 启动取件与回到前台取件可能撞在一起 (同一件不能被收两遍)。 */
    private val pickUpMutex = Mutex()

    /**
     * 本进程投递过的副本绝对路径: 对账保留它们 (见 [discardUnimported])。
     * 只在 [pickUpMutex] 内读写。
     */
    private val stagedInProcess = HashSet<String>()

    /** 回到前台的取件钩子只挂一次 (见 [watchForeground])。 */
    @Volatile
    private var foregroundWatching = false

    /** 容器不可用的判定与提示只做一次 (容器能力由签名固定, 见 [notifyEntryUnavailable])。 */
    @Volatile
    private var entryUnavailableHandled = false

    /** 容器不可用的提示观察者 (等应用进入活动态再提示, 见 notifyEntryUnavailable)。 */
    @Volatile
    private var unavailableNotifier: Any? = null

    /**
     * 取件入口: 宿主启动链调用一次 (见 `IosProviderRegistry.registerIosProviders`),
     * 之后应用每次从后台回到前台自动再取一次 ([watchForeground])。
     *
     * 每次调用都把收件目录里当时存在的文件逐个收进应用内接收目录并交给既有导入链, 末尾对账清理
     * 接收目录 (启动这次还顺带清掉收件目录里的残留临时件, 见 [discardAbandonedTemps])。
     * 只处理"当时在容器里"的文件, 收下的原件随后即被清掉, 同一件不会被收两遍。
     * 容器取不到 (无 entitlement) 或接收目录不可用时给用户可见说明并按"本次不取件"处理,
     * 原件留在容器里等下次取件 (不静默, 也不丢件)。
     */
    fun deliverPending() {
        watchForeground()
        scope.launch { pickUp(discardInboxResidue = true) }
    }

    /**
     * 应用从后台回到前台时也取件: 分享扩展写件时本应用只能在后台, 用户切回来就该导入。
     * 只取件, 不清收件目录里的残留临时件 (原因见 [discardAbandonedTemps])。
     *
     * 触发用平台事件本身 ([UIApplicationWillEnterForegroundNotification]): Apple 文档说它只在整应用
     * 的聚合状态由后台进前台时发 (控制中心/来电横幅这类短暂失活不发), 冷启动直接进前台也会发一次
     * (那时启动取件已经跑过, 再扫到的是空目录), 后台启动的进程之后再进前台同样会发。
     */
    private fun watchForeground() {
        if (foregroundWatching) return
        foregroundWatching = true
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) {
            scope.launch { pickUp(discardInboxResidue = false) }
        }
    }

    /**
     * 一次取件: 扫收件目录 → 逐件落应用内接收目录并投给导入链 → 对账清理接收目录。
     *
     * 串行的原因: 冷启动直接进前台时平台事件照发一次, 两次取件会撞上; 并发跑会把同一件收两遍
     * (投两次导入、删两次原件)。
     *
     * @param discardInboxResidue 是否顺带清掉收件目录里的残留临时件 (只有启动取件清, 原因见
     * [discardAbandonedTemps])
     */
    private suspend fun pickUp(discardInboxResidue: Boolean) = pickUpMutex.withLock {
        val inbox = inboxDirectory()
        val store = storeDirectory()
        if (inbox != null && store != null) {
            val entries = inboxEntries(inbox)
            if (discardInboxResidue) discardAbandonedTemps(entries)
            for (file in pendingFiles(entries)) {
                val staged = stage(file, store) ?: continue
                stagedInProcess += staged.absolutePath
                deliver(staged, source = file)
            }
        } else if (inbox == null) {
            notifyEntryUnavailable()
        } else {
            Toasters.get().toast("无法接收分享进来的文件：应用内接收目录不可用")
        }
        // 收件目录或接收目录不可用时也照样对账: 上一轮留下的未导入副本与本次无关
        if (store != null) discardUnimported(store)
    }

    /**
     * 容器内的收件目录; 容器取不到 (无 entitlement) 或路径不可用时返回 null。
     *
     * 目录不存在时创建 (扩展侧也会创建, 两边都做是为了"谁先跑都不出错")。
     */
    private fun inboxDirectory(): File? {
        val container = NSFileManager.defaultManager
            .containerURLForSecurityApplicationGroupIdentifier(APP_GROUP_ID) ?: return null
        val containerPath = container.path ?: return null
        return File(containerPath, INBOX_DIR_NAME).apply { mkdirs() }
    }

    /**
     * 应用内接收目录 (应用支持目录下的 ShareImports): 收件副本的落地位置, 也是导入链读到的位置。
     *
     * 取不到 (系统未返回应用支持目录) 或建不出来时返回 null, 由调用方给用户提示并按"本次不取件"
     * 处理 (原件留在容器里, 下次启动重试)。
     */
    private fun storeDirectory(): File? {
        val support = NSSearchPathForDirectoriesInDomains(
            directory = NSApplicationSupportDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        ).firstOrNull() as? String ?: return null
        val store = File(support, STORE_DIR_NAME)
        if (store.mkdirs()) return store
        AppLog.put("分享取件: 无法创建应用内接收目录 ${store.path}")
        return null
    }

    /** 收件目录里的一次性列出 (目录只扫一遍); 读不到按空处理, 不抛。 */
    private fun inboxEntries(inbox: File): List<File> = runCatching {
        inbox.listFiles()?.toList() ?: emptyList()
    }.onFailure {
        AppLog.put("分享扩展收件目录扫描失败\n${it.message}", it)
    }.getOrDefault(emptyList())

    /**
     * 待收文件: 跳过子目录与隐藏项 —— 写件侧的临时件与拷贝半成品都是隐藏名;
     * 分享的文件本身以点开头时同样是隐藏项, 也不在此列 (不投递)。
     */
    private fun pendingFiles(entries: List<File>): List<File> =
        entries.filter { it.isFile && !it.name.startsWith(".") }

    /**
     * 清掉收件目录里的残留临时件: 写件侧与 [stage] 都是"先拷到 [tempNameFor] 的临时名, 成功后再
     * 原子改名", 拷贝中途进程被系统杀掉就停在临时名上 —— 那个名字不会再改成正式名, 也没人投递它。
     *
     * 只在启动取件调 ([pickUp] 的 [discardInboxResidue]), 不在回到前台的取件调: 临时名本身分不出
     * "残留"与"扩展正在写", 而扩展整个拷贝期间都停在分享面板上 —— 用户可以在拷贝没完时切走再切回
     * 本应用, 那时清就等于删掉扩展还在写的目标 (扩展改名失败、这一次分享丢掉)。启动取件也可能落在
     * 扩展拷贝过程中 (系统按后台任务唤起进程同样走 didFinishLaunching 这条启动链), 只是它不随用户
     * 每次切回应用发生。
     *
     * 只删这种名字: 以点开头但结尾不是 [TEMP_SUFFIX] 的项, 是用户分享的文件本身就以点开头,
     * 删掉就是丢件。
     */
    private fun discardAbandonedTemps(entries: List<File>) {
        for (file in entries) {
            if (!file.isFile || !file.isAbandonedTemp()) continue
            if (file.delete()) AppLog.put("分享取件清理残留临时件: ${file.path}")
            else AppLog.put("分享取件清理残留临时件失败: ${file.path}")
        }
    }

    /** 隐藏临时件的名字形态 (见 [tempNameFor])。 */
    private fun File.isAbandonedTemp(): Boolean =
        name.startsWith(".") && name.endsWith(TEMP_SUFFIX)

    /**
     * 收一件: 复制进应用内接收目录, 返回落地副本; 复制失败返回 null (原件留在容器里, 下次启动重试)。
     *
     * 先写隐藏的临时名再原子改名: 复制中途失败既不会把半截副本留给导入链 (半截文件会被当完整
     * 书籍导入), 也不会先毁掉接收目录里的同名旧副本。同名副本按"重复导入同名文件即替换"的
     * 既有导入语义覆盖。
     */
    private fun stage(source: File, store: File): File? {
        val dest = File(store, source.name)
        val temp = File(store, tempNameFor(source.name))
        return runCatching {
            temp.delete()
            source.copyTo(temp, overwrite = true)
            check(temp.renameTo(dest)) { "无法落到接收目录: ${dest.path}" }
            dest
        }.onFailure {
            temp.delete()
            AppLog.put("分享扩展取件失败: ${source.name}\n${it.message}", it)
            Toasters.get().toast("无法接收分享进来的文件：${source.name}\n${it.message}")
        }.getOrNull()
    }

    /** 落盘临时件的名字形态: 原名前加点、后加 [TEMP_SUFFIX] (与写件侧 `deliver` 同一套)。 */
    private fun tempNameFor(name: String): String = ".$name$TEMP_SUFFIX"

    /**
     * 投一件给既有导入链, 投递成功后删掉容器里的原件 (副本已落应用内接收目录, 删原件不影响导入链
     * 稍后读取); 投递失败保留原件 (下次启动重试) 并给用户可见提示。
     */
    private fun deliver(staged: File, source: File) {
        if (!handleIosLaunchRequest("file://${staged.absolutePath}")) {
            AppLog.put("分享扩展取件投递失败: ${staged.name}")
            Toasters.get().toast("分享进来的文件无法交给导入流程：${staged.name}")
            return
        }
        if (!source.delete()) AppLog.put("分享扩展取件后清理失败: ${source.path}")
    }

    /**
     * 对账清理接收目录: 本进程投递过的副本 [stagedInProcess] 不动, 被书架书籍引用的保留,
     * 其余删掉。
     *
     * 保留判据是本进程投递过的而不是本次刚收下的: 回到前台会再取一次件, 而导入链读这份副本、
     * 视频页放这份副本都发生在投递之后 —— 按本次判据会在下一次回到前台时删掉还在用的副本。
     * 用户没确认导入的、格式不支持的、只播一次的分享视频都落在"其余"里, 最晚在下次冷启动被清掉。
     * 书架读不出来时放弃本次清理: 宁可不删, 也不删错。
     */
    private suspend fun discardUnimported(store: File) {
        val files = runCatching { store.listFiles()?.filter { it.isFile } ?: emptyList() }
            .onFailure { AppLog.put("分享取件: 接收目录扫描失败\n${it.message}", it) }
            .getOrDefault(emptyList())
        if (files.isEmpty()) return
        val bookDao = AppDbProviders.get().bookDao
        for (file in files) {
            if (file.absolutePath in stagedInProcess) continue
            val referenced = try {
                bookDao.getBook("file://${file.absolutePath}") != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("分享取件: 读取书架失败, 本次不清理\n${e.message}", e)
                return
            }
            if (referenced) continue
            if (file.delete()) AppLog.put("分享取件清理未导入副本: ${file.path}")
            else AppLog.put("分享取件清理未导入副本失败: ${file.path}")
        }
    }

    /**
     * 容器取不到时给用户一次明确说明: 这一条正是"用户自签未带该能力"的产物所走的路径。
     *
     * 启动早期窗口还没创建, 此刻提示会退化成系统日志 —— 提示挂到"应用进入活动态"那一次;
     * 那一次拿不到顶层 UI 或已有别的弹窗时不当它提示过, 下次启动再试 (最多每次安装提示一次)。
     * 回到前台的取件会反复走到这里, 故本进程只判定并挂提示一次 (容器能力由签名固定)。
     */
    private fun notifyEntryUnavailable() {
        if (entryUnavailableHandled) return
        entryUnavailableHandled = true
        AppLog.put("分享取件: App Group 容器取不到, 分享进来的文件无法接收 (签名不含共享容器)")
        if (PreferenceProviders.get().getBoolean(UNAVAILABLE_NOTIFIED_KEY, false)) return
        unavailableNotifier = NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) {
            // 提示通道拿不到顶层 UI 或已有模态时会退化成日志, 这种情形不落记忆键
            val host = topMostViewController()
            val canPresent = host != null && host.presentedViewController == null
            if (canPresent && !PreferenceProviders.get().getBoolean(UNAVAILABLE_NOTIFIED_KEY, false)) {
                PreferenceProviders.get().putBoolean(UNAVAILABLE_NOTIFIED_KEY, true)
                Toasters.get().toast(SHARE_ENTRY_UNAVAILABLE)
            }
            unavailableNotifier?.let {
                NSNotificationCenter.defaultCenter.removeObserver(it)
            }
            unavailableNotifier = null
        }
    }
}
