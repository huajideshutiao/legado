package io.legado.desktop.extension

import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.extension.ExtensionApkInfo
import io.legado.app.help.extension.ExtensionInstallScaffold
import io.legado.app.help.extension.MangaExtensionHost
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.help.extension.model.ContentWarning as ModelContentWarning
import io.legado.app.help.extension.model.InstallStep
import io.legado.app.help.extension.model.MangaExtension
import io.legado.app.help.extension.requireValidExtensionPackageName
import io.legado.app.help.extension.trust.TrustHelper
import io.legado.app.help.file.desktopAppRootDir
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 桌面端扩展目录: 应用数据根下 extensions/ (便携模式跟随程序目录 data/)。 */
fun desktopMangaExtensionDir(): File = File(desktopAppRootDir(), "extensions")

/**
 * [MangaExtensionHost] 桌面实现: 扩展目录扫描 (extensions 目录下 *.apk 文件) 喂
 * [JvmExtensionLoader] (dex2jar 转 jar + ChildFirstURLClassLoader) 装载出
 * Source/AnimeSource, 映射回共享层 MangaExtension 模型。
 *
 * 与 Android 端 PackageManager 面的差异: 无共享扩展形态 (isShared 恒 false)、
 * 无安装广播 (安装/卸载落盘后直接触发整表重扫)、信任判定在 dex2jar 之前
 * 完成 (未信任扩展不转换)。apk 未变 (mtime/size) 且仍受信任时复用已装载
 * 实例与 classloader, 避免每次重扫重复 dex2jar。
 */
class DesktopMangaExtensionHost(
    private val extDir: File = desktopMangaExtensionDir(),
) : MangaExtensionHost {

    /** 装载缓存: pkgName → (apk 标记, JvmExtension.Loaded)。 */
    private val loadCache = ConcurrentHashMap<String, CachedLoad>()

    private data class CachedLoad(
        val apkLastModified: Long,
        val apkLength: Long,
        val loaded: JvmExtension.Loaded,
    )

    override suspend fun loadExtensions(
        alreadyLoaded: Map<String, MangaExtension.Loaded>,
    ): List<MangaExtension.Installed> = withContext(IoDispatcher) {
        extDir.mkdirs()
        val apks = extDir.listFiles { file -> file.isFile && file.extension == APK_EXTENSION }
            .orEmpty()
            .sortedBy { it.name }
        val results = ArrayList<MangaExtension.Installed>(apks.size)
        val seen = HashSet<String>()
        for (apk in apks) {
            // manifest 解析失败的包直接跳过 (对齐 Android getPackageArchiveInfo null 语义)
            val manifest = runCatching { ApkManifestReader.read(apk) }
                .onFailure { AppLog.put("扩展 manifest 解析失败: ${apk.name}", it) }
                .getOrNull() ?: continue
            results += runCatching { loadApk(apk, manifest) }
                .getOrElse { e ->
                    // 预检之后的意外异常收敛为该包的 NotLoaded(Failed), 不影响其他扩展
                    AppLog.put("扩展加载出错: ${manifest.packageName}", e)
                    MangaExtension.NotLoaded(
                        name = manifest.packageName,
                        pkgName = manifest.packageName,
                        versionName = manifest.versionName.orEmpty(),
                        versionCode = manifest.versionCode,
                        isShared = false,
                        contentWarning = ModelContentWarning.SAFE,
                        signatures = emptyList(),
                        reason = MangaExtension.NotLoaded.Reason.Failed(
                            e.message ?: e::class.simpleName.orEmpty(),
                            e.stackTraceToString(),
                        ),
                    )
                }
            seen += manifest.packageName
        }
        // 已卸载扩展: 关闭并移除装载缓存 (classloader/dex2jar 产物随之可回收)
        loadCache.keys.filter { it !in seen }.forEach { pkg ->
            loadCache.remove(pkg)?.loaded?.classLoader?.let { loader ->
                runCatching { loader.close() }
            }
        }
        results
    }

    /**
     * 单扩展装载: 信任预检 (未信任/无签名不进 dex2jar) → 缓存复用 → JvmExtensionLoader 重载。
     */
    private fun loadApk(apk: File, manifest: ApkManifest): MangaExtension.Installed {
        val pkgName = manifest.packageName

        val signatures = runCatching { ApkSignatures.sha256HexList(apk) }
            .getOrElse { throw NoStackTraceException("扩展签名提取失败: ${it.message}") }

        fun notLoaded(
            reason: MangaExtension.NotLoaded.Reason,
        ) = MangaExtension.NotLoaded(
            name = manifest.label?.substringAfter("Aniyomi: ")?.substringAfter("Tachiyomi: ")
                ?: pkgName,
            pkgName = pkgName,
            versionName = manifest.versionName.orEmpty(),
            versionCode = manifest.versionCode,
            isShared = false,
            contentWarning = ModelContentWarning.SAFE,
            signatures = signatures,
            reason = reason,
        )

        if (signatures.isEmpty()) {
            AppLog.put("扩展未签名: $pkgName")
            return notLoaded(MangaExtension.NotLoaded.Reason.Unsigned)
        }
        // 信任判定先于 dex2jar: 未信任扩展 (含默认仓库指纹不命中且用户未确认) 不转换
        if (!TrustHelper.isTrusted(pkgName, manifest.versionCode, signatures)) {
            AppLog.put("扩展签名未受信任: $pkgName")
            return notLoaded(MangaExtension.NotLoaded.Reason.Untrusted(signatures.last()))
        }

        // apk 未变且仍受信任: 原样复用装载实例 (源实例保持不变, 免 dex2jar 重转)
        loadCache[pkgName]?.takeIf {
            it.apkLastModified == apk.lastModified() && it.apkLength == apk.length()
        }?.let { return it.loaded.toMangaExtension() }

        // 版本更新的旧产物失效: 清同包旧 jar 后重转
        cleanupStaleJars(pkgName)
        val jvm = JvmExtensionLoader.load(apk, desktopJarDir())
        when (jvm) {
            is JvmExtension.Loaded -> {
                loadCache.remove(pkgName)?.loaded?.classLoader?.let { loader ->
                    runCatching { loader.close() }
                }
                loadCache[pkgName] = CachedLoad(apk.lastModified(), apk.length(), jvm)
                return jvm.toMangaExtension()
            }

            is JvmExtension.NotLoaded -> return jvm.toMangaExtension(signatures)
        }
    }

    private fun JvmExtension.Loaded.toMangaExtension(): MangaExtension.Loaded =
        MangaExtension.Loaded(
            name = name,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = lang,
            contentWarning = when (contentWarning) {
                ContentWarning.SAFE -> ModelContentWarning.SAFE
                ContentWarning.MIXED -> ModelContentWarning.MIXED
                ContentWarning.NSFW -> ModelContentWarning.NSFW
            },
            isShared = false,
            signatures = signatures,
            pkgFactory = pkgFactory,
            sources = sources,
            animeSources = animeSources,
        )

    private fun JvmExtension.NotLoaded.toMangaExtension(
        signatures: List<String>,
    ): MangaExtension.NotLoaded =
        MangaExtension.NotLoaded(
            name = name,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            isShared = false,
            contentWarning = ModelContentWarning.SAFE,
            signatures = signatures,
            libVersion = libVersion,
            reason = when (reason) {
                JvmExtension.Reason.Malformed -> MangaExtension.NotLoaded.Reason.Malformed
                JvmExtension.Reason.UnsupportedLibVersion ->
                    MangaExtension.NotLoaded.Reason.UnsupportedLibVersion
                JvmExtension.Reason.Unsigned -> MangaExtension.NotLoaded.Reason.Unsigned
                JvmExtension.Reason.Failed -> MangaExtension.NotLoaded.Reason.Failed(
                    message ?: "加载失败",
                    stackTrace.orEmpty(),
                )
            },
        )

    // region 安装 / 更新 / 卸载

    /**
     * 安装骨架的桌面平台面: 元信息经 AXML 解析 + apksig 提取, 落盘到扩展目录
     * (无安装广播, 落盘后直接触发整表重扫)。
     */
    private val installer = object : ExtensionInstallScaffold() {

        override fun tempFile(pkgName: String): File =
            File(extDir, "$pkgName.$APK_EXTENSION.part")

        override fun readApkInfo(file: File): ExtensionApkInfo? {
            val manifest = runCatching { ApkManifestReader.read(file) }.getOrNull() ?: return null
            return ExtensionApkInfo(
                pkgName = manifest.packageName,
                versionCode = manifest.versionCode,
                isExtension = manifest.reqFeatures.any {
                    it == EXTENSION_FEATURE || it == ANIME_EXTENSION_FEATURE
                },
                signatures = runCatching { ApkSignatures.sha256HexList(file) }.getOrDefault(emptyList()),
            )
        }

        override fun readInstalledInfo(pkgName: String): ExtensionApkInfo? {
            val target = File(extDir, "$pkgName.$APK_EXTENSION")
            if (!target.isFile) return null
            return readApkInfo(target)
        }

        override fun placeApk(file: File, info: ExtensionApkInfo, replaced: Boolean) {
            val target = File(extDir, "${info.pkgName}.$APK_EXTENSION")
            extDir.mkdirs()
            if (!file.renameTo(target)) {
                file.copyTo(target, overwrite = true)
                file.delete()
            }
        }

        override fun onInstalled(pkgName: String) {
            // 无安装广播: 落盘后直接触发整表重扫 (Android 走私有扩展安装广播)
            MangaExtensionManager.reloadExtensions()
        }
    }

    override fun install(extension: MangaExtension.Available): Flow<InstallStep> =
        installer.install(extension)

    override fun cancelInstall(pkgName: String) {
        installer.cancelInstall(pkgName)
    }

    override fun uninstall(extension: MangaExtension.Installed) {
        // 桌面无共享扩展形态: 一律扩展目录内文件
        requireValidExtensionPackageName(extension.pkgName)
        val file = File(extDir, "${extension.pkgName}.$APK_EXTENSION")
        if (file.isFile && file.delete()) {
            loadCache.remove(extension.pkgName)?.loaded?.classLoader?.let { loader ->
                runCatching { loader.close() }
            }
            MangaExtensionManager.reloadExtensions()
        }
    }

    // endregion

    /** 同包旧版本 dex2jar 产物清理 (jar 名形如 `<pkg>-v<version>[.jar|-dex1.jar]`)。 */
    private fun cleanupStaleJars(pkgName: String) {
        val dir = desktopJarDir()
        dir.listFiles { file -> file.isFile && file.name.startsWith("$pkgName-v") }
            ?.forEach { it.delete() }
    }

    private fun desktopJarDir(): File = File(desktopAppRootDir(), "ext-jars")

    companion object {
        private const val APK_EXTENSION = "apk"

        // 与 Android 端 ExtensionLoader 的 feature 键一致 (预检 + 安装校验用;
        // JvmExtensionLoader 内部按同一契约识别)
        private const val EXTENSION_FEATURE = "tachiyomi.extension"
        private const val ANIME_EXTENSION_FEATURE = "tachiyomi.animeextension"
    }
}