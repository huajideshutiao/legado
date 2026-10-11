// JVM 版扩展加载器 (desktop): 真实扩展 APK → dex2jar 转 jar → ChildFirstURLClassLoader →
// 按 manifest 契约实例化 Source/AnimeSource。识别契约的键名/白名单/映射规则下沉在
// io.legado.app.help.extension.ExtensionManifestContract, 与 Android 端共用。
// dex→jar 与 manifest 解析方式参考 Suwayomi-Server AndroidCompat 实证实现 (de.femtopedia.dex2jar
// fork 保持 com.googlecode.d2j 包名; net.dongliu:apk-parser 还原 AXML)。
package io.legado.desktop.extension

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.legado.app.help.extension.ExtensionManifestContract
import io.legado.app.help.extension.isValidExtensionPackageName
import io.legado.app.help.extension.safeExtensionFileNameSegment
import io.legado.app.help.extension.model.ContentWarning
import io.legado.desktop.help.dex.DexJarConverter
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile

sealed interface JvmExtension {
    val pkgName: String
    val versionName: String
    val versionCode: Long

    data class Loaded(
        val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        val libVersion: Double,
        val lang: String,
        val contentWarning: ContentWarning,
        val pkgFactory: String?,
        val sources: List<Source>,
        val animeSources: List<AnimeSource>,
        val signatures: List<String>,
        val classLoader: URLClassLoader,
        /** dex2jar 产物 (多 dex 各一 jar); 调用方决定缓存/清理策略 */
        val jarFiles: List<File>,
    ) : JvmExtension

    data class NotLoaded(
        val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        val libVersion: Double?,
        val reason: Reason,
        val message: String? = null,
        val stackTrace: String? = null,
    ) : JvmExtension

    enum class Reason {
        Malformed,
        UnsupportedLibVersion,
        Unsigned,
        Failed,
    }
}

object JvmExtensionLoader {

    /**
     * 加载单个扩展 APK。
     *
     * @param jarOutputDir dex2jar 产物目录 (缺省落系统临时目录 legado-ext-jars)
     */
    fun load(apkFile: File, jarOutputDir: File = defaultJarDir()): JvmExtension {
        val manifest = ApkManifestReader.read(apkFile)
        val pkgName = manifest.packageName
        val versionName = manifest.versionName.orEmpty()

        fun notLoaded(
            reason: JvmExtension.Reason,
            message: String? = null,
            stackTrace: String? = null,
            libVersion: Double? = null,
        ) = JvmExtension.NotLoaded(
            name = pkgName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = manifest.versionCode,
            libVersion = libVersion,
            reason = reason,
            message = message,
            stackTrace = stackTrace,
        )

        // 包名直接参与 dex2jar 产物文件路径拼接: 非法包名不装载 (含路径分隔/上跳序列)
        if (!isValidExtensionPackageName(pkgName)) {
            return notLoaded(JvmExtension.Reason.Malformed, "非法扩展包名: $pkgName")
        }

        // 两 feature 不会同时声明, 视频键优先判定
        val kind = ExtensionManifestContract.extensionKindOf(manifest.reqFeatures)
            ?: return notLoaded(JvmExtension.Reason.Malformed, "缺少扩展 feature 声明")

        val metaData = manifest.metaData
        if (manifest.versionName.isNullOrBlank() || metaData.isEmpty()) {
            return notLoaded(JvmExtension.Reason.Malformed, "缺少 versionName 或 metadata")
        }

        // metadata 未声明时回退 versionName 前两段 (视频扩展实测无 tachiyomix.* 元数据: "14.10"→14.0)
        val declaredLibVersion = ExtensionManifestContract.parseLibVersion(
            metaData[ExtensionManifestContract.METADATA_EXTENSION_LIB]?.toFloatOrNull(),
            manifest.versionName,
        )
        val libVersion = ExtensionManifestContract.supportedLibVersion(kind, declaredLibVersion)
        if (libVersion == null) {
            return notLoaded(
                JvmExtension.Reason.UnsupportedLibVersion,
                "扩展库版本 $declaredLibVersion 不受支持, " +
                    "仅支持 ${ExtensionManifestContract.supportedLibVersions(kind).joinToString()}",
                libVersion = declaredLibVersion,
            )
        }

        // desktop 测试级签名提取: 只产出与 Android 端 getSignatures 同口径的 SHA-256, 不判信任
        val signatures = try {
            ApkSignatures.sha256HexList(apkFile)
        } catch (e: Exception) {
            return notLoaded(
                JvmExtension.Reason.Unsigned,
                "扩展签名提取失败: ${e.message}",
                e.stackTraceToString(),
                libVersion,
            )
        }
        if (signatures.isEmpty()) {
            return notLoaded(JvmExtension.Reason.Unsigned, "扩展未签名", libVersion = libVersion)
        }

        val contentWarning = when {
            metaData.containsKey(ExtensionManifestContract.METADATA_CONTENT_WARNING) ->
                ExtensionManifestContract.contentWarningOfDeclared(
                    metaData[ExtensionManifestContract.METADATA_CONTENT_WARNING]?.toIntOrNull() ?: 0
                )
            kind == ExtensionManifestContract.ExtensionKind.ANIME &&
                ExtensionManifestContract.isNsfwFlag(metaData[ExtensionManifestContract.ANIME_METADATA_NSFW]?.toIntOrNull()) ->
                ContentWarning.NSFW
            kind == ExtensionManifestContract.ExtensionKind.MANGA &&
                ExtensionManifestContract.isNsfwFlag(metaData[ExtensionManifestContract.METADATA_NSFW]?.toIntOrNull()) ->
                ContentWarning.NSFW
            else -> ContentWarning.SAFE
        }

        val extName = metaData[ExtensionManifestContract.METADATA_NAME]
            ?: manifest.label
                ?.let { ExtensionManifestContract.stripLabelPrefix(it) }
            ?: pkgName

        // APK → dex → jar (Suwayomi PackageTools.dex2jar 同参数面; fork 的 open 无多 dex 重载,
        // 多 dex 各转一个 jar, 一并进 classloader)
        val jarFiles = try {
            dex2jar(apkFile, jarOutputDir, "$pkgName-v${safeExtensionFileNameSegment(versionName)}")
        } catch (e: Exception) {
            return notLoaded(
                JvmExtension.Reason.Failed,
                "dex2jar 转换失败: ${e.message}",
                e.stackTraceToString(),
                libVersion,
            )
        }

        val classLoader = try {
            ChildFirstURLClassLoader(
                jarFiles.map { it.toURI().toURL() }.toTypedArray(),
                JvmExtensionLoader::class.java.classLoader,
            )
        } catch (e: Exception) {
            return notLoaded(
                JvmExtension.Reason.Failed,
                "扩展类加载器创建失败: ${e.message}",
                e.stackTraceToString(),
                libVersion,
            )
        }

        val sourceClassKey =
            if (kind == ExtensionManifestContract.ExtensionKind.ANIME) {
                ExtensionManifestContract.ANIME_METADATA_SOURCE_CLASS
            } else {
                ExtensionManifestContract.METADATA_SOURCE_CLASS
            }
        val sourceClasses = metaData[sourceClassKey]
        if (sourceClasses.isNullOrBlank()) {
            classLoader.close()
            return notLoaded(JvmExtension.Reason.Malformed, "扩展缺少源类声明", libVersion = libVersion)
        }

        // 分号分隔, "." 开头为扩展包内相对类名; 漫画/视频源按实例类型分流 (与 Android 端逐字一致)
        val mangaSources = ArrayList<Source>()
        val animeSources = ArrayList<AnimeSource>()
        ExtensionManifestContract.expandSourceClassNames(sourceClasses, pkgName)
            .forEach { className ->
                try {
                    when (val obj = Class.forName(className, false, classLoader)
                        .getDeclaredConstructor().newInstance()) {
                        is Source -> mangaSources += obj
                        is SourceFactory -> mangaSources += obj.createSources()
                        is AnimeSource -> animeSources += obj
                        is AnimeSourceFactory -> animeSources += obj.createSources()
                        else -> throw IllegalStateException("未知的源类类型: ${obj.javaClass}")
                    }
                } catch (e: Throwable) {
                    classLoader.close()
                    return notLoaded(
                        JvmExtension.Reason.Failed,
                        "扩展源实例化失败: $extName ($className): ${e.message}",
                        e.stackTraceToString(),
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
            if (kind == ExtensionManifestContract.ExtensionKind.ANIME) {
                ExtensionManifestContract.ANIME_METADATA_SOURCE_FACTORY
            } else {
                ExtensionManifestContract.METADATA_SOURCE_FACTORY
            }
        return JvmExtension.Loaded(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = manifest.versionCode,
            libVersion = libVersion,
            lang = lang,
            contentWarning = contentWarning,
            pkgFactory = metaData[factoryKey],
            sources = mangaSources,
            animeSources = animeSources,
            signatures = signatures,
            classLoader = classLoader,
            jarFiles = jarFiles,
        )
    }

    /**
     * dex → jar; 多 dex APK 每个 dex 条目各产一个 jar (转换参数面见 DexJarConverter.convertDex)。
     */
    private fun dex2jar(apkFile: File, outputDir: File, baseName: String): List<File> {
        outputDir.mkdirs()
        val dexBytes = ZipFile(apkFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && DexJarConverter.DEX_ENTRY_REGEX.matches(it.name.substringAfterLast('/')) }
                .sortedBy { it.name }
                .map { zip.getInputStream(it).use { input -> input.readBytes() } }
                .toList()
        }
        check(dexBytes.isNotEmpty()) { "APK 内无 classes.dex: ${apkFile.name}" }
        return dexBytes.mapIndexed { index, bytes ->
            val jarFile = File(outputDir, "$baseName${if (index == 0) "" else "-dex$index"}.jar")
            // 原子落地: 同包重扫/双开桌面实例不会写出半个 jar
            DexJarConverter.convertDexAtomically(bytes, jarFile)
            jarFile
        }
    }

    private fun defaultJarDir(): File =
        File(System.getProperty("java.io.tmpdir"), "legado-ext-jars")
}
