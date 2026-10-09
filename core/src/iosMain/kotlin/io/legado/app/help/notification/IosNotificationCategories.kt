@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.notification

import io.legado.app.ui.compose.platform.syncGetString
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionCustomDismissAction
import platform.UserNotifications.UNNotificationCategoryOptionNone

/**
 * iOS 通知分类 (带自定义动作的通知必须先注册分类再发送, 否则通知上不出现动作按钮)。
 *
 * 定义在 core 而非 ui: 分类标识两侧都要用 —— 注册/发送/动作处理在 ui
 * (IosNotificationPublisher / IosNotificationService / IosNotificationDelegate),
 * 更新目录进度通知在 core ([IosNotificationProgress] 给通知内容挂分类);
 * 而 `UNUserNotificationCenter.setNotificationCategories` 是整体替换, 全集只能有一处定义。
 *
 * 注册见 `IosNotificationPublisher.installCategories`, 由
 * `io.legado.app.help.config.registerIosProviders` 在启动早期调用。
 *
 * 每个分类对应的通知栏形态逐条对照 app 端原通知 (见各常量的 KDoc)。
 */
object IosNotificationCategories {

    /** 导出通知分类 (进行中态挂 [ExportCancel] 动作)。 */
    const val Export = "legado.export"

    /** 导出通知的"取消"动作标识 (对照原版导出通知上的 IntentAction.stop 动作)。 */
    const val ExportCancel = "legado.export.cancel"

    /** 更新目录/强制刷新进度通知分类 (对照原版 `UpdateBookService` / `AndroidUpdateBookCallback` 的通知)。 */
    const val UpdateBook = "legado.update_book"

    /** 更新目录通知的"取消"动作标识 (对照原版该通知上的 IntentAction.stop 动作)。 */
    const val UpdateBookCancel = "legado.update_book.cancel"

    /** 缓存书籍进度通知分类 (对照原版 `CacheBookService` 的通知)。 */
    const val CacheBook = "legado.cache_book"

    /** 缓存书籍通知的"取消"动作标识 (对照原版该通知上的 IntentAction.stop 动作)。 */
    const val CacheBookCancel = "legado.cache_book.cancel"

    /** 书源校验进度通知分类 (对照原版 `CheckSourceService` 的通知)。 */
    const val CheckSource = "legado.check_source"

    /** 书源校验通知的"取消"动作标识 (对照原版该通知上的 IntentAction.stop 动作)。 */
    const val CheckSourceCancel = "legado.check_source.cancel"

    /** 全部分类 ([UNUserNotificationCenter.setNotificationCategories] 为整体替换, 一次提交全集)。 */
    fun all(): Set<UNNotificationCategory> = setOf(
        // 导出: 清掉通知同样停导出 (对照原版 setDeleteIntent)
        cancelCategory(Export, ExportCancel, UNNotificationCategoryOptionCustomDismissAction),
        // 更新目录: 原版该通知未挂 setDeleteIntent, 故清通知不触发取消
        cancelCategory(UpdateBook, UpdateBookCancel, UNNotificationCategoryOptionNone),
        cancelCategory(CacheBook, CacheBookCancel, UNNotificationCategoryOptionNone),
        cancelCategory(CheckSource, CheckSourceCancel, UNNotificationCategoryOptionNone),
    )

    /**
     * 单动作分类: 一个"取消"按钮 (动作标题取 app 端同一 key `cancel`)。
     *
     * @param options [UNNotificationCategoryOptionCustomDismissAction] 仅在该分类对应通知
     *   原版挂了 setDeleteIntent 时才传 (未挂则不接清通知响应, 与 app 端"清掉通知无事发生"一致)
     */
    private fun cancelCategory(
        identifier: String,
        actionIdentifier: String,
        options: ULong,
    ): UNNotificationCategory = UNNotificationCategory.categoryWithIdentifier(
        identifier = identifier,
        actions = listOf(
            // 不带 Foreground: 取消在后台完成即可, 与 app 端动作直达 Service (不拉 Activity) 同语义
            UNNotificationAction.actionWithIdentifier(
                identifier = actionIdentifier,
                title = syncGetString("cancel"),
                options = UNNotificationActionOptionNone,
            ),
        ),
        intentIdentifiers = emptyList(),
        options = options,
    )
}
