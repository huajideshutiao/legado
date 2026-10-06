package io.legado.app.help.extension.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.extension.ExtensionApkInfo
import io.legado.app.help.extension.isValidExtensionPackageName
import io.legado.app.help.extension.model.ContentWarning
import io.legado.app.help.extension.model.MangaExtension
import io.legado.app.help.extension.requireValidExtensionPackageName
import io.legado.app.help.extension.trust.TrustHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 漫画/视频扩展加载器。扩展分两类:
 *
 * 1. 共享扩展: 经系统包安装器安装, 其他 Tachiyomi 系应用也可识别;
 * 2. 私有扩展: apk 落在本应用 filesDir/exts/<pkg>.ext (只读), 仅本应用可用。
 *
 * 同包名两类并存时, versionCode 高者生效; install 校验不允许降级且要求同签名。
 *
 * 漫画 (Mihon/Tachiyomi 系, feature=tachiyomi.extension) 与视频 (Aniyomi 系,
 * feature=tachiyomi.animeextension) 共用一套装载流程, 按 feature/metadata 键区分:
 * 视频扩展无 tachiyomix.* 元数据时 lib 版本回退 versionName 前两段 ("14.10"→14.0)。
 */
internal object ExtensionLoader {

    private const val EXTENSION_FEATURE = "tachiyomi.extension"
    private const val ANIME_EXTENSION_FEATURE = "tachiyomi.animeextension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
    private const val ANIME_METADATA_SOURCE_CLASS = "tachiyomi.animeextension.class"
    private const val ANIME_METADATA_SOURCE_FACTORY = "tachiyomi.animeextension.factory"
    private const val ANIME_METADATA_NSFW = "tachiyomi.animeextension.nsfw"

    private const val METADATA_NAME = "tachiyomix.name"
    private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
    private const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"

    /** 漫画扩展受支持的扩展库版本 (tachiyomix 契约)。 */
    private val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

    /** 视频扩展受支持的扩展库版本 (Aniyomi 整数系; extensions-lib 无 15 tag 但 app 0.15 在用, 保留)。 */
    private val ANIME_SUPPORTED_LIB_VERSIONS = listOf(14.0, 15.0, 16.0, 17.0)

    /** 扩展家族: 由 feature 键判定 (两 feature 不会同时声明)。 */
    private enum class ExtensionKind { MANGA, ANIME }

    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private const val PRIVATE_EXTENSION_EXTENSION = "ext"

    private fun getPrivateExtensionDir(context: Context) = File(context.filesDir, "exts")

    /**
     * 扩展安装包元信息 (覆盖校验面)。Android 侧由 PackageManager 查询, 与桌面端的
     * ApkManifestReader + ApkSignatures 同口径 (包名/versionCode/签名 SHA-256 小写 hex)。
     */
    fun archiveApkInfo(context: Context, file: File): ExtensionApkInfo? =
        getArchivePackageInfo(context, file)?.toInstallInfo()

    /** 已安装同包扩展的元信息 (共享版/私有版按 versionCode 取高者); 未安装返回 null。 */
    fun installedApkInfo(context: Context, pkgName: String): ExtensionApkInfo? =
        getExtensionPackageInfoFromPkgName(context, pkgName)?.toInstallInfo()

    private fun PackageInfo.toInstallInfo() = ExtensionApkInfo(
        pkgName = packageName,
        versionCode = PackageInfoCompat.getLongVersionCode(this),
        isExtension = isPackageAnExtension(this),
        signatures = getSignatures(this).orEmpty(),
    )

    /**
     * 将已通过 `checkExtensionInstallable` 校验的扩展 apk 落为私有扩展文件
     * (Android 14+ 只读), 并按新增/覆盖通知重扫。
     */
    fun installPrivateExtensionFile(context: Context, file: File, pkgName: String, replaced: Boolean) {
        requireValidExtensionPackageName(pkgName)
        val target = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        try {
            target.delete()
            file.copyAndSetReadOnlyTo(target)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        if (replaced) {
            ExtensionInstallReceiver.notifyReplaced(context, pkgName)
        } else {
            ExtensionInstallReceiver.notifyAdded(context, pkgName)
        }
    }

    fun uninstallPrivateExtension(context: Context, pkgName: String) {
        requireValidExtensionPackageName(pkgName)
        if (File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION").delete()) {
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    fun getArchivePackageInfo(context: Context, file: File): PackageInfo? {
        return context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
    }

    /** 安装/更新广播是否指向一个扩展包; 包已不可查时返回 false。 */
    fun isExtensionPackage(context: Context, pkgName: String): Boolean {
        return try {
            isPackageAnExtension(context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS))
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 扫描全部扩展并并发加载。
     *
     * @param alreadyLoaded 已加载成功的扩展。apk 未变且仍通过全部校验时原样返回,
     * 源实例保持不变, 更新状态得以保留; 缺省则全部重新加载。
     */
    suspend fun loadExtensions(
        context: Context,
        alreadyLoaded: Map<String, MangaExtension.Loaded> = emptyMap(),
    ): List<MangaExtension.Installed> {
        val pkgManager = context.packageManager

        val installedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        }

        val sharedExtPkgs = installedPkgs
            .asSequence()
            .filter { isPackageAnExtension(it) }
            .map { ExtensionInfo(packageInfo = it, isShared = true) }

        val privateExtPkgs = getPrivateExtensionDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXTENSION }
            ?.mapNotNull {
                // Android 14+ 要求私有扩展文件只读
                if (it.canWrite()) {
                    it.setReadOnly()
                }

                val path = it.absolutePath
                pkgManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
                    ?.also { pkg -> pkg.applicationInfo?.fixBasePaths(path) }
            }
            ?.filter { isPackageAnExtension(it) }
            ?.map { ExtensionInfo(packageInfo = it, isShared = false) }
            ?: emptySequence()

        val extPkgs = (sharedExtPkgs + privateExtPkgs)
            // 同包名去重, 共享扩展默认优先
            .distinctBy { it.packageInfo.packageName }
            .mapNotNull { sharedPkg ->
                val privatePkg = privateExtPkgs
                    .singleOrNull { it.packageInfo.packageName == sharedPkg.packageInfo.packageName }
                selectExtensionPackage(sharedPkg, privatePkg)
            }
            .toList()

        if (extPkgs.isEmpty()) return emptyList()

        // 并发加载, 单个扩展失败只产出 NotLoaded(Failed), 不影响其他扩展
        return withContext(IoDispatcher) {
            extPkgs
                .map { info ->
                    async(start = CoroutineStart.LAZY) {
                        loadExtensionCatching(
                            context = context,
                            extensionInfo = info,
                            alreadyLoaded = alreadyLoaded[info.packageInfo.packageName],
                        )
                    }
                }
                .awaitAll()
        }
    }

    fun getExtensionPackageInfoFromPkgName(context: Context, pkgName: String): PackageInfo? {
        // 包名参与私有扩展文件路径拼接, 非法包名直接视为不存在
        if (!isValidExtensionPackageName(pkgName)) return null
        val privateExtensionFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        val privatePkg = if (privateExtensionFile.isFile) {
            context.packageManager.getPackageArchiveInfo(privateExtensionFile.absolutePath, PACKAGE_FLAGS)
                ?.takeIf { isPackageAnExtension(it) }
                ?.also { it.applicationInfo?.fixBasePaths(privateExtensionFile.absolutePath) }
        } else {
            null
        }

        val sharedPkg = try {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
                .takeIf { isPackageAnExtension(it) }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        return selectExtensionPackage(
            sharedPkg?.let { ExtensionInfo(it, isShared = true) },
            privatePkg?.let { ExtensionInfo(it, isShared = false) },
        )?.packageInfo
    }

    /**
     * 单个扩展加载的兜底: 任何未预期的异常都收敛为该扩展的 NotLoaded(Failed)。
     */
    private suspend fun loadExtensionCatching(
        context: Context,
        extensionInfo: ExtensionInfo,
        alreadyLoaded: MangaExtension.Loaded? = null,
    ): MangaExtension.Installed {
        return try {
            loadExtension(context, extensionInfo, alreadyLoaded)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            val pkgInfo = extensionInfo.packageInfo
            AppLog.put("扩展加载出错: ${pkgInfo.packageName}", e)
            MangaExtension.NotLoaded(
                name = pkgInfo.packageName,
                pkgName = pkgInfo.packageName,
                versionName = pkgInfo.versionName.orEmpty(),
                versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
                isShared = extensionInfo.isShared,
                contentWarning = ContentWarning.SAFE,
                signatures = getSignatures(pkgInfo).orEmpty(),
                reason = MangaExtension.NotLoaded.Reason.Failed(e.rootMessage, e.stackTraceToString()),
            )
        }
    }

    private suspend fun loadExtension(
        context: Context,
        extensionInfo: ExtensionInfo,
        alreadyLoaded: MangaExtension.Loaded? = null,
    ): MangaExtension.Installed {
        val pkgManager = context.packageManager
        val pkgInfo = extensionInfo.packageInfo
        val appInfo = pkgInfo.applicationInfo
        val metaData = appInfo?.metaData
        val pkgName = pkgInfo.packageName
        val kind = extensionKindOf(pkgInfo)

        val extName = metaData?.getString(METADATA_NAME)
            ?: appInfo?.let {
                pkgManager.getApplicationLabel(it).toString()
                    .substringAfter("Aniyomi: ").substringAfter("Tachiyomi: ")
            }
            ?: pkgName
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)
        val signatures = getSignatures(pkgInfo).orEmpty()
        val nsfwKey = if (kind == ExtensionKind.ANIME) ANIME_METADATA_NSFW else METADATA_NSFW
        val contentWarning = when {
            metaData == null -> ContentWarning.SAFE
            metaData.containsKey(METADATA_CONTENT_WARNING) -> {
                when (metaData.getInt(METADATA_CONTENT_WARNING)) {
                    1 -> ContentWarning.MIXED
                    2 -> ContentWarning.NSFW
                    else -> ContentWarning.SAFE
                }
            }
            metaData.getInt(nsfwKey) == 1 -> ContentWarning.NSFW
            else -> ContentWarning.SAFE
        }

        fun notLoaded(
            reason: MangaExtension.NotLoaded.Reason,
            libVersion: Double? = null,
        ) = MangaExtension.NotLoaded(
            name = extName,
            pkgName = pkgName,
            versionName = versionName.orEmpty(),
            versionCode = versionCode,
            isShared = extensionInfo.isShared,
            contentWarning = contentWarning,
            signatures = signatures,
            libVersion = libVersion,
            reason = reason,
        )

        if (appInfo == null || metaData == null) {
            AppLog.put("扩展缺少应用信息: $extName")
            return notLoaded(MangaExtension.NotLoaded.Reason.Malformed)
        }

        if (versionName.isNullOrEmpty()) {
            AppLog.put("扩展缺少 versionName: $extName")
            return notLoaded(MangaExtension.NotLoaded.Reason.Malformed)
        }

        // 校验扩展库版本; metadata 未声明时回退 versionName 前两段 (视频扩展实测无 tachiyomix.* 元数据,
        // 版本名即 lib 代系: "14.10"→14.0)
        val libVersion = metaData.getFloat(METADATA_EXTENSION_LIB)
            .takeUnless { it == 0.0f }
            ?.toString()
            ?.toDouble()
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
        val supportedVersions =
            if (kind == ExtensionKind.ANIME) ANIME_SUPPORTED_LIB_VERSIONS else SUPPORTED_LIB_VERSIONS
        if (libVersion == null || libVersion !in supportedVersions) {
            AppLog.put(
                "扩展库版本 $libVersion 不受支持, 仅支持 ${supportedVersions.joinToString()}: $extName"
            )
            return notLoaded(MangaExtension.NotLoaded.Reason.UnsupportedLibVersion, libVersion)
        }

        if (signatures.isEmpty()) {
            AppLog.put("扩展未签名: $pkgName")
            return notLoaded(MangaExtension.NotLoaded.Reason.Unsigned, libVersion)
        } else if (!TrustHelper.isTrusted(pkgName, versionCode, signatures)) {
            AppLog.put("扩展签名未受信任: $pkgName")
            return notLoaded(MangaExtension.NotLoaded.Reason.Untrusted(signatures.last()), libVersion)
        }

        // 以上检查都很廉价, 以下涉及类加载; apk 未变且仍通过校验时沿用既有实例
        if (alreadyLoaded != null &&
            alreadyLoaded.versionCode == versionCode &&
            alreadyLoaded.isShared == extensionInfo.isShared
        ) {
            return alreadyLoaded
        }

        val classLoader = try {
            DelegateLastClassLoaderCompat(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Exception) {
            AppLog.put("扩展类加载器创建失败: $extName ($pkgName)", e)
            return notLoaded(
                MangaExtension.NotLoaded.Reason.Failed(e.rootMessage, e.stackTraceToString()),
                libVersion,
            )
        }

        val sourceClassKey =
            if (kind == ExtensionKind.ANIME) ANIME_METADATA_SOURCE_CLASS else METADATA_SOURCE_CLASS
        val sourceClasses = metaData.getString(sourceClassKey)
        if (sourceClasses.isNullOrBlank()) {
            AppLog.put("扩展缺少源类声明: $extName")
            return notLoaded(MangaExtension.NotLoaded.Reason.Malformed, libVersion)
        }

        // 分号分隔, "." 开头为扩展包内相对类名; 漫画/视频源按实例类型分流
        val mangaSources = ArrayList<Source>()
        val animeSources = ArrayList<AnimeSource>()
        sourceClasses
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { sourceClass ->
                val className = if (sourceClass.startsWith(".")) {
                    pkgInfo.packageName + sourceClass
                } else {
                    sourceClass
                }
                try {
                    when (val obj = Class.forName(className, false, classLoader)
                        .getDeclaredConstructor().newInstance()) {
                        is Source -> mangaSources += obj
                        is SourceFactory -> mangaSources += obj.createSources()
                        is AnimeSource -> animeSources += obj
                        is AnimeSourceFactory -> animeSources += obj.createSources()
                        else -> throw Exception("未知的源类类型: ${obj.javaClass}")
                    }
                } catch (e: Throwable) {
                    AppLog.put("扩展源实例化失败: $extName ($className)", e)
                    return notLoaded(
                        MangaExtension.NotLoaded.Reason.Failed(e.rootMessage, e.stackTraceToString()),
                        libVersion,
                    )
                }
            }

        val langs = when {
            animeSources.isNotEmpty() -> animeSources.map { it.lang }
            else -> mangaSources.filterIsInstance<CatalogueSource>().map { it.lang }
        }.toSet()
        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        val factoryKey =
            if (kind == ExtensionKind.ANIME) ANIME_METADATA_SOURCE_FACTORY else METADATA_SOURCE_FACTORY
        return MangaExtension.Loaded(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = lang,
            contentWarning = contentWarning,
            sources = mangaSources,
            animeSources = animeSources,
            pkgFactory = metaData.getString(factoryKey),
            isShared = extensionInfo.isShared,
            signatures = signatures,
        )
    }

    /**
     * 同包名共享/私有扩展按 versionCode 取高者。
     */
    private fun selectExtensionPackage(shared: ExtensionInfo?, private: ExtensionInfo?): ExtensionInfo? {
        when {
            private == null && shared != null -> return shared
            shared == null && private != null -> return private
            shared == null && private == null -> return null
        }

        return if (PackageInfoCompat.getLongVersionCode(shared!!.packageInfo) >=
            PackageInfoCompat.getLongVersionCode(private!!.packageInfo)
        ) {
            shared
        } else {
            private
        }
    }

    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        return pkgInfo.reqFeatures.orEmpty().any {
            it.name == EXTENSION_FEATURE || it.name == ANIME_EXTENSION_FEATURE
        }
    }

    /** 两 feature 不会同时声明, 视频键优先判定。 */
    private fun extensionKindOf(pkgInfo: PackageInfo): ExtensionKind {
        return if (pkgInfo.reqFeatures.orEmpty().any { it.name == ANIME_EXTENSION_FEATURE }) {
            ExtensionKind.ANIME
        } else {
            ExtensionKind.MANGA
        }
    }

    /**
     * 扩展签名集合, 每个签名输出 SHA-256 小写 hex。
     */
    fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            when {
                signingInfo == null -> null
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
                else -> signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
        return signatures?.map { sha256Hex(it.toByteArray()) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * getPackageArchiveInfo 产出的 ApplicationInfo 在部分系统版本上没有
     * sourceDir/publicSourceDir, 会破坏资源/图标加载, 这里回填 apk 路径。
     */
    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) {
            sourceDir = apkPath
        }
        if (publicSourceDir == null) {
            publicSourceDir = apkPath
        }
    }

    private fun File.copyAndSetReadOnlyTo(target: File) {
        target.parentFile?.mkdirs()
        inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        target.setReadOnly()
    }

    private data class ExtensionInfo(
        val packageInfo: PackageInfo,
        val isShared: Boolean,
    )
}

/**
 * 异常链最深一层 cause 的消息才是真正的错误。
 */
private val Throwable.rootMessage: String
    get() {
        val root = generateSequence(this) { it.cause }.last()
        return listOfNotNull(root::class.simpleName, root.message).joinToString(": ")
    }
