package io.legado.app.help.update

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.text
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 自建更新源检测 (对应 app 端 `AppConfig.updateUrl` 的 JSON 协议)。
 *
 * 协议 (与 app 端原版一致):
 * - 配置值为 JSON 数组串, 元素是更新源 URL, 如 `["https://example.com/update.json"]`;
 *   桌面端友好起见, 非 JSON 数组的裸串按单个 URL 处理
 * - 每个 URL 返回一个 JSON 数组, 元素为发布信息对象:
 *   ```
 *   [
 *     {
 *       "name": "legado-3.25.0801.apk",
 *       "versionName": "3.25.0801",
 *       "versionCode": 626,
 *       "note": "更新日志 (markdown)",
 *       "downloadUrl": "https://...",
 *       "appVariant": "official"   // official / beta / releaseA
 *     }
 *   ]
 *   ```
 *   未知字段忽略, 缺失字段用默认值兜底
 *
 * 与 [GitHubReleaseChecker] 的差异:
 * - 无 release 页概念, [UpdateCheckInfo.landingUrl] 恒为空 (无资产时执行层无可降级目标)
 * - 全部 URL 拉取失败时返回 [UpdateCheckResult.Failed] (不静默吞成"已是最新版本")
 *
 * 组合方式见 [UpdateStrategies] / [AppUpdateManager]。
 */
class CustomUrlUpdateChecker(
    private val urls: List<String>,
) : UpdateChecker {

    override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult = runCatching {
        if (urls.isEmpty()) throw NoStackTraceException("自定义更新地址为空")
        val checkVariant = AppUpdateShared.getCheckVariant(
            request.updateToVariant,
            request.currentAppVariant
        )
        // 逐源拉取, 容忍单个源失败 (部分成功用成功源的结果)
        val releases = mutableListOf<CustomReleaseInfo>()
        var firstError: Throwable? = null
        urls.forEach { url ->
            runCatching { fetchRelease(url) }
                .onSuccess { releases.addAll(it) }
                .onFailure { if (firstError == null) firstError = it }
        }
        if (releases.isEmpty() && firstError != null) {
            throw firstError
        }
        // 渠道过滤 + 取版本号最新 (与 app 端 filter appVariant 语义一致)
        val best = releases
            .filter { it.variant == checkVariant }
            .maxWithOrNull { a, b -> AppVersions.compare(a.versionName, b.versionName) }
        if (best == null || !AppVersions.isNewer(best.versionName, request.currentVersionName)) {
            return UpdateCheckResult.UpToDate
        }
        UpdateCheckResult.NewVersion(
            UpdateCheckInfo(
                versionName = best.versionName,
                releaseNote = best.note,
                downloadUrl = best.downloadUrl,
                fileName = best.name,
            )
        )
    }.getOrElse { UpdateCheckResult.Failed(it) }

    private suspend fun fetchRelease(url: String): List<CustomReleaseInfo> {
        val res = OkHttpClientProviders.get().okHttpClient.newCallResponse { url(url) }
        // KmpResponse 实现 Closeable, 必须关闭 (原版 AppUpdate.kt 用 `.use {}`); commonMain 无
        // Closeable.use 扩展, 用 try/finally close() 等价实现, 非 2xx 抛异常分支也确保关闭
        try {
            if (!res.isSuccessful) throw NoStackTraceException("获取新版本出错(${res.code})")
            val body = res.body.text()
            if (body.isBlank()) throw NoStackTraceException("获取新版本出错")
            return KS_JSON.decodeFromString<List<CustomReleaseInfo>>(body)
        } finally {
            res.close()
        }
    }

    companion object {
        /**
         * 解析 updateUrl 配置串 (app 端原版协议):
         * 1. JSON 数组串 → 逐元素取 URL (`["https://...","https://..."]`)
         * 2. 非 JSON 数组的非空串 → 视为单个 URL (桌面端手填便利)
         * 空串/空白 → 空列表 (调用方回退默认策略)
         */
        fun parseUrls(config: String): List<String> {
            val trimmed = config.trim()
            if (trimmed.isEmpty()) return emptyList()
            if (trimmed.startsWith("[")) {
                return runCatching {
                    KS_JSON.decodeFromString<List<String>>(trimmed)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                }.getOrElse { emptyList() }
            }
            return listOf(trimmed)
        }
    }
}

/**
 * 自建更新源发布信息 (协议字段与 app 端原版 AppReleaseInfo 一致)。
 *
 * 全部字段可缺省: [appVariant] 默认 "official", 其余默认空串/0,
 * 由 [CustomUrlUpdateChecker] 统一做渠道映射与版本比较。
 */
@Serializable
data class CustomReleaseInfo(
    val name: String = "",
    @SerialName("versionName")
    val versionName: String = "",
    @SerialName("versionCode")
    val versionCode: Int = 0,
    val note: String = "",
    @SerialName("downloadUrl")
    val downloadUrl: String = "",
    val appVariant: String = "official",
    val updateTime: Long = 0,
) {
    /** 渠道串 (official/beta/releaseA) 容错映射到本仓库 [AppVariant] 枚举。 */
    val variant: AppVariant
        get() = when {
            appVariant.contains("releaseA", ignoreCase = true) -> AppVariant.BETA_RELEASEA
            appVariant.contains("beta", ignoreCase = true) -> AppVariant.BETA_RELEASE
            else -> AppVariant.OFFICIAL
        }
}
