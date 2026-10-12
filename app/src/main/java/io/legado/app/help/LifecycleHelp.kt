package io.legado.app.help

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.legado.app.base.BaseService
import io.legado.app.model.fileBook.CbzFile
import io.legado.app.model.fileBook.EpubFile
import io.legado.app.model.fileBook.PdfFileShared
import io.legado.app.utils.LogUtils
import java.lang.ref.WeakReference

/**
 * Activity管理器,管理项目中Activity的状态
 */
@Suppress("unused")
object LifecycleHelp : Application.ActivityLifecycleCallbacks {

    private const val TAG = "LifecycleHelp"

    private val activities: MutableList<WeakReference<Activity>> = arrayListOf()
    private val services: MutableList<WeakReference<BaseService>> = arrayListOf()
    private var appFinishedListener: (() -> Unit)? = null

    val currentActivity: Activity?
        @Synchronized get() {
            for (i in activities.size - 1 downTo 0) {
                val activity = activities[i].get()
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    return activity
                }
            }
            return null
        }

    init {
        setOnAppFinishedListener {
            CbzFile.clear()
            EpubFile.clear()
            PdfFileShared.clear()
        }
    }

    fun activitySize(): Int {
        return activities.size
    }

    /**
     * 判断指定Activity是否存在
     *
     * 原版消费方是媒体键路由 (`MediaButtonReceiver` 按阅读页/音频页是否开着决定按键去向),
     * 本仓该分发已由无头会话分支取代, 故本仓无调用点。原版既有公开 API, 保留以对齐原版。
     */
    fun isExistActivity(activityClass: Class<*>): Boolean {
        activities.forEach { item ->
            if (item.get()?.javaClass == activityClass) {
                return true
            }
        }
        return false
    }

    /**
     * 关闭指定 activity(class)
     */
    fun finishActivity(vararg activityClasses: Class<*>) {
        val waitFinish = ArrayList<WeakReference<Activity>>()
        for (temp in activities) {
            for (activityClass in activityClasses) {
                if (temp.get()?.javaClass == activityClass) {
                    waitFinish.add(temp)
                    break
                }
            }
        }
        waitFinish.forEach {
            it.get()?.finish()
        }
    }

    fun setOnAppFinishedListener(appFinishedListener: (() -> Unit)) {
        this.appFinishedListener = appFinishedListener
    }

    override fun onActivityPaused(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onPause")
    }

    override fun onActivityResumed(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onResume")
    }

    override fun onActivityStarted(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onStart")
    }

    @Synchronized
    override fun onActivityDestroyed(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onDestroy")
        for (temp in activities) {
            if (temp.get() != null && temp.get() === activity) {
                activities.remove(temp)
                if (services.isEmpty() && activities.isEmpty()) {
                    onAppFinished()
                }
                break
            }
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        LogUtils.d(TAG, "${activity::class.simpleName} onSaveInstanceState")
    }

    override fun onActivityStopped(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onStop")
    }

    @Synchronized
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        LogUtils.d(TAG, "${activity::class.simpleName} onCreate")
        activities.add(WeakReference(activity))
    }

    @Synchronized
    fun onServiceCreate(service: BaseService) {
        LogUtils.d(TAG, "${service::class.simpleName} onCreate")
        services.add(WeakReference(service))
    }

    @Synchronized
    fun onServiceDestroy(service: BaseService) {
        LogUtils.d(TAG, "${service::class.simpleName} onDestroy")
        for (temp in services) {
            if (temp.get() != null && temp.get() === service) {
                services.remove(temp)
                if (services.isEmpty() && activities.isEmpty()) {
                    onAppFinished()
                }
                break
            }
        }
    }

    private fun onAppFinished() {
        appFinishedListener?.invoke()
    }
}
