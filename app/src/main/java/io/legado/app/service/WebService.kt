package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.help.config.AppConfig
import io.legado.app.help.setLiveOngoing
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.sendToClip
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import io.legado.app.web.WebServerManager
import splitties.init.appCtx
import splitties.systemservices.powerManager
import splitties.systemservices.wifiManager

/**
 * Web 服务 Android Service 壳。
 *
 * # 下沉说明 (原 app 端 io.legado.app.service.WebService)
 * 服务器生命周期 (HttpServer + WebSocketServer 起/停 + IP 枚举 + isRun/hostAddress 状态)
 * 已下沉到 shared commonMain [WebServerManager] + [io.legado.app.web.JvmWebServerPlatform];
 * 本类只保留 Android Service 壳 (wakelock/wifiLock/前台通知/网络监听/Tile), 对齐原行为。
 *
 * # companion 委托
 * isRun / hostAddress 委托 [WebServerManager] (共享状态, 桌面端复用同一 object);
 * start/stop/serve 保留原 Service 启停入口 (Android Service 机制)。
 */
class WebService : BaseService() {

    companion object {
        val isRun: Boolean get() = WebServerManager.isRun

        val hostAddress: String get() = WebServerManager.hostAddress

        fun start(context: Context) {
            context.startService<WebService>()
        }

        fun startForeground(context: Context) {
            val intent = Intent(context, WebService::class.java)
            context.startForegroundServiceCompat(intent)
        }

        fun stop(context: Context) {
            context.stopService<WebService>()
        }

        fun serve() {
            appCtx.startService<WebService> {
                action = "serve"
            }
        }
    }

    private val useWakeLock = AppConfig.webServiceWakeLock
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:WebService")
            .apply {
                setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:WebService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private var notificationList = mutableListOf(appCtx.getString(R.string.service_starting))
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        upTile(true)
        // 对齐原版 onCreate 的 isRun = true (服务器起之前 UI 即显示运行中)
        WebServerManager.markRunning()
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 网络变化: 重新枚举 IP → 更新 WebServerManager 地址 → 刷新前台通知
            // (原 app 端行为: 不重启 HttpServer, 仅更新 notificationList + hostAddress + postEvent)
            val addressList = NetworkUtils.getLocalIPAddress()
            val port = WebServerManager.getPort()
            val addresses = if (addressList.any()) {
                addressList.map { "http://${it.hostAddress}:$port" }
            } else {
                emptyList()
            }
            // 无可用 IP 时 hostAddress 置本地化文案 (对齐原版, 供通知/复制/EventBus 消费方显示)
            val unavailableText = getString(R.string.network_connection_unavailable)
            WebServerManager.updateAddresses(addresses, unavailableText)
            notificationList.clear()
            if (addresses.isNotEmpty()) {
                notificationList.addAll(addresses)
            } else {
                notificationList.add(unavailableText)
            }
            startForegroundNotification()
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> stopSelf()
            "copyHostAddress" -> sendToClip(WebServerManager.hostAddress)
            "serve" -> if (useWakeLock) {
                wakeLock.acquire()
                wifiLock?.acquire()
            }

            else -> upWebServer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        networkChangedListener.unRegister()
        // 服务器停止 + isRun/hostAddress 清空 + postEvent 委托 WebServerManager
        WebServerManager.stop()
        upTile(false)
    }

    private fun upWebServer() {
        // 服务器起/停 + IP 枚举 + isRun/hostAddress 状态 + postEvent 委托 WebServerManager
        val result = WebServerManager.startWithResult()
        val addresses = result.addresses
        if (addresses.isEmpty()) {
            // 对齐原版: 绑定异常 toast e.localizedMessage, 无可用 IP 才提示 no ip address
            toastOnUi(result.errorMsg ?: "web service cant start, no ip address")
            stopSelf()
        } else {
            notificationList.clear()
            notificationList.addAll(addresses)
            startForegroundNotification()
        }
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setContentTitle(getString(R.string.web_service))
            .setContentText(notificationList.joinToString("\n"))
            .setContentIntent(
                servicePendingIntent<WebService>("copyHostAddress")
            )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<WebService>(IntentAction.stop)
        )
        // Web 服务为常驻运行态而非确定进度的"旅程", 故只请求实时进行中胶囊;
        // 系统若判定不可提升会降级为普通常驻通知, 无副作用。
        builder.setLiveOngoing()
        val notification = builder.build()
        startForeground(NotificationId.WebService, notification)
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun upTile(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            kotlin.runCatching {
                startService<WebTileService> {
                    action = if (active) {
                        IntentAction.start
                    } else {
                        IntentAction.stop
                    }
                }
            }

        }
    }
}
