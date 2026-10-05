// JVM 版扩展加载器 (desktop): 真实扩展 APK → dex2jar 转 jar → ChildFirstURLClassLoader →
// 按 manifest 契约实例化 Source/AnimeSource。识别契约与 Android 端
// io.legado.app.help.extension.util.ExtensionLoader 逐字一致 (feature 键 / metadata 键 /
// lib 版本白名单 / 名称前缀剥离 / 相对类名补包 / SHA-256 签名)。
// dex→jar 与 manifest 解析方式参考 Suwayomi-Server AndroidCompat 实证实现 (de.femtopedia.dex2jar
// fork 保持 com.googlecode.d2j 包名; net.dongliu:apk-parser 还原 AXML)。
package io.legado.desktop.extension

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.legado.desktop.help.dex.DexJarConverter
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile

enum class ExtensionKind { MANGA, ANIME }

enum class ContentWarning { SAFE, MIXED, NSFW }

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

    /** 视频扩展受支持的扩展库版本 (Aniyomi 整数系)。 */
    private val ANIME_SUPPORTED_LIB_VERSIONS = listOf(14.0, 15.0, 16.0, 17.0)

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

        // 两 feature 不会同时声明, 视频键优先判定 (与 Android 端 extensionKindOf 一致)
        val kind = when {
            ANIME_EXTENSION_FEATURE in manifest.reqFeatures -> ExtensionKind.ANIME
            EXTENSION_FEATURE in manifest.reqFeatures -> ExtensionKind.MANGA
            else -> return notLoaded(JvmExtension.Reason.Malformed, "缺少扩展 feature 声明")
        }

        val metaData = manifest.metaData
        if (manifest.versionName.isNullOrBlank() || metaData.isEmpty()) {
            return notLoaded(JvmExtension.Reason.Malformed, "缺少 versionName 或 metadata")
        }

        // metadata 未声明时回退 versionName 前两段 (视频扩展实测无 tachiyomix.* 元数据: "14.10"→14.0)
        val libVersion = metaData[METADATA_EXTENSION_LIB]?.toFloatOrNull()
            ?.takeUnless { it == 0.0f }
            ?.toDouble()
            ?: manifest.versionName.substringBeforeLast('.').toDoubleOrNull()
        val supportedVersions =
            if (kind == ExtensionKind.ANIME) ANIME_SUPPORTED_LIB_VERSIONS else SUPPORTED_LIB_VERSIONS
        if (libVersion == null || libVersion !in supportedVersions) {
            return notLoaded(
                JvmExtension.Reason.UnsupportedLibVersion,
                "扩展库版本 $libVersion 不受支持, 仅支持 ${supportedVersions.joinToString()}",
                libVersion = libVersion,
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
            metaData.containsKey(METADATA_CONTENT_WARNING) -> when (metaData[METADATA_CONTENT_WARNING]?.toIntOrNull()) {
                1 -> ContentWarning.MIXED
                2 -> ContentWarning.NSFW
                else -> ContentWarning.SAFE
            }
            kind == ExtensionKind.ANIME &&
                metaData[ANIME_METADATA_NSFW]?.toIntOrNull() == 1 -> ContentWarning.NSFW
            kind == ExtensionKind.MANGA && metaData[METADATA_NSFW]?.toIntOrNull() == 1 -> ContentWarning.NSFW
            else -> ContentWarning.SAFE
        }

        val extName = metaData[METADATA_NAME]
            ?: manifest.label
                ?.substringAfter("Aniyomi: ")
                ?.substringAfter("Tachiyomi: ")
            ?: pkgName

        // APK → dex → jar (Suwayomi PackageTools.dex2jar 同参数面; fork 的 open 无多 dex 重载,
        // 多 dex 各转一个 jar, 一并进 classloader)
        val jarFiles = try {
            dex2jar(apkFile, jarOutputDir, "${pkgName}-v${manifest.versionName}")
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
            if (kind == ExtensionKind.ANIME) ANIME_METADATA_SOURCE_CLASS else METADATA_SOURCE_CLASS
        val sourceClasses = metaData[sourceClassKey]
        if (sourceClasses.isNullOrBlank()) {
            classLoader.close()
            return notLoaded(JvmExtension.Reason.Malformed, "扩展缺少源类声明", libVersion = libVersion)
        }

        // 分号分隔, "." 开头为扩展包内相对类名; 漫画/视频源按实例类型分流 (与 Android 端逐字一致)
        val mangaSources = ArrayList<Source>()
        val animeSources = ArrayList<AnimeSource>()
        sourceClasses
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { sourceClass ->
                val className = if (sourceClass.startsWith(".")) {
                    pkgName + sourceClass
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
            if (kind == ExtensionKind.ANIME) ANIME_METADATA_SOURCE_FACTORY else METADATA_SOURCE_FACTORY
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
            DexJarConverter.convertDex(bytes, jarFile)
            jarFile
        }
    }

    private fun defaultJarDir(): File =
        File(System.getProperty("java.io.tmpdir"), "legado-ext-jars")
}
