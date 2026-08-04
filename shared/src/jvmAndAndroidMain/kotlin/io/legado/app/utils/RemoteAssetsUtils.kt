package io.legado.app.utils

import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.image.BG_CDN_PATH
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
 * `appCtx.assets` → [readSharedResourceBytes]; `okHttpClient` → [OkHttpClientProviders];
 * `AppConst.appInfo.versionName` → [PlatformCapabilityProviders]。
 */
object RemoteAssetsUtils {

    private const val BASE_URL = "https://cdn.jsdelivr.net/gh"

    // 内置背景图远程下载目录 (原版逻辑: 全图不随包, 运行时下载到本地缓存; master 已删该目录,
    // 固定 commit de37cc824b 实测 200)。路径字面量单一数据源在共享 BG_CDN_PATH (见 BgImageSources.kt)
    private const val BG_DIR = BG_CDN_PATH
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

    /**
     * 内置背景缩略图: 单一数据源在 shared composeResources `files/bg_preview/`
     * (commonMain/composeResources/files/bg_preview, 四端同一份), 本地读取零网络。
     */
    fun getBgPreviewBytes(fileName: String): ByteArray? {
        return readSharedResourceBytes("$BG_PREVIEW_DIR/$fileName")
    }

    /**
     * 取内置背景图全尺寸原图字节 (原版逻辑: 全图不随包, 远程下载到本地缓存):
     * 一级本地缓存 (对照原版 curBgDrawable: 缓存文件存在即用, 下载过就永久可用),
     * 二级 CDN 下载 (commit 级地址, master 已删该目录)。
     */
    suspend fun getBgBytes(fileName: String): ByteArray? {
        val cacheFile = getBgCachePath(fileName)
        if (cacheFile.isFile && cacheFile.length() > 0) {
            return withContext(Dispatchers.IO) { cacheFile.readBytes() }
        }
        return downloadBgIfNeeded(fileName)
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
