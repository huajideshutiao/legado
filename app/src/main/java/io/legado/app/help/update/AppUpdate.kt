package io.legado.app.help.update

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.appInfo
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.i18n.androidAppString
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx

object AppUpdate {

    // app 端 AbiProvider: 返回 Build.SUPPORTED_ABIS
    private val abiProvider = object : AbiProvider {
        override val supportedAbis: Array<String>
            get() = android.os.Build.SUPPORTED_ABIS
    }

    fun check(
        scope: CoroutineScope,
        activity: AppCompatActivity,
        silent: Boolean = false
    ) {
        val waitDialog = if (!silent) WaitDialog.from(activity) else null
        waitDialog?.show()
        // 委托 shared 完成纯逻辑检查, app 端仅保留 UI (WaitDialog/UpdateDialog/toast)
        AppUpdateShared.check(
            scope = scope,
            abiProvider = abiProvider,
            updateToVariant = AppConfig.updateToVariant ?: "",
            currentVersionName = AppConst.appInfo.versionName,
            currentAppVariant = AppConst.appInfo.appVariant
        ).onSuccess {
            if (it != null) {
                // UpdateDialog 已下沉 sharedUiMain: 经 AppOverlay 弹更新弹窗 (payload=IntentData 侧信道)
                AppNavigatorProviders.getOrNull()?.showOverlay(
                    AppOverlay.Dialog(
                        key = "updateDialog",
                        payload = IntentData.put(it),
                    )
                )
            } else if (!silent) {
                appCtx.toastOnUi(androidAppString("is_latest_version"))
            }
        }.onError {
            if (!silent) {
                appCtx.toastOnUi("${androidAppString("check_update")}\n${it.localizedMessage}")
            }
        }.onFinally {
            waitDialog?.dismissSafe()
        }
    }
}
