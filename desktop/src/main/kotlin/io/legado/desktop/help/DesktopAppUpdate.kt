package io.legado.desktop.help

import io.legado.app.constant.AppLog
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.toast.Toasters
import io.legado.desktop.constant.DesktopAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * 桌面端检查更新 (方案 B: GitHub Release API + 手动下载)。
 *
 * # 背景 / 与 app 端差异
 *
 * app 端 [io.legado.app.help.update.AppUpdate] 走自建更新源 (`AppConfig.updateUrl`, JSON 协议)
 * + DownloadManager 自动下载安装 (Android Intent `ACTION_INSTALL_PACKAGE`)。
 *
 * 桌面端无 Intent / DownloadManager, 也无自建更新源下沉, 改为:
 * 1. 调 GitHub Release API (`/repos/gedoor/legado/releases/latest`) 取最新 release
 * 2. 解析 `tag_name` (版本号) + `body` (release notes) + `assets` (找 Windows `.msi` 下载链接)
 * 3. 与 [DesktopAppInfo.versionName] 比对 (去 v 前缀, 简单字符串比对)
 * 4. 有新版本时回调 [onResult] 由 UI 层弹窗提示用户手动下载
 *    (`java.awt.Desktop.browse` 打开下载 URL); 已是最新版本 / 失败时由本类直接 toast 提示
 *    (UI 层无需处理这两类分支)
 *
 * # 不引入
 *
 * - semver 库: 版本比对仅去 v 前缀后字符串等值比较 (与 app 端 AppUpdate.isNewest 语义对齐)
 * - 自动下载安装: 桌面端无 Intent, 仅提示用户手动下载
 *
 * @see io.legado.app.help.update.AppUpdate (app 端实现, 走自建更新源)
 */
object DesktopAppUpdate {

    /** GitHub Release API (latest release 元数据: tag_name / body / assets)。 */
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/gedoor/legado/releases/latest"

    /** GitHub API v2+ 推荐 Accept 头 (避免 API 弃用警告)。 */
    private const val ACCEPT_HEADER = "application/vnd.github+json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 检查更新。
     *
     * @param onResult 新版本可用时回调 [UpdateInfo] (UI 层据此弹 AlertDialog 提示用户手动下载);
     *                 已是最新版本 / 网络失败时由本函数直接 toast, 不回调 [onResult]。
     */
    suspend fun check(onResult: (UpdateInfo) -> Unit) {
        val info = runCatching {
            fetchLatestRelease()
        }.getOrElse {
            AppLog.put("检查更新失败\n${it.localizedMessage}", it)
            Toasters.get().toast(it.localizedMessage ?: "检查更新失败")
            return
        }
        // 版本比对: 去 v/V 前缀 + trim, 简单字符串等值比较 (不引入 semver)
        val current = DesktopAppInfo.versionName.removePrefix("v").removePrefix("V").trim()
        val latest = info.latestVersion.removePrefix("v").removePrefix("V").trim()
        if (latest.isNotEmpty() && latest != current) {
            onResult(info)
        } else {
            Toasters.get().toast("已是最新版本")
        }
    }

    /** 拉 latest release JSON 并解析为 [UpdateInfo] (IO 线程执行)。 */
    private suspend fun fetchLatestRelease(): UpdateInfo = withContext(Dispatchers.IO) {
        val client = OkHttpClientProviders.get().okHttpClient
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", ACCEPT_HEADER)
            .build()
        client.newCall(request).execute().use { response ->
            val bodyStr = response.body.string()
            parseUpdateInfo(bodyStr)
        }
    }

    /**
     * 解析 GitHub Release API JSON 响应为 [UpdateInfo]。
     *
     * 仅取 3 个字段: `tag_name` (版本号) / `body` (release notes markdown) / `assets`
     * (找 `name` 以 `.msi` 结尾的 Windows 安装包 `browser_download_url`)。
     * 用 kotlinx.serialization.json 的 DOM API 手动遍历, 不为整个响应定义 @Serializable。
     */
    private fun parseUpdateInfo(jsonStr: String): UpdateInfo {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: ""
        val body = obj["body"]?.jsonPrimitive?.contentOrNull ?: ""
        var downloadUrl = ""
        val assets = obj["assets"]?.jsonArray
        if (assets != null) {
            for (asset in assets) {
                val name = asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
                // Windows MSI 安装包 (jpackage 产物, 对应 desktop/build.gradle.kts TargetFormat.Msi)
                if (name.endsWith(".msi", ignoreCase = true)) {
                    downloadUrl = asset.jsonObject["browser_download_url"]
                        ?.jsonPrimitive?.contentOrNull ?: ""
                    break
                }
            }
        }
        return UpdateInfo(
            latestVersion = tag,
            releaseNotes = body,
            downloadUrl = downloadUrl,
        )
    }
}

/**
 * 检查更新结果 (新版本元数据)。
 *
 * @param latestVersion 最新版本号 (GitHub Release `tag_name`, 如 "3.25.0722" 或 "v3.25.0722")
 * @param releaseNotes  release notes (GitHub Release `body`, markdown 文本)
 * @param downloadUrl   Windows MSI 下载链接 (`assets[].browser_download_url`; 无 MSI 资源时为空串)
 */
data class UpdateInfo(
    val latestVersion: String,
    val releaseNotes: String,
    val downloadUrl: String,
)
