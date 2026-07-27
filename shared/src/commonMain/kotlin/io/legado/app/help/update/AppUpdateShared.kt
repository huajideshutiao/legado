package io.legado.app.help.update

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.text
import io.legado.app.utils.KS_JSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.decodeFromString

/**
 * AppUpdate 纯逻辑下沉 commonMain:
 * - ABI 匹配 + 版本号比较 + GitHub Releases 拉取
 * - 通过 [AbiProvider] 注入 SUPPORTED_ABIS (app 端 Build.SUPPORTED_ABIS, desktop 端空数组或 JVM 架构)
 * - 通过 [OkHttpClientProviders] 取 okHttpClient (已下沉 shared)
 * - 通过 [KS_JSON] 解析 (已下沉 shared)
 *
 * app 端 [io.legado.app.help.update.AppUpdate] 仅保留 UI 部分 (WaitDialog/UpdateDialog/toast),
 * 委托本类完成检查。
 */
object AppUpdateShared {

    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String
    )

    fun check(
        scope: CoroutineScope,
        abiProvider: AbiProvider,
        updateToVariant: String,
        currentVersionName: String,
        currentAppVariant: AppVariant
    ): Coroutine<UpdateInfo?> {
        return Coroutine.async(scope) {
            val supportedAbis = abiProvider.supportedAbis
            val checkVariant = getCheckVariant(updateToVariant, currentAppVariant)
            getLatestRelease(checkVariant)
                .filter { it.appVariant == checkVariant }
                .filter { it.versionName > currentVersionName }
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

    fun getCheckVariant(
        updateToVariant: String,
        currentAppVariant: AppVariant
    ): AppVariant {
        return when (updateToVariant) {
            "official_version" -> AppVariant.OFFICIAL
            "beta_release_version" -> AppVariant.BETA_RELEASE
            "beta_releaseA_version" -> AppVariant.BETA_RELEASEA
            else -> {
                if (currentAppVariant == AppVariant.UNKNOWN) AppVariant.OFFICIAL else currentAppVariant
            }
        }
    }

    suspend fun getLatestRelease(checkVariant: AppVariant): List<AppReleaseInfo> {
        val url = if (checkVariant.isBeta()) {
            "https://api.github.com/repos/huajideshutiao/legado/releases/tags/beta"
        } else {
            "https://api.github.com/repos/huajideshutiao/legado/releases/latest"
        }
        val res = OkHttpClientProviders.get().okHttpClient.newCallResponse { url(url) }
        if (!res.isSuccessful) throw NoStackTraceException("获取新版本出错(${res.code})")
        val body = res.body.text()
        if (body.isBlank()) throw NoStackTraceException("获取新版本出错")
        return KS_JSON.decodeFromString<GithubRelease>(body)
            .gitReleaseToAppReleaseInfo()
    }
}
