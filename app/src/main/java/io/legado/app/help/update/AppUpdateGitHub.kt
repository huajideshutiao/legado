package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

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

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo?> {
        return Coroutine.async(scope) {
            val supportedAbis = android.os.Build.SUPPORTED_ABIS
            val checkVariant = getCheckVariant()
            getLatestRelease(checkVariant)
                .filter { it.appVariant == checkVariant }
                .filter { it.versionName > AppConst.appInfo.versionName }
                .minByOrNull { info ->
                    // 架构匹配优先级排序：完全匹配 > all > 其他
                    val abiScore = when {
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
                    abiScore
                }
                ?.let {
                    return@async AppUpdate.UpdateInfo(
                        it.versionName,
                        it.note,
                        it.downloadUrl,
                        it.name
                    )
                }
            return@async null
        }.timeout(10000)
    }
}
