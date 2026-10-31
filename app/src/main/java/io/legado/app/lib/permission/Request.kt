package io.legado.app.lib.permission

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.legado.app.App
import io.legado.app.powerManager
import io.legado.app.utils.startActivity
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Suppress("MemberVisibilityCanBePrivate")
internal class Request : OnRequestPermissionsResultCallback {

    /** 本次请求的唯一标识: 权限页回传按它绑定原请求。 */
    override val id: Long = nextRequestId.incrementAndGet()

    private var requestCode: Int = TYPE_REQUEST_PERMISSION
    private var permissions: ArrayList<String> = ArrayList()
    private var grantedCallback: OnPermissionsGrantedCallback? = null
    private var deniedCallback: OnPermissionsDeniedCallback? = null
    private var errorCallback: OnErrorCallback? = null
    private var rationale: CharSequence? = null

    /** 本次请求是否已终结: 成功/拒绝/错误/取消只交付一次。 */
    private val finished = AtomicBoolean(false)

    /** 消费方已撤销: 回传不再向消费方交付。 */
    private val cancelled = AtomicBoolean(false)

    /** 准入资格 (单一原子量): 撤销与「这次调用是否发出」在此判定先后。 */
    private val admission = AtomicInteger(ADMISSION_IDLE)

    /** 已真正拉起系统权限调用 (此前只是分配了当前位): 只有发出的调用才存在真实回传占用。 */
    val isStarted: Boolean get() = admission.get() == ADMISSION_LIVE

    /**
     * 准入: 与撤销同一个原子判定点, 撤销是唯一否决点。
     * 准入先到 = 调用正在/已经发出, 回传占用保到真实返回; 撤销先到 = 本次调用零副作用。
     */
    private fun admit(): Boolean = admission.compareAndSet(ADMISSION_IDLE, ADMISSION_LIVE)

    private val deniedPermissions: Array<String>?
        get() {
            return getDeniedPermissions(this.permissions.toTypedArray())
        }

    fun addPermissions(vararg permissions: String) {
        this.permissions.addAll(listOf(*permissions))
    }

    fun setOnGrantedCallback(callback: OnPermissionsGrantedCallback) {
        grantedCallback = callback
    }

    fun setOnDeniedCallback(callback: OnPermissionsDeniedCallback) {
        deniedCallback = callback
    }

    fun setOnErrorCallBack(callback: OnErrorCallback) {
        errorCallback = callback
    }

    fun setRationale(@StringRes resId: Int) {
        rationale = App.instance.getString(resId)
    }

    fun setRationale(rationale: CharSequence) {
        this.rationale = rationale
    }

    /**
     * 准入并发出权限调用; 返回本次是否已结清 (立即终态交付, 或已实际发出调用)。
     * 返回 false 仅当准入被否决 (撤销先到): 查询/注册/拉起一律未发生, 零副作用,
     * 由调用方让出当前位并推进。
     */
    @SuppressLint("ObsoleteSdkInt")
    fun start(): Boolean {
        if (!admit()) return false
        RequestPlugins.setOnRequestPermissionsCallback(this)

        // 准入已赢得撤销: 以下权限查询/页面拉起都在已准入区内执行; 页面启动是外部调用,
        // 消费回调在保护范围外执行 (异常原样上抛)
        val deniedPermissions = try {
            this.deniedPermissions
        } catch (e: Exception) {
            onError(e)
            return true
        }
        val rationale = this.rationale
        if (deniedPermissions == null) {
            onPermissionsGranted()
            return true
        }
        if (rationale == null) {
            onPermissionsDenied(deniedPermissions)
            return true
        }
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                toSetting()
            } else {
                if (deniedPermissions.contains(Permissions.MANAGE_EXTERNAL_STORAGE)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        toManageFileSetting(deniedPermissions)
                    }
                } else if (deniedPermissions.contains(Permissions.POST_NOTIFICATIONS)) {
                    toNotificationSetting(deniedPermissions)
                } else if (deniedPermissions.contains(Permissions.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        toIgnoreBatterySetting(deniedPermissions)
                    }
                } else if (deniedPermissions.isNotEmpty()) {
                    App.instance.startActivity<PermissionActivity> {
                        putExtra(PermissionActivity.KEY_RATIONALE, rationale)
                        putExtra(PermissionActivity.KEY_INPUT_REQUEST_TYPE, TYPE_REQUEST_PERMISSION)
                        putExtra(PermissionActivity.KEY_INPUT_PERMISSIONS_CODE, requestCode)
            putExtra(PermissionActivity.KEY_INPUT_REQUEST_ID, id)
                        putExtra(PermissionActivity.KEY_INPUT_PERMISSIONS, deniedPermissions)
                    }
                }
            }
        } catch (e: Exception) {
            // 启动权限页失败也要终结本条请求, 不能让发起方一直等
            onError(e)
        }
        return true
    }

    /** 弃全部消费侧回调 (granted/denied/error); 回传占用由 RequestManager 维持到真实返回。 */
    fun clear() {
        // 撤销是唯一否决点: 只否决尚未准入的调用; 已准入的调用不可撤回, 只撤消费侧
        admission.compareAndSet(ADMISSION_IDLE, ADMISSION_VETOED)
        cancelled.set(true)
        grantedCallback = null
        deniedCallback = null
        errorCallback = null
    }

    fun getDeniedPermissions(permissions: Array<String>): Array<String>? {
        val deniedPermissionList = ArrayList<String>()
        for (permission in permissions) {
            when (permission) {
                Permissions.POST_NOTIFICATIONS -> {
                    if (!NotificationManagerCompat.from(App.instance).areNotificationsEnabled()) {
                        deniedPermissionList.add(permission)
                    }
                }

                Permissions.MANAGE_EXTERNAL_STORAGE -> {
                    if (Permissions.isManageExternalStorage()) {
                        if (!Environment.isExternalStorageManager()) {
                            deniedPermissionList.add(permission)
                        }
                    }
                }

                Permissions.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> {
                    if (!powerManager.isIgnoringBatteryOptimizations(App.instance.packageName)) {
                        deniedPermissionList.add(permission)
                    }
                }

                else -> {
                    if (
                        ContextCompat.checkSelfPermission(App.instance, permission)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        deniedPermissionList.add(permission)
                    }
                }
            }
        }
        val size = deniedPermissionList.size
        if (size > 0) {
            return deniedPermissionList.toTypedArray()
        }
        return null
    }

    private fun onPermissionsGranted() {
        if (!finishOnce()) return
        try {
            // 交付前读已同步撤销资格: clear 任意线程置位后, 回执不再向消费方交付;
            // manager 收口放 finally: 消费回调抛错也收口 (异常原样上抛, 不吞)
            if (!cancelled.get()) grantedCallback?.onPermissionsGranted()
        } finally {
            notifyManagerGranted()
        }
    }

    private fun onPermissionsDenied(deniedPermissions: Array<String>) {
        if (!finishOnce()) return
        try {
            if (!cancelled.get()) deniedCallback?.onPermissionsDenied(deniedPermissions)
        } finally {
            notifyManagerDenied(deniedPermissions)
        }
    }

    /** 终结一次; 已终结的重复回传直接丢弃。 */
    private fun finishOnce(): Boolean = finished.compareAndSet(false, true)

    /** 只有当前正在等待的请求才能推进队列, 旧请求的迟到回传不得清掉下一条。 */
    private fun notifyManagerGranted() {
        if (RequestPlugins.sRequestCallback === this) {
            RequestPlugins.sResultCallback?.onPermissionsGranted()
        }
    }

    private fun notifyManagerDenied(deniedPermissions: Array<String>) {
        if (RequestPlugins.sRequestCallback === this) {
            RequestPlugins.sResultCallback?.onPermissionsDenied(deniedPermissions)
        }
    }

    private fun notifyManagerError(e: Exception) {
        if (RequestPlugins.sRequestCallback === this) {
            RequestPlugins.sResultCallback?.onError(e)
        }
    }

    private fun toSetting() {
        App.instance.startActivity<PermissionActivity> {
            putExtra(PermissionActivity.KEY_RATIONALE, rationale)
            putExtra(PermissionActivity.KEY_INPUT_REQUEST_TYPE, TYPE_REQUEST_SETTING)
            putExtra(PermissionActivity.KEY_INPUT_REQUEST_ID, id)
        }
    }

    private fun toManageFileSetting(deniedPermissions: Array<String>) {
        App.instance.startActivity<PermissionActivity> {
            putExtra(PermissionActivity.KEY_RATIONALE, rationale)
            putExtra(PermissionActivity.KEY_INPUT_REQUEST_TYPE, TYPE_MANAGE_ALL_FILES_ACCESS)
            putExtra(PermissionActivity.KEY_INPUT_PERMISSIONS_CODE, requestCode)
            putExtra(PermissionActivity.KEY_INPUT_REQUEST_ID, id)
            putExtra(PermissionActivity.KEY_INPUT_PERMISSIONS, deniedPermissions)
        }
    }

    private fun toNotificationSetting(deniedPermissions: Array<String>) {
        App.instance.startActivity<PermissionActivity> {
            putExtra(PermissionActivity.KEY_RATIONALE, rationale)
            putExtra(PermissionActivity.KEY_INPUT_REQUEST_TYPE, TYPE_REQUEST_NOTIFICATIONS)
            putExtra(PermissionActivity.KEY_INPUT_PERMISSIONS_CODE, requestCode)
            putExtra(PermissionActivity.KEY_INPUT_REQUEST_ID, id)
            putExtra(PermissionActivity.KEY_INPUT_PERMISSIONS, deniedPermissions)
        }
    }

    private fun toIgnoreBatterySetting(deniedPermissions: Array<String>) {
        App.instance.startActivity<PermissionActivity> {
            putExtra(PermissionActivity.KEY_RATIONALE, rationale)
            putExtra(
                PermissionActivity.KEY_INPUT_REQUEST_TYPE,
                TYPE_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            )
            putExtra(PermissionActivity.KEY_INPUT_PERMISSIONS_CODE, requestCode)
            putExtra(PermissionActivity.KEY_INPUT_REQUEST_ID, id)
            putExtra(PermissionActivity.KEY_INPUT_PERMISSIONS, deniedPermissions)
        }
    }

    override fun onRequestPermissionsResult(
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        val deniedPermissions = getDeniedPermissions(permissions)
        if (deniedPermissions != null) {
            onPermissionsDenied(deniedPermissions)
        } else {
            onPermissionsGranted()
        }
    }

    override fun onSettingActivityResult() {
        val deniedPermissions = deniedPermissions
        if (deniedPermissions == null) {
            onPermissionsGranted()
        } else {
            onPermissionsDenied(deniedPermissions)
        }
    }

    override fun onError(e: Exception) {
        if (!finishOnce()) return
        try {
            if (!cancelled.get()) errorCallback?.onError(e)
        } finally {
            notifyManagerError(e)
        }
    }

    companion object {
        private val nextRequestId = AtomicLong(SecureRandom().nextLong() and Long.MAX_VALUE)

        /** 准入资格状态: 撤销与「这次调用是否发出」在这一个原子量上判定先后。 */
        private const val ADMISSION_IDLE = 0
        private const val ADMISSION_LIVE = 1
        private const val ADMISSION_VETOED = 2

        const val TYPE_REQUEST_PERMISSION = 1
        const val TYPE_REQUEST_SETTING = 2
        const val TYPE_MANAGE_ALL_FILES_ACCESS = 3
        const val TYPE_REQUEST_NOTIFICATIONS = 4
        const val TYPE_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 5
    }
}