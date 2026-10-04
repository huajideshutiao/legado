package io.legado.app.help.extension.installer

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.extension.model.InstallStep
import io.legado.app.help.extension.model.MangaExtension
import io.legado.app.help.extension.util.ExtensionLoader
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 扩展安装器: 下载 apk 到 cacheDir → 校验 (feature/降级/签名) → 拷为私有扩展文件
 * (filesDir/exts, 只读)。不调用系统包安装器; 共享扩展的卸载走系统卸载界面。
 *
 * Flow 收集方取消即中止安装; 同一扩展再次发起安装时取消进行中的任务。
 */
internal class ExtensionInstaller(private val context: Context) {

    private val installJobs = ConcurrentHashMap<String, Job>()

    fun downloadAndInstall(extension: MangaExtension.Available): Flow<InstallStep> {
        installJobs[extension.pkgName]?.cancel()
        return flow {
            val job = currentCoroutineContext()[Job] ?: error("安装流程必须在协程中收集")
            installJobs[extension.pkgName] = job
            val apkFile = File(context.cacheDir, "${extension.pkgName}.apk")
            try {
                emit(InstallStep.Downloading)
                download(extension.apkUrl, apkFile) { progress -> emit(InstallStep.Progress(progress)) }
                currentCoroutineContext().ensureActive()

                emit(InstallStep.Installing)
                val pkgInfo = ExtensionLoader.getArchivePackageInfo(context, apkFile)
                    ?: throw NoStackTraceException("安装包无法解析: ${extension.pkgName}")
                ExtensionLoader.installPrivateExtensionFile(context, apkFile, pkgInfo)

                emit(InstallStep.Installed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(InstallStep.Error(e.message))
            } finally {
                installJobs.remove(extension.pkgName, job)
                apkFile.delete()
            }
        }.flowOn(IoDispatcher)
    }

    fun cancelInstall(pkgName: String) {
        installJobs[pkgName]?.cancel()
    }

    fun uninstallSharedApk(pkgName: String) {
        val intent = Intent(Intent.ACTION_DELETE, "package:$pkgName".toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private suspend fun download(url: String, target: File, onProgress: suspend (Int) -> Unit) {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw NoStackTraceException("下载失败 HTTP ${response.code}: $url")
            }
            val body = response.body
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastProgress = -1
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val progress = ((written * 100) / total).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8 * 1024
    }
}
