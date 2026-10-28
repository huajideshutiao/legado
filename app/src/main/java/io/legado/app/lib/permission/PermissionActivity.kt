package io.legado.app.lib.permission

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.ComposeDialog
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.i18n.androidAppString
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.utils.registerForActivityResult
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PermissionActivity : AppCompatActivity() {

    private var rationaleDialog: ComposeDialog? = null

    /** 本次权限页归属的请求 id (回传只交付给原请求)。 */
    private var requestId: Long = -1L

    private val settingActivityResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onRequestPermissionFinish()
        }
    private val settingActivityResultAwait =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult())
    private val requestPermissionResult =
        registerForActivityResult(ActivityResultContracts.RequestPermission())
    private val requestPermissionsResult =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())

    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getLongExtra(KEY_INPUT_REQUEST_ID, -1L)
        val rationale = intent.getStringExtra(KEY_RATIONALE)
        val permissions = intent.getStringArrayExtra(KEY_INPUT_PERMISSIONS)!!
        when (intent.getIntExtra(KEY_INPUT_REQUEST_TYPE, Request.TYPE_REQUEST_PERMISSION)) {
            //权限请求
            Request.TYPE_REQUEST_PERMISSION -> showSettingDialog(permissions, rationale) {
                lifecycleScope.launch {
                    try {
                        val result = requestPermissionsResult.launch(permissions)
                        if (result.values.all { it }) {
                            onRequestPermissionFinish()
                        } else {
                            openSettingsActivity()
                        }
                    } catch (e: CancellationException) {
                        // 权限页重建: 旧实例的等待随宿主消亡, 不是权限失败;
                        // 新实例重新展示理由并重新请求, 同一条请求继续等真实结果
                        throw e
                    } catch (e: Exception) {
                        AppLog.put("请求权限出错\n$e", e, true)
                        resultCallback()?.onError(e)
                        finish()
                    }
                }
            }
            //跳转到设置界面
            Request.TYPE_REQUEST_SETTING -> showSettingDialog(permissions, rationale) {
                openSettingsActivity()
            }
            //所有文件的管理权限
            Request.TYPE_MANAGE_ALL_FILES_ACCESS -> showSettingDialog(permissions, rationale) {
                try {
                    if (Permissions.isManageExternalStorage()) {
                        val settingIntent =
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                "package:$packageName".toUri()
                            )
                        settingActivityResult.launch(settingIntent)
                    } else {
                        throw NoStackTraceException("no MANAGE_ALL_FILES_ACCESS_PERMISSION")
                    }
                } catch (e: Exception) {
                    AppLog.put("请求所有文件的管理权限出错\n$e", e, true)
                    resultCallback()?.onError(e)
                    finish()
                }
            }

            Request.TYPE_REQUEST_NOTIFICATIONS -> showSettingDialog(permissions, rationale) {
                lifecycleScope.launch {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            && requestPermissionResult.launch(Permissions.POST_NOTIFICATIONS)
                        ) {
                            onRequestPermissionFinish()
                        } else {
                            val intent = Intent()
                            intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            intent.putExtra(Settings.EXTRA_CHANNEL_ID, applicationInfo.uid)
                            settingActivityResult.launch(intent)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.put("请求通知权限出错\n$e", e, true)
                        resultCallback()?.onError(e)
                        finish()
                    }
                }
            }

            Request.TYPE_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> showSettingDialog(
                permissions, rationale
            ) {
                lifecycleScope.launch {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = "package:$packageName".toUri()
                        val className =
                            "com.android.settings.fuelgauge.RequestIgnoreBatteryOptimizations"
                        val activities = packageManager.queryIntentActivities(
                            intent,
                            PackageManager.MATCH_DEFAULT_ONLY
                        )
                        if (activities.any { it.activityInfo.name == className }) {
                            val component = intent.resolveActivity(packageManager)
                            if (component.className != className) {
                                intent.setClassName("com.android.settings", className)
                                settingActivityResultAwait.launch(intent)
                            }
                        }
                        intent.component = null
                        settingActivityResult.launch(intent)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.put("请求后台权限出错\n$e", e, true)
                        resultCallback()?.onError(e)
                        finish()
                    }
                }
            }
        }
        // 对话框已关闭、Activity 仍可见时 (从系统设置页返回等), 返回键 = 取消权限请求,
        // 语义对齐 showSettingDialog 的 onCancelled: 回调拒绝结果 + finish。
        // (原版空回调恒消费返回键, 导致该界面返回键完全失效, 用户被困无法退出)
        onBackPressedDispatcher.addCallback(this) {
            rationaleDialog?.dismiss()
            resultCallback()?.onRequestPermissionsResult(
                permissions,
                IntArray(0)
            )
            finish()
        }
    }

    /** 只把回传交给同一原请求; 旧页面迟到结果不推进别人的队列。 */
    private fun resultCallback(): OnRequestPermissionsResultCallback? =
        RequestPlugins.sRequestCallback?.takeIf { requestId > 0L && it.id == requestId }

    private fun onRequestPermissionFinish() {
        resultCallback()?.onSettingActivityResult()
        finish()
    }

    private fun openSettingsActivity() {
        try {
            val settingIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            settingIntent.data = Uri.fromParts("package", packageName, null)
            settingActivityResult.launch(settingIntent)
        } catch (e: Exception) {
            toastOnUi(androidAppString("tip_cannot_jump_setting_page"))
            resultCallback()?.onError(e)
            finish()
        }
    }

    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun showSettingDialog(
        permissions: Array<String>,
        rationale: CharSequence?,
        onOk: () -> Unit
    ) {
        rationaleDialog?.dismiss()
        if (rationale.isNullOrEmpty()) {
            finish()
            return
        }
        rationaleDialog = alert(
            androidAppString("dialog_title"),
            rationale
        ) {
            positiveButton(androidAppString("dialog_setting")) { onOk.invoke() }
            negativeButton(androidAppString("dialog_cancel")) {
                resultCallback()?.onRequestPermissionsResult(
                    permissions,
                    IntArray(0)
                )
                finish()
            }
            onCancelled {
                resultCallback()?.onRequestPermissionsResult(
                    permissions,
                    IntArray(0)
                )
                finish()
            }
        }
    }

    companion object {

        const val KEY_RATIONALE = "KEY_RATIONALE"
        const val KEY_INPUT_REQUEST_TYPE = "KEY_INPUT_REQUEST_TYPE"
        const val KEY_INPUT_PERMISSIONS_CODE = "KEY_INPUT_PERMISSIONS_CODE"
        const val KEY_INPUT_PERMISSIONS = "KEY_INPUT_PERMISSIONS"
        const val KEY_INPUT_REQUEST_ID = "KEY_INPUT_REQUEST_ID"
    }
}
