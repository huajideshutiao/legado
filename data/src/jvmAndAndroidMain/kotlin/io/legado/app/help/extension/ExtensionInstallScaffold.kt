package io.legado.app.help.extension

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.extension.model.InstallStep
import io.legado.app.help.extension.model.MangaExtension
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
 * 扩展安装包元信息。两端读取途径不同 (Android = PackageManager 查询; 桌面 = AXML 解析 +
 * apksig 校验), 但校验判据相同, 故先归一到本结构再进 [checkExtensionInstallable]。
 */
data class ExtensionApkInfo(
    val pkgName: String,
    val versionCode: Long,
    val isExtension: Boolean,
    /** 签名证书 SHA-256 小写 hex; 无签名或提取失败为空表。 */
    val signatures: List<String>,
)

/**
 * 覆盖安装校验 (两端单一事实来源): 必须是扩展包 / 不允许降级 / 新包必须已签名 /
 * 已装包签名必须可读且被新包完全覆盖 —— 已装包签名读取失败即拒绝, 不做空集放行。
 */
fun checkExtensionInstallable(incoming: ExtensionApkInfo, current: ExtensionApkInfo?) {
    check(incoming.isExtension) { "${incoming.pkgName} 不是扩展" }
    if (current == null) return
    check(incoming.versionCode >= current.versionCode) { "不允许降级安装" }
    check(incoming.signatures.isNotEmpty()) { "扩展未签名" }
    check(current.signatures.isNotEmpty() && incoming.signatures.containsAll(current.signatures)) {
        "与已安装扩展签名不一致"
    }
}

/** Android 包名契约: 至少两段, 每段字母开头, 仅字母/数字/下划线。 */
private val EXTENSION_PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

fun isValidExtensionPackageName(pkgName: String): Boolean = EXTENSION_PACKAGE_NAME.matches(pkgName)

/**
 * 校验扩展包名。包名来自 apk manifest 或仓库索引, 直接参与扩展文件与 dex2jar 产物路径拼接,
 * 含路径分隔符或 `..` 的包名可把文件写到扩展目录之外, 故拼接前必须先过白名单 (两端共用本处)。
 */
fun requireValidExtensionPackageName(pkgName: String): String {
    if (!isValidExtensionPackageName(pkgName)) {
        throw NoStackTraceException("非法扩展包名: $pkgName")
    }
    return pkgName
}

/**
 * 版本名并入产物文件名前的净化: 非 [A-Za-z0-9._-] 字符替换为 '_'。版本名来自 apk manifest,
 * 无格式约束, 可含路径分隔与上跳序列。
 */
fun safeExtensionFileNameSegment(raw: String): String =
    raw.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
        .joinToString("")

/**
 * 下载扩展 apk 到 [target] (进度按 1% 粒度回调); 失败抛异常, 由调用方收敛成安装错误。
 */
suspend fun downloadExtensionApk(url: String, target: File, onProgress: suspend (Int) -> Unit) {
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

/**
 * 扩展安装脚手架 (JVM+Android 共用): 下载 apk 到临时文件 → 覆盖校验 → 平台落盘 → 触发重扫。
 * 下载/进度/取消/失败收敛与覆盖校验只有这一份实现 (两端各自实现曾导致签名严格度漂移);
 * 平台差异收敛在 [tempFile]/[readApkInfo]/[readInstalledInfo]/[placeApk]/[onInstalled]。
 */
abstract class ExtensionInstallScaffold {

    private val installJobs = ConcurrentHashMap<String, Job>()

    /** 下载落地点 (流程结束必删)。 */
    protected abstract fun tempFile(pkgName: String): File

    /** 读取安装包元信息; 无法解析返回 null。 */
    protected abstract fun readApkInfo(file: File): ExtensionApkInfo?

    /** 读取已安装的同包扩展元信息; 未安装返回 null。 */
    protected abstract fun readInstalledInfo(pkgName: String): ExtensionApkInfo?

    /** 校验通过后落盘; [replaced] 为 true 表示覆盖已有同包扩展。 */
    protected abstract fun placeApk(file: File, info: ExtensionApkInfo, replaced: Boolean)

    /** 落盘后触发重扫; 走安装广播的端无需覆写。 */
    protected open fun onInstalled(pkgName: String) {}

    /** 下载并安装; 同一扩展再次发起安装时取消进行中的任务, 收集方取消即中止。 */
    fun install(extension: MangaExtension.Available): Flow<InstallStep> {
        installJobs[extension.pkgName]?.cancel()
        return flow {
            val job = currentCoroutineContext()[Job] ?: error("安装流程必须在协程中收集")
            installJobs[extension.pkgName] = job
            var apkFile: File? = null
            try {
                requireValidExtensionPackageName(extension.pkgName)
                emit(InstallStep.Downloading)
                val target = tempFile(extension.pkgName)
                apkFile = target
                downloadExtensionApk(extension.apkUrl, target) { emit(InstallStep.Progress(it)) }
                currentCoroutineContext().ensureActive()

                emit(InstallStep.Installing)
                val info = readApkInfo(target)
                    ?: throw NoStackTraceException("安装包无法解析: ${extension.pkgName}")
                val current = readInstalledInfo(info.pkgName)
                checkExtensionInstallable(info, current)
                placeApk(target, info, replaced = current != null)
                emit(InstallStep.Installed)
                onInstalled(info.pkgName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(InstallStep.Error(e.message))
            } finally {
                installJobs.remove(extension.pkgName, job)
                apkFile?.delete()
            }
        }.flowOn(IoDispatcher)
    }

    fun cancelInstall(pkgName: String) {
        installJobs[pkgName]?.cancel()
    }
}
