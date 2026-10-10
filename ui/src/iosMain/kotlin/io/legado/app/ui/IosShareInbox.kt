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
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplicationDidBecomeActiveNotification

/**
 * iOS 分享扩展取件: 扫 App Group 共享容器里的收件目录, 把文件交给既有的外部请求链。
 *
 * # 为什么需要它
 * 分享扩展**不能拉起宿主 App** (扩展进程里没有通往宿主的 openURL 通道, Apple 限制),
 * 唯一交付通道是"扩展写共享容器 → 宿主下次启动取件": 写件侧是
 * `iosApp/ShareExtension/ShareViewController.swift` 的 `deliver`, 取件侧就是本对象。
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
 * - 容器里的原件在副本落盘成功后立即删掉 (下次启动不会重复收件);
 * - 接收目录里的副本在每次取件末尾对账: 本次刚收下的不动, 被书架书籍引用 (书籍地址即该文件)
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
     */
    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    /** 容器不可用的提示观察者 (等应用进入活动态再提示, 见 notifyEntryUnavailable)。 */
    @Volatile
    private var unavailableNotifier: Any? = null

    /**
     * 启动路径调用一次 (见 `IosProviderRegistry.registerIosProviders`):
     * 把收件目录里当时存在的文件逐个收进应用内接收目录并交给既有导入链, 末尾对账清理接收目录。
     *
     * 只处理"当时在容器里"的文件, 本次收下的原件随后即被清掉, 下次启动不会重复收件。
     * 容器取不到 (无 entitlement) 或接收目录不可用时给用户可见说明并按"本次不取件"处理,
     * 原件留在容器里等下次启动 (不静默, 也不丢件)。
     */
    fun deliverPending() {
        scope.launch {
            val inbox = inboxDirectory()
            val store = storeDirectory()
            val received = mutableListOf<File>()
            if (inbox != null && store != null) {
                for (file in pendingFiles(inbox)) {
                    val staged = stage(file, store) ?: continue
                    received += staged
                    deliver(staged, source = file)
                }
            } else if (inbox == null) {
                notifyEntryUnavailable()
            } else {
                Toasters.get().toast("无法接收分享进来的文件：应用内接收目录不可用")
            }
            // 收件目录或接收目录不可用时也照样对账: 上一轮留下的未导入副本与本次无关
            store?.let { discardUnimported(it, keep = received) }
        }
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

    /** 收件目录里的待收文件 (跳过子目录与隐藏项); 读不到按空处理, 不抛。 */
    private fun pendingFiles(inbox: File): List<File> = runCatching {
        inbox.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
    }.onFailure {
        AppLog.put("分享扩展收件目录扫描失败\n${it.message}", it)
    }.getOrDefault(emptyList())

    /**
     * 收一件: 复制进应用内接收目录, 返回落地副本; 复制失败返回 null (原件留在容器里, 下次启动重试)。
     *
     * 先写隐藏的临时名再原子改名: 复制中途失败既不会把半截副本留给导入链 (半截文件会被当完整
     * 书籍导入), 也不会先毁掉接收目录里的同名旧副本。同名副本按"重复导入同名文件即替换"的
     * 既有导入语义覆盖。
     */
    private fun stage(source: File, store: File): File? {
        val dest = File(store, source.name)
        val temp = File(store, ".${source.name}.part")
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
     * 对账清理接收目录: [keep] (本次刚收下的) 不动, 被书架书籍引用的保留, 其余删掉。
     *
     * 用户没确认导入的、格式不支持的、只播一次的分享视频都落在"其余"里, 不在本地长期留存。
     * 书架读不出来时放弃本次清理: 宁可不删, 也不删错。
     */
    private suspend fun discardUnimported(store: File, keep: List<File>) {
        val files = runCatching { store.listFiles()?.filter { it.isFile } ?: emptyList() }
            .onFailure { AppLog.put("分享取件: 接收目录扫描失败\n${it.message}", it) }
            .getOrDefault(emptyList())
        if (files.isEmpty()) return
        val keeping = keep.mapTo(HashSet()) { it.absolutePath }
        val bookDao = AppDbProviders.get().bookDao
        for (file in files) {
            if (file.absolutePath in keeping) continue
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
     */
    private fun notifyEntryUnavailable() {
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
