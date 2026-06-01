package io.legado.app.lib.cronet

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.BuildConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.Cronet
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.fileBook.RemoteZipWrapper
import io.legado.app.utils.LogUtils
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@Suppress("ConstPropertyName")
@Keep
object CronetLoader : CronetEngine.Builder.LibraryLoader(), Cronet.LoaderInterface {

    private const val soVersion = BuildConfig.Cronet_Version
    private const val aarUrl =
        "https://maven.aliyun.com/repository/google/org/chromium/net/cronet-embedded/$soVersion/cronet-embedded-$soVersion.aar"
    private val soName = "libcronet.$soVersion.so"
    private val soFile: File
    private var cpuAbi: String? = null
    var download = false

    @Volatile
    private var cacheInstall = false

    init {
        val cpuAbi = getCpuAbi(appCtx)
        val dir = appCtx.getDir("cronet", Context.MODE_PRIVATE)
        soFile = File(dir, cpuAbi).apply { mkdirs() }.resolve(soName)
    }

    /**
     * 判断Cronet是否安装完成
     */
    override fun install(): Boolean {
        synchronized(this) {
            if (cacheInstall) return true
            // GMS Provider 可用，或本地 SO 已下载，就允许拦截器尝试初始化
            return hasGmsProvider() || isSoDownloaded()
        }
    }

    fun isSoDownloaded(): Boolean {
        return soFile.exists()
    }

    fun hasGmsProvider(): Boolean {
        return try {
            CronetProvider.getAllProviders(appCtx).any {
                it.isEnabled && it.name == GMS_PROVIDER_NAME
            }
        } catch (_: Throwable) {
            false
        }
    }

    override fun preDownload(onComplete: ((Boolean) -> Unit)?) {
        // GMS Cronet Provider 可用时跳过 SO 下载
        if (hasGmsProvider()) {
            LogUtils.d(javaClass.simpleName, "GMS Cronet 可用，跳过下载 SO")
            onComplete?.invoke(true)
            return
        }
        Coroutine.async {
            if (isSoDownloaded()) {
                onComplete?.invoke(true)
                return@async
            }
            if (download) return@async
            download = true
            Coroutine.async {
                val downloadFileTmp = File(appCtx.cacheDir, "so_download/$soName.tmp")
                val downloadFile = File(appCtx.cacheDir, "so_download/$soName")
                val result = if (downloadFile.exists()) true
                else try {
                    val webDav = WebDav(aarUrl, Authorization("", ""))
                    val response =
                        webDav.webDavClient.newCall(Request.Builder().url(aarUrl).head().build())
                            .execute()
                    val fileSize = response.header("Content-Length")?.toLong() ?: -1L
                    if (fileSize <= 0) throw IOException("Failed to get file size")
                    val remoteZip = RemoteZipWrapper(webDav, "cronet.aar", fileSize)
                    val entry = remoteZip.entries().asSequence().find {
                        it.name.startsWith("jni/${getCpuAbi(appCtx)}/libcronet") && it.name.endsWith(
                            ".so"
                        )
                    } ?: throw IOException("SO entry not found for ${getCpuAbi(appCtx)}")

                    downloadFileTmp.parentFile?.mkdirs()
                    remoteZip.getInputStream(entry)?.use { input ->
                        downloadFileTmp.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    downloadFileTmp.renameTo(downloadFile)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    downloadFileTmp.delete()
                    false
                }

                if (result && downloadFile.exists()) {
                    downloadFile.inputStream().use { input ->
                        soFile.parentFile?.mkdirs()
                        FileOutputStream(soFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    soFile.setReadable(true)
                    cacheInstall = false
                    onComplete?.invoke(true)
                } else {
                    downloadFile.delete()
                    onComplete?.invoke(false)
                }
                download = false
            }
        }
    }


    @SuppressLint("UnsafeDynamicallyLoadedCode")
    override fun loadLibrary(libName: String) {
        if (!libName.contains("cronet")) {
            System.loadLibrary(libName)
            return
        }
        try {
            System.loadLibrary(libName)
        } catch (_: Throwable) {
            if (soFile.exists()) {
                System.load(soFile.absolutePath)
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun getCpuAbi(context: Context): String {
        cpuAbi?.let { return it }
        return try {
            val appInfo = context.applicationInfo
            val abiField = ApplicationInfo::class.java.getDeclaredField("primaryCpuAbi")
            abiField.isAccessible = true
            (abiField.get(appInfo) as? String) ?: Build.SUPPORTED_ABIS[0]
        } catch (_: Exception) {
            Build.SUPPORTED_ABIS[0]
        }.also { cpuAbi = it }
    }

}
