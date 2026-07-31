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
 * 远端资产 (内置背景图 / 简繁词典) 按需下载缓存 (下沉自 app 端 `utils/RemoteAssetsUtils.kt`)。
 *
 * Android 专属依赖已换成 KMP 抽象: `appCtx.cacheDir` → [AppFilesDirs];
 * `appCtx.assets` → [readAppAssetBytes]; `okHttpClient` → [OkHttpClientProviders];
 * `AppConst.appInfo.versionName` → [PlatformCapabilityProviders]。
 */
object RemoteAssetsUtils {

    private const val BASE_URL = "https://fastly.jsdelivr.net/gh"
    private const val BG_DIR = "huajideshutiao/legado@master/app/src/main/assets/bg"
    private const val BG_PREVIEW_DIR = "bg_preview"
    private const val TC_DIR =
        "liuyueyi/quick-chinese-transfer@master/transfer-core/src/main/resources/tc"

    private val bgFiles = listOf(
        "午后沙滩.jpg", "宁静夜色.jpg", "山水墨影.jpg", "山水画.jpg",
        "护眼漫绿.jpg", "新羊皮纸.jpg", "明媚倾城.jpg", "深宫魅影.jpg",
        "清新时光.jpg", "羊皮纸1.jpg", "羊皮纸2.jpg", "羊皮纸3.jpg",
        "羊皮纸4.jpg", "边彩画布.jpg"
    )

    private val remoteAssetsDir: File by lazy {
        File(AppFilesDirs.get().cacheDir, "remote_assets").apply { if (!exists()) mkdirs() }
    }

    private val bgCacheDir: File by lazy {
        File(remoteAssetsDir, "bg").apply { if (!exists()) mkdirs() }
    }

    private val tcCacheDir: File by lazy {
        File(remoteAssetsDir, "tc").apply { if (!exists()) mkdirs() }
    }

    fun getBgPreviewBytes(fileName: String): ByteArray? {
        return readAppAssetBytes("$BG_PREVIEW_DIR/$fileName")
    }

    suspend fun downloadBgIfNeeded(fileName: String): ByteArray? {
        return downloadFile(bgCacheDir, BG_DIR, fileName)
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

    fun getBgList(): List<String> = bgFiles

    fun getBgCachePath(fileName: String): File = File(bgCacheDir, fileName)

    fun getTcCachePath(fileName: String): File = File(tcCacheDir, fileName)

}
