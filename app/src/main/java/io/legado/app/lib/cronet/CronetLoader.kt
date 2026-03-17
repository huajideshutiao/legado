package io.legado.app.lib.cronet

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.Keep
import io.legado.app.BuildConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.Cronet
import io.legado.app.utils.DebugLog
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.security.MessageDigest

@Suppress("ConstPropertyName")
@Keep
object CronetLoader : CronetEngine.Builder.LibraryLoader(), Cronet.LoaderInterface {

    private const val soVersion = BuildConfig.Cronet_Version
    private const val soName = "libcronet.$soVersion.so"
    private val soUrl: String
    private val soFile: File
    private val downloadFile: File
    private var cpuAbi: String? = null
    private var md5: String
    var download = false

    @Volatile
    private var cacheInstall = false

    init {
        val cpuAbi = getCpuAbi(appCtx)
        soUrl =
            "https://storage.googleapis.com/chromium-cronet/android/$soVersion/Release/cronet/libs/$cpuAbi/$soName"
        md5 = getMd5(appCtx)
        val dir = appCtx.getDir("cronet", Context.MODE_PRIVATE)
        soFile = File(dir, cpuAbi).apply { mkdirs() }.resolve(soName)
        downloadFile = File(appCtx.cacheDir, "so_download").apply { mkdirs() }.resolve(soName)
    }

    /**
     * 判断系统是否支持 HttpEngine (Android 14+ / API 34+)
     */
    fun isHttpEngineAvailable(): Boolean {
        // 安卓 14 及以上，或者 SdkExtension 版本符合要求
        return Build.VERSION.SDK_INT >= 30
            && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
    }

    /**
     * 判断Cronet是否安装完成
     */
    override fun install(): Boolean {
        synchronized(this) {
            if (cacheInstall) return true
            cacheInstall = isHttpEngineAvailable() ||
                hasAvailableCronetProvider() ||
                (md5.length == 32 && soFile.exists() && md5 == getFileMD5(soFile))
            return cacheInstall
        }
    }

    override fun preDownload(onComplete: ((Boolean) -> Unit)?) {
        // 如果系统支持 HttpEngine，彻底跳过下载
        if (isHttpEngineAvailable()) {
            DebugLog.d(javaClass.simpleName, "系统支持 HttpEngine，跳过下载 SO")
            onComplete?.invoke(true)
            return
        }
        Coroutine.async {
            if (hasAvailableCronetProvider()) {
                DebugLog.d(javaClass.simpleName, "发现可用 CronetProvider，跳过下载")
                onComplete?.invoke(true)
                return@async
            }
            if (soFile.exists() && md5 == getFileMD5(soFile)) {
                onComplete?.invoke(true)
                return@async
            }
            if (download) return@async
            download = true
            Coroutine.async {
                val result = if (downloadFile.exists()) true
                else try {
                    val connection = java.net.URL(soUrl).openConnection() as HttpURLConnection
                    connection.inputStream.use { input ->
                        downloadFile.parentFile?.mkdirs()
                        downloadFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    true
                } catch (_: Throwable) {
                    downloadFile.delete()
                    false
                }

                val fileMD5 = getFileMD5(downloadFile)
                if (result && md5.equals(fileMD5, ignoreCase = true)) {
                    FileInputStream(downloadFile).use { input ->
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

    private fun hasAvailableCronetProvider(): Boolean {
        return try {
            CronetProvider.getAllProviders(appCtx).any {
                it.isEnabled && it.name != CronetProvider.PROVIDER_NAME_FALLBACK
            }
        } catch (e: Throwable) {
            DebugLog.d(javaClass.simpleName, "检查 CronetProvider 失败: ${e.message}")
            false
        }
    }

    private fun getMd5(context: Context): String {
        context.assets.open("cronet.json").bufferedReader().use { reader ->
            return JSONObject(reader.readText()).optString(getCpuAbi(context), "")
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
            if (soFile.exists() && getFileMD5(soFile) == md5) {
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

    private fun getFileMD5(file: File): String? {
        if (!file.exists()) return null
        return try {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    md.update(buffer, 0, read)
                }
            }
            String.format("%032x", BigInteger(1, md.digest())).lowercase()
        } catch (_: Exception) {
            null
        }
    }
}
