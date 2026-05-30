package io.legado.app.help.update

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx

object AppUpdate {

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

    private fun check(scope: CoroutineScope): Coroutine<UpdateInfo?> {
        return Coroutine.async(scope) {
            val supportedAbis = android.os.Build.SUPPORTED_ABIS
            val checkVariant = getCheckVariant()
            getLatestRelease(checkVariant)
                .filter { it.appVariant == checkVariant }
                .filter { it.versionName > AppConst.appInfo.versionName }
                .minByOrNull { info ->
                    when {
                        supportedAbis.any { abi ->
                            val shortAbi = when {
                                abi.startsWith("arm64") -> "arm64"
                                abi.startsWith("armeabi-v7") -> "armv7"
                                abi.startsWith("x86_64") -> "x64"
                                else -> abi
                            }
                            info.name.contains(shortAbi, ignoreCase = true)
                        } -> 0

                        info.name.contains("all", ignoreCase = true) -> 1
                        else -> 2
                    }
                }
                ?.let {
                    return@async UpdateInfo(it.versionName, it.note, it.downloadUrl, it.name)
                }
            return@async null
        }.timeout(10000)
    }

    private fun getCheckVariant(): AppVariant {
        return when (AppConfig.updateToVariant) {
            "official_version" -> AppVariant.OFFICIAL
            "beta_release_version" -> AppVariant.BETA_RELEASE
            "beta_releaseA_version" -> AppVariant.BETA_RELEASEA
            else -> {
                val variant = AppConst.appInfo.appVariant
                if (variant == AppVariant.UNKNOWN) AppVariant.OFFICIAL else variant
            }
        }
    }

    private suspend fun getLatestRelease(checkVariant: AppVariant): List<AppReleaseInfo> {
        val url = if (checkVariant.isBeta()) {
            "https://api.github.com/repos/huajideshutiao/legado/releases/tags/beta"
        } else {
            "https://api.github.com/repos/huajideshutiao/legado/releases/latest"
        }
        okHttpClient.newCallResponse { url(url) }.use { res ->
            if (!res.isSuccessful) throw NoStackTraceException("获取新版本出错(${res.code})")
            val body = res.body.text()
            if (body.isBlank()) throw NoStackTraceException("获取新版本出错")
            return GSON.fromJsonObject<GithubRelease>(body).getOrThrow()
                .gitReleaseToAppReleaseInfo()
        }
    }

}
