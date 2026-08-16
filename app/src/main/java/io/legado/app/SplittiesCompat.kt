package io.legado.app

import android.app.DownloadManager
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.ClipboardManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.view.View

/**
 * splitties (com.louiscad.splitties, 最后 release 2021 已停滞) 的本地替代。
 * 各属性语义与 splitties 逐项等价:
 * - `splitties.init.appCtx` → 全仓已直接内连为 `App.instance` (App.onCreate 早期赋值), 无需本文件
 * - `splitties.systemservices.*` → **无接收者的顶层属性** (内部走 App.instance.getSystemService,
 *   与原版 splitties 基于 appCtx 的声明一致, 可在任意类/object/顶层函数内裸调用)
 * - `splitties.views.topPadding/bottomPadding` → View padding 读写属性 (保留扩展)
 */
val audioManager: AudioManager
    get() = App.instance.getSystemService(AudioManager::class.java)

val clipboardManager: ClipboardManager
    get() = App.instance.getSystemService(ClipboardManager::class.java)

val connectivityManager: ConnectivityManager
    get() = App.instance.getSystemService(ConnectivityManager::class.java)

val downloadManager: DownloadManager
    get() = App.instance.getSystemService(DownloadManager::class.java)

val notificationManager: NotificationManager
    get() = App.instance.getSystemService(NotificationManager::class.java)

val powerManager: PowerManager
    get() = App.instance.getSystemService(PowerManager::class.java)

val telephonyManager: TelephonyManager
    get() = App.instance.getSystemService(TelephonyManager::class.java)

val uiModeManager: UiModeManager
    get() = App.instance.getSystemService(UiModeManager::class.java)

val wifiManager: WifiManager
    get() = App.instance.getSystemService(WifiManager::class.java)

var View.topPadding: Int
    get() = paddingTop
    set(value) = setPadding(paddingLeft, value, paddingRight, paddingBottom)

var View.bottomPadding: Int
    get() = paddingBottom
    set(value) = setPadding(paddingLeft, paddingTop, paddingRight, value)
