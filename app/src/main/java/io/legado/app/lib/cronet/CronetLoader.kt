package io.legado.app.lib.cronet

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.ext.SdkExtensions
import android.text.TextUtils
import androidx.annotation.Keep
import io.legado.app.BuildConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.Cronet
import io.legado.app.utils.DebugLog
import org.chromium.net.CronetProvider
import org.chromium.net.CronetEngine
import org.json.JSONObject
import splitties.init.appCtx
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
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
        soUrl = ("https://storage.googleapis.com/chromium-cronet/android/"
                + soVersion + "/Release/cronet/libs/"
                + getCpuAbi(appCtx) + "/" + soName)
        md5 = getMd5(appCtx)
        val dir = appCtx.getDir("cronet", Context.MODE_PRIVATE)
        soFile = File(dir.toString() + "/" + getCpuAbi(appCtx), soName)
        downloadFile = File(appCtx.cacheDir.toString() + "/so_download", soName)
    }

    /**
     * 判断系统是否支持 HttpEngine (Android 14+ / API 34+)
     */
    fun isHttpEngineAvailable(): Boolean {
        // 安卓 14 及以上，或者 SdkExtension 版本符合要求
        return Build.VERSION.SDK_INT >= 34 || 
               (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(30) >= 7)
    }

    /**
     * 判断Cronet是否安装完成
     */
    override fun install(): Boolean {
        synchronized(this) {
            if (cacheInstall) return true

            // 1. 如果是安卓 14+，直接认为已安装（将使用系统 HttpEngine）
            if (isHttpEngineAvailable()) {
                cacheInstall = true
                return true
            }

            // 2. 检查是否有其他可用的 Provider (如 GMS)
            if (hasAvailableCronetProvider()) {
                cacheInstall = true
                return true
            }

            // 3. 检查本地下载的 SO 库 (旧版本安卓)
            if (md5.length == 32 && soFile.exists() && md5 == getFileMD5(soFile)) {
                cacheInstall = true
                return true
            }
            
            return false
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
            download(soUrl, md5, downloadFile, soFile, onComplete)
        }
    }

    private fun hasAvailableCronetProvider(): Boolean {
        try {
            val providers = CronetProvider.getAllProviders(appCtx)
            for (provider in providers) {
                // 排除 Fallback，如果找到任何启用的 Native 或 GMS Provider，说明不需要下载
                if (provider.isEnabled && provider.name != CronetProvider.PROVIDER_NAME_FALLBACK) {
                    DebugLog.d(javaClass.simpleName, "找到可用 Provider: ${provider.name}")
                    return true
                }
            }
        } catch (e: Throwable) {
            DebugLog.d(javaClass.simpleName, "检查 CronetProvider 失败: ${e.message}")
        }
        return false
    }

    private fun getMd5(context: Context): String {
        val stringBuilder = StringBuilder()
        return try {
            val assetManager = context.assets
            val bf = BufferedReader(InputStreamReader(assetManager.open("cronet.json")))
            var line: String?
            while (bf.readLine().also { line = it } != null) {
                stringBuilder.append(line)
            }
            JSONObject(stringBuilder.toString()).optString(getCpuAbi(context), "")
        } catch (e: java.lang.Exception) { "" }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    override fun loadLibrary(libName: String) {
        // 这个方法会被 CronetEngine.Builder 调用。
        // 在使用系统内置实现时，系统会自动处理 loadLibrary，不应报错。
        try {
            if (!libName.contains("cronet")) {
                System.loadLibrary(libName)
                return
            }
            System.loadLibrary(libName)
        } catch (e: Throwable) {
            if (soFile.exists() && getFileMD5(soFile) == md5) {
                System.load(soFile.absolutePath)
            } else {
                // 如果是安卓 14+ 但因为某些原因调用到这里，且没有本地库，
                // 理论上此时应该使用系统的实现，系统内部已完成加载，尝试 System.loadLibrary 可能抛异常，
                // 这种情况下由于是在 install()=true 之后发生的，我们需要捕获并忽略，或者重新尝试加载。
                System.loadLibrary(libName)
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun getCpuAbi(context: Context): String? {
        if (cpuAbi != null) return cpuAbi
        try {
            val appInfo = context.applicationInfo
            val abiField = ApplicationInfo::class.java.getDeclaredField("primaryCpuAbi")
            abiField.isAccessible = true
            cpuAbi = abiField.get(appInfo) as String?
        } catch (e: Exception) { }
        if (TextUtils.isEmpty(cpuAbi)) cpuAbi = Build.SUPPORTED_ABIS[0]
        return cpuAbi
    }

    private fun downloadFileIfNotExist(url: String, destFile: File): Boolean {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            inputStream = connection.inputStream
            if (destFile.exists()) return true
            destFile.parentFile?.mkdirs()
            destFile.createNewFile()
            outputStream = FileOutputStream(destFile)
            val buffer = ByteArray(32768)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }
            return true
        } catch (e: Throwable) {
            destFile.delete()
            return false
        } finally {
            inputStream?.close()
            outputStream?.close()
        }
    }

    private fun download(url: String, md5: String?, tempFile: File, destFile: File, onComplete: ((Boolean) -> Unit)? = null) {
        if (download) return
        download = true
        Coroutine.async {
            val result = downloadFileIfNotExist(url, tempFile)
            val fileMD5 = getFileMD5(tempFile)
            if (result && md5.equals(fileMD5, ignoreCase = true)) {
                copyFile(tempFile, destFile)
                cacheInstall = false
                onComplete?.invoke(true)
            } else {
                tempFile.delete()
                onComplete?.invoke(false)
            }
            download = false
        }
    }

    private fun copyFile(source: File, dest: File): Boolean {
        try {
            FileInputStream(source).use { input ->
                dest.parentFile?.mkdirs()
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            dest.setReadable(true)
            return true
        } catch (e: Exception) { return false }
    }

    private fun getFileMD5(file: File): String? {
        if (!file.exists()) return null
        try {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    md.update(buffer, 0, read)
                }
            }
            return String.format("%032x", BigInteger(1, md.digest())).lowercase()
        } catch (e: Exception) { return null }
    }
}
