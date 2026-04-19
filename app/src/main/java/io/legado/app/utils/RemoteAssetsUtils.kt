package io.legado.app.utils

import io.legado.app.constant.AppConst
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import splitties.init.appCtx
import java.io.File

object RemoteAssetsUtils {

    private const val BASE_URL = "https://fastly.jsdelivr.net/gh/"
    private const val BG_DIR = "gedoor/legado@master/app/src/main/assets/bg"
    private const val BG_PREVIEW_DIR = "bg_preview"
    private const val TC_DIR =
        "liuyueyi/quick-chinese-transfer@master/transfer-core/src/main/resources/tc"

    private val bgFiles = listOf(
        "午后沙滩.jpg",
        "宁静夜色.jpg",
        "山水墨影.jpg",
        "山水画.jpg",
        "护眼漫绿.jpg",
        "新羊皮纸.jpg",
        "明媚倾城.jpg",
        "深宫魅影.jpg",
        "清新时光.jpg",
        "羊皮纸1.jpg",
        "羊皮纸2.jpg",
        "羊皮纸3.jpg",
        "羊皮纸4.jpg",
        "边彩画布.jpg"
    )

    private val tcFiles = listOf(
        "s2t.txt",
        "t2hk.txt",
        "t2s.txt",
        "t2tw.txt"
    )

    private val cacheDir: File
        get() = File(appCtx.filesDir, "remote_assets").also {
            if (!it.exists()) it.mkdirs()
        }

    private val bgCacheDir: File
        get() = File(cacheDir, "bg").also {
            if (!it.exists()) it.mkdirs()
        }

    private val tcCacheDir: File
        get() = File(cacheDir, "tc").also {
            if (!it.exists()) it.mkdirs()
        }

    fun getBgPreviewBytes(fileName: String): ByteArray? {
        return try {
            appCtx.assets.open("$BG_PREVIEW_DIR/$fileName").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun downloadBgIfNeeded(fileName: String): ByteArray? {
        val cachedFile = File(bgCacheDir, fileName)
        if (cachedFile.exists() && cachedFile.length() > 0) {
            return cachedFile.readBytes()
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/$BG_DIR/$fileName"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Legado/${AppConst.appInfo.versionName}")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        run {
                            cachedFile.writeBytes(bytes)
                            bytes
                        }
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun downloadTcIfNeeded(fileName: String): ByteArray? {
        val cachedFile = File(tcCacheDir, fileName)
        if (cachedFile.exists() && cachedFile.length() > 0) {
            return cachedFile.readBytes()
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/$TC_DIR/$fileName"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Legado/${AppConst.appInfo.versionName}")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        run {
                            cachedFile.writeBytes(bytes)
                            bytes
                        }
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun getBgList(): List<String> = bgFiles

    fun getTcList(): List<String> = tcFiles

    fun getBgCachePath(fileName: String): File = File(bgCacheDir, fileName)

    fun getTcCachePath(fileName: String): File = File(tcCacheDir, fileName)

    fun hasBgCache(fileName: String): Boolean {
        val file = File(bgCacheDir, fileName)
        return file.exists() && file.length() > 0
    }

    fun hasTcCache(fileName: String): Boolean {
        val file = File(tcCacheDir, fileName)
        return file.exists() && file.length() > 0
    }

    suspend fun preloadAll() {
        bgFiles.forEach { fileName ->
            downloadBgIfNeeded(fileName)
        }
        tcFiles.forEach { fileName ->
            downloadTcIfNeeded(fileName)
        }
    }
}
