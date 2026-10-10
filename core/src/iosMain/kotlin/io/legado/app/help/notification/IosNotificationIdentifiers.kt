package io.legado.app.help.notification

import io.legado.app.constant.NotificationId

/**
 * NotificationId → iOS 本地通知标识 (identifier) 的唯一换算点。
 *
 * UNUserNotificationCenter 用字符串 identifier 区分通知, Android/鸿蒙用 [NotificationId] 整数;
 * 本对象是两者的换算处, 由 core 的进度通知 ([IosNotificationProgress]) 与 :ui 的通知服务
 * (`IosNotificationService`) 共用, 保证同一通知 id 在 iOS 上始终是同一条通知
 * (覆盖式更新 / 精确取消), 不因发送方不同而分裂成两条。
 */
object IosNotificationIdentifiers {

    /** 通知 id 对应的本地通知标识。 */
    fun of(id: Int): String = "legado-$id"
}
