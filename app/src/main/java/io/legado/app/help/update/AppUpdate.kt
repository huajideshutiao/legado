package io.legado.app.help.update

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx

object AppUpdate {

    val gitHubUpdate: AppUpdateInterface? by lazy {
        AppUpdateGitHub
    }

    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String
    )

    fun check(
        scope: CoroutineScope,
        activity: AppCompatActivity,
        silent: Boolean = false
    ) {
        val waitDialog = if (!silent) WaitDialog.from(activity) else null
        waitDialog?.show()
        gitHubUpdate?.run {
            check(scope)
                .onSuccess {
                    if (it != null) {
                        activity.showDialogFragment(UpdateDialog(it))
                    } else if (!silent) {
                        appCtx.toastOnUi(R.string.is_latest_version)
                    }
                }.onError {
                    if (!silent) {
                        appCtx.toastOnUi("${activity.getString(R.string.check_update)}\n${it.localizedMessage}")
                    }
                }.onFinally {
                    waitDialog?.dismissSafe()
                }
        }
    }

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo?>

    }

}
