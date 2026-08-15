package io.legado.app

import android.app.DownloadManager
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.ClipboardManager
import android.content.Context
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
 * - `splitties.systemservices.*` → Context 扩展 (getSystemService 直取, 与 splitties 实现一致)
 * - `splitties.views.topPadding/bottomPadding` → View padding 读写属性
 */
val Context.audioManager: AudioManager
    get() = getSystemService(AudioManager::class.java)

val Context.clipboardManager: ClipboardManager
    get() = getSystemService(ClipboardManager::class.java)

val Context.connectivityManager: ConnectivityManager
    get() = getSystemService(ConnectivityManager::class.java)

val Context.downloadManager: DownloadManager
    get() = getSystemService(DownloadManager::class.java)

val Context.notificationManager: NotificationManager
    get() = getSystemService(NotificationManager::class.java)

val Context.powerManager: PowerManager
    get() = getSystemService(PowerManager::class.java)

val Context.telephonyManager: TelephonyManager
    get() = getSystemService(TelephonyManager::class.java)

val Context.uiModeManager: UiModeManager
    get() = getSystemService(UiModeManager::class.java)

val Context.wifiManager: WifiManager
    get() = getSystemService(WifiManager::class.java)

var View.topPadding: Int
    get() = paddingTop
    set(value) = setPadding(paddingLeft, value, paddingRight, paddingBottom)

var View.bottomPadding: Int
    get() = paddingBottom
    set(value) = setPadding(paddingLeft, paddingTop, paddingRight, value)
