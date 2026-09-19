package io.legado.app.utils

import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallResponse
import io.legado.app.ui.root.PlatformCapabilityProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/**
 * 远端资产 (简繁词典) 按需下载缓存 (下沉自 app 端 `utils/RemoteAssetsUtils.kt`)。
 *
 * Android 专属依赖已换成 KMP 抽象: `appCtx.cacheDir` → [AppFilesDirs];
 * `okHttpClient` → [OkHttpClientProviders];
 * `AppConst.appInfo.versionName` → [PlatformCapabilityProviders]。
 *
 * 内置阅读背景图的远程下载链路 (bg:// 原图 / bg_preview 随包缩略图) 已随
 * "内置背景下线、背景完全用户自选" 整体移除, 本类只保留简繁词典下载。
 */
object RemoteAssetsUtils {

    private const val BASE_URL = "https://cdn.jsdelivr.net/gh"

    private const val TC_DIR =
        "liuyueyi/quick-chinese-transfer@master/transfer-core/src/main/resources/tc"

    private val remoteAssetsDir: File by lazy {
        File(AppFilesDirs.get().cacheDir, "remote_assets").apply { if (!exists()) mkdirs() }
    }

    private val tcCacheDir: File by lazy {
        File(remoteAssetsDir, "tc").apply { if (!exists()) mkdirs() }
    }

    suspend fun downloadTcIfNeeded(fileName: String): ByteArray? {
        return downloadFile(tcCacheDir, TC_DIR, fileName)
    }

    private suspend fun downloadFile(
        cacheDir: File,
        dirPath: String,
        fileName: String
    ): ByteArray? {
        val cachedFile = File(cacheDir, fileName)
        if (cachedFile.exists() && cachedFile.length() > 0) {
            return withContext(Dispatchers.IO) { cachedFile.readBytes() }
        }

        return withContext(Dispatchers.IO) {
            try {
                val encodedFileName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                val url = "$BASE_URL/$dirPath/$encodedFileName"
                // getOrNull: 简繁字库下载由书源 JS 的 t2s/s2t 触发, 可能跑在无 UI 宿主的后台链上
                val versionName =
                    PlatformCapabilityProviders.getOrNull()?.getAppVersionName().orEmpty()
                OkHttpClientProviders.get().okHttpClient.newCallResponse {
                    url(url)
                    header("User-Agent", "Legado/$versionName")
                }.use { response ->
                    if (response.isSuccessful) {
                        response.body.bytes().also {
                            cachedFile.writeBytes(it)
                        }
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun getTcCachePath(fileName: String): File = File(tcCacheDir, fileName)

}
