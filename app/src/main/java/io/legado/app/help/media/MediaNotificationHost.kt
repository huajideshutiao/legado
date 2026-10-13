package io.legado.app.help.media

import android.app.Service
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import io.legado.app.help.coroutine.Coroutine

/**
 * 前台媒体通知宿主 (朗读 / 音频共用)。
 *
 * 两条通知此前各自实现同一套逻辑: 快照去重 → 串行刷新 → 失败只记日志 → 前台启动失败即停服务。
 * 收口到本类后, 差异只剩"通知内容怎么拼"(调用方的 [build]) 与"哪些字段变了才需要重建"([dedupKey])。
 *
 * @param context        上下文 (取通知管理器)
 * @param notificationId 通知 id ([io.legado.app.constant.NotificationId])
 * @param async          后台执行器 (通常传 `BaseService.execute`)
 * @param build          拼装通知 (每次重建时调用)
 * @param dedupKey       去重判据: 内容与封面身份全等时不重建 (返回相同列表即视为无变化)
 * @param errorLog       构建/刷新失败时的日志 (文案由调用方给, 带上各自的业务名与异常本身)
 * @param onForegroundFailure 前台启动失败的收尾 (通知创建不出来时服务必须收场, 否则崩溃)
 */
class MediaNotificationHost(
    private val context: Context,
    private val notificationId: Int,
    private val async: (block: suspend () -> Unit) -> Coroutine<*>,
    private val build: () -> androidx.core.app.NotificationCompat.Builder,
    private val dedupKey: () -> List<Any?>,
    private val errorLog: (String, Throwable?) -> Unit,
    private val onForegroundFailure: () -> Unit,
) {

    private var lastKey: List<Any?>? = null
    private var job: Coroutine<*>? = null

    /** 内容或封面变了才重建通知; 重建按"后一次取代前一次"串行, 不并发下发。 */
    fun refresh() {
        val key = dedupKey()
        if (key == lastKey) return
        lastKey = key
        job?.cancel()
        job = async {
            try {
                NotificationManagerCompat.from(context).notify(notificationId, build().build())
            } catch (e: Exception) {
                errorLog(e.localizedMessage.orEmpty(), e)
            }
        }
    }

    /** 以本通知挂前台服务 (失败即走调用方给的收场逻辑)。 */
    fun startForeground(service: Service) {
        async {
            try {
                service.startForeground(notificationId, build().build())
            } catch (e: Exception) {
                errorLog(e.localizedMessage.orEmpty(), e)
                onForegroundFailure()
            }
        }
    }

    /** 服务销毁时: 等在飞的通知任务收尾后撤掉通知。 */
    fun cancelOnDestroy() {
        job?.invokeOnCompletion {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }
}
