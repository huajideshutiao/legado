package io.legado.app.help.media

import android.annotation.SuppressLint
import android.net.wifi.WifiManager
import android.os.PowerManager
import splitties.systemservices.powerManager
import splitties.systemservices.wifiManager

/**
 * WakeLock + WifiLock 配对管理,均关闭引用计数以便重复 acquire/release。
 *
 * [enabled] 为 false 时 acquire / release 都直接返回,等价于"未启用唤醒锁"。
 */
class MediaPlaybackLock(
    private val tag: String,
    private val enabled: Boolean
) {

    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            .apply { setReferenceCounted(false) }
    }

    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag)
            ?.apply { setReferenceCounted(false) }
    }

    @SuppressLint("WakelockTimeout")
    fun acquire() {
        if (!enabled) return
        if (!wakeLock.isHeld) wakeLock.acquire()
        wifiLock?.takeIf { !it.isHeld }?.acquire()
    }

    fun release() {
        if (!enabled) return
        if (wakeLock.isHeld) wakeLock.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }
}
