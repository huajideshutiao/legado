package io.legado.app.ui

import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.fileBook.FileBookProviders
import io.legado.app.utils.File
import io.legado.app.utils.inputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSFileManager

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
 * # 取件为什么要"先搬进沙盒"再投递 (而不是把容器路径直接投给导入链)
 * 1. 容器路径不在 App 沙盒内, 也不在用户经"文件"面板授权的目录里 →
 *    iOS 的授权层 (`IosSecurityScopedStorage.withAccess` / `withLocalDirAccess`)
 *    会直接判"没有访问权限"并抛错, 导入链读不到;
 * 2. 投给导入链的路径要活到用户在上架页确认那一刻, 所以不能"投完就删"。
 *    搬进沙盒 (落盘复用已有的 [FileBookProviders] `saveBookFile`, 即 `{filesDir}/books/<原文件名>`)
 *    正好一次解决两件事: 容器里的原件当场清掉 (下次启动不会再导一遍), 沙盒副本供导入链长期使用。
 *    读容器时**不**经 `withLocalDirAccess`: 那层授权是给"用户选的容器外目录"用的,
 *    对自己的共享容器只会误判成越权。
 *
 * # 生效前提 (未签名产物上不会生效, 且不会崩)
 * App Group 靠 entitlement 落地 (`iosApp/iosApp.entitlements` 与
 * `ShareExtension/ShareExtension.entitlements`), 而本仓 CI 产物是**未签名 ipa**
 * (`CODE_SIGNING_ALLOWED=NO`, 与 iCloud 容器同样原因) → [NSFileManager]
 * `containerURLForSecurityApplicationGroupIdentifier` 恒返回 null → [deliverPending] 安静退出。
 * 签名 (侧载工具重签 / 正式签名) 且 App ID 勾上 App Groups 后才真正生效。
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

    /**
     * 取件扫描跑在 IO 线程: didFinishLaunching 里做同步文件拷贝会拖住启动
     * (分享进来的 epub/视频可能几十上百 MB), 而投递口 [handleIosLaunchRequest]
     * 只是往 [io.legado.app.ui.root.LaunchRequestBus] 的无限队列里投一条,
     * 队列保留订阅前的请求, 晚到不影响消费。
     */
    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    /**
     * 启动路径调用一次 (见 `IosProviderRegistry.registerIosProviders`):
     * 把收件目录里当时存在的文件逐个取件; 容器取不到 (未签名产物) 直接安静返回。
     *
     * 只处理"当时在容器里"的文件 —— 本次已取走的会被清掉, 所以下次启动不会重复导入。
     */
    fun deliverPending() {
        scope.launch {
            val inbox = inboxDirectory() ?: return@launch
            for (file in pendingFiles(inbox)) deliver(file)
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

    /** 收件目录里的待取文件 (跳过子目录与隐藏文件); 读不到按空处理, 不抛。 */
    private fun pendingFiles(inbox: File): List<File> = runCatching {
        inbox.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
    }.onFailure {
        AppLog.put("分享扩展收件目录扫描失败\n${it.message}", it)
    }.getOrDefault(emptyList())

    /**
     * 取一件: 复制进沙盒 → 交既有请求链 → 清掉容器里的原件。
     *
     * 复制失败 (磁盘满等) 或投递失败时**保留**原件并记日志, 下次启动重试;
     * 投递成功后才删 —— 投递的是沙盒副本, 删原件不会影响导入页稍后读取。
     */
    private fun deliver(file: File) {
        val ref = runCatching {
            // 落盘复用各端 FileBookAccessor 的既有实现 (iOS 实为 {filesDir}/books/<原文件名>,
            // 返回 "file://" + 绝对路径), 不另写一份拷贝
            FileBookProviders.get().saveBookFile(file.inputStream(), file.name)
        }.onFailure {
            AppLog.put("分享扩展取件失败: ${file.name}\n${it.message}", it)
        }.getOrNull() ?: return

        if (!handleIosLaunchRequest(ref)) {
            AppLog.put("分享扩展取件投递失败: ${file.name}")
            return
        }

        if (!file.delete()) AppLog.put("分享扩展取件后清理失败: ${file.path}")
    }
}
