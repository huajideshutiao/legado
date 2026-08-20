package io.legado.app.help.update

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.text
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.decodeFromString

/**
 * 查项目自己的 GitHub Release (当前四端共用的检测实现)。
 *
 * 流程: 按渠道取 release → [AppVersions] 比版本号 → 按平台后缀 + 架构挑资产。
 * 挑不到本平台资产时仍返回新版本, 只是 [UpdateCheckInfo.downloadUrl] 为空,
 * 由执行层降级为打开 release 页。
 *
 * @param repo GitHub `owner/repo`, 便于 fork 或自建镜像替换
 */
class GitHubReleaseChecker(
    private val repo: String = DEFAULT_REPO,
) : UpdateChecker {

    override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult = runCatching {
        val checkVariant = AppUpdateShared.getCheckVariant(
            request.updateToVariant,
            request.currentAppVariant
        )
        val release = fetchRelease(checkVariant)
        val latestVersion = release.releaseVersion
        if (!AppVersions.isNewer(latestVersion, request.currentVersionName)) {
            return UpdateCheckResult.UpToDate
        }
        val asset = release.gitReleaseToAppReleaseInfo(request.platform)
            .filter { it.appVariant == checkVariant }
            .minByOrNull { assetRank(it.name, request.platform, request.supportedAbis) }
        UpdateCheckResult.NewVersion(
            UpdateCheckInfo(
                versionName = latestVersion,
                releaseNote = release.body,
                downloadUrl = asset?.downloadUrl.orEmpty(),
                fileName = asset?.name.orEmpty(),
                landingUrl = release.htmlUrl ?: "https://github.com/$repo/releases",
            )
        )
    }.getOrElse { UpdateCheckResult.Failed(it) }

    suspend fun fetchRelease(checkVariant: AppVariant): GithubRelease {
        val url = if (checkVariant.isBeta()) {
            "https://api.github.com/repos/$repo/releases/tags/beta"
        } else {
            "https://api.github.com/repos/$repo/releases/latest"
        }
        val res = OkHttpClientProviders.get().okHttpClient.newCallResponse { url(url) }
        // KmpResponse 实现 Closeable, 必须关闭 (原版 AppUpdate.kt 用 `.use {}`); commonMain 无
        // Closeable.use 扩展, 用 try/finally close() 等价实现, 非 2xx 抛异常分支也确保关闭
        try {
            if (!res.isSuccessful) throw NoStackTraceException("获取新版本出错(${res.code})")
            val body = res.body.text()
            if (body.isBlank()) throw NoStackTraceException("获取新版本出错")
            return KS_JSON.decodeFromString<GithubRelease>(body)
        } finally {
            res.close()
        }
    }

    companion object {
        const val DEFAULT_REPO = "huajideshutiao/legado"

        /**
         * 资产排序权重: 先按平台后缀优先级 (msi 优于 zip), 再按架构匹配度
         * (本机架构 0 < 通用包 1 < 其它架构 2)。与旧 Android abi 匹配语义一致。
         */
        fun assetRank(
            assetName: String,
            platform: UpdatePlatform,
            supportedAbis: List<String>
        ): Int {
            val lower = assetName.lowercase()
            val suffixIdx = platform.assetSuffixes.indexOfFirst { lower.endsWith(it) }
                .let { if (it < 0) platform.assetSuffixes.size else it }
            val archRank = when {
                AbiTokens.normalize(supportedAbis).any { lower.contains(it) } -> 0
                AbiTokens.universalTokens.any { lower.contains(it) } -> 1
                else -> 2
            }
            return suffixIdx * 10 + archRank
        }
    }
}
