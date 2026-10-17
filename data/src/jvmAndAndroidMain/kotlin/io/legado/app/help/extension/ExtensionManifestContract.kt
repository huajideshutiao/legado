package io.legado.app.help.extension

import io.legado.app.help.extension.model.ContentWarning

/**
 * 扩展 apk 识别契约 (Tachiyomi/Mihon/Aniyomi 系): feature 键、metadata 键、扩展库版本
 * 白名单、内容分级映射与源类名展开规则。
 *
 * 与 keiyoushi 仓库索引及 Tachiyomi 系扩展 apk 严格对齐, 键名与值不可自行增改。
 * metadata 读取方式各端不同 (Android = PackageManager Bundle, 桌面 = ApkManifestReader),
 * 读取后的判定一律消费本契约的纯规则, 两端不得另留平行副本。
 */
object ExtensionManifestContract {

    const val EXTENSION_FEATURE = "tachiyomi.extension"
    const val ANIME_EXTENSION_FEATURE = "tachiyomi.animeextension"

    const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    const val METADATA_NSFW = "tachiyomi.extension.nsfw"

    const val ANIME_METADATA_SOURCE_CLASS = "tachiyomi.animeextension.class"
    const val ANIME_METADATA_SOURCE_FACTORY = "tachiyomi.animeextension.factory"
    const val ANIME_METADATA_NSFW = "tachiyomi.animeextension.nsfw"

    const val METADATA_NAME = "tachiyomix.name"
    const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
    const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"

    /** 漫画扩展受支持的扩展库版本 (tachiyomix 契约)。 */
    val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

    /** 视频扩展受支持的扩展库版本 (Aniyomi 整数系; extensions-lib 无 15 tag 但 app 0.15 在用, 保留)。 */
    val ANIME_SUPPORTED_LIB_VERSIONS = listOf(14.0, 15.0, 16.0, 17.0)

    /** 扩展家族: 由 feature 键判定 (两 feature 不会同时声明)。 */
    enum class ExtensionKind { MANGA, ANIME }

    /** feature 声明是否指向扩展包 (漫画或视频)。 */
    fun isExtensionFeature(featureName: String?): Boolean =
        featureName == EXTENSION_FEATURE || featureName == ANIME_EXTENSION_FEATURE

    /** 两 feature 不会同时声明, 视频键优先判定; 缺声明返回 null, 由调用方决定残缺处理。 */
    fun extensionKindOf(reqFeatureNames: Collection<String?>): ExtensionKind? = when {
        ANIME_EXTENSION_FEATURE in reqFeatureNames -> ExtensionKind.ANIME
        EXTENSION_FEATURE in reqFeatureNames -> ExtensionKind.MANGA
        else -> null
    }

    /** 扩展名前缀剥离 ("Aniyomi: "/"Tachiyomi: ")。 */
    fun stripLabelPrefix(label: String): String =
        label.substringAfter("Aniyomi: ").substringAfter("Tachiyomi: ")

    /**
     * 扩展库版本解析: metadata 声明优先 (0 视为未声明), 未声明回退 versionName 前两段
     * (视频扩展实测无 tachiyomix.* 元数据, 版本名即 lib 代系: "14.10"→14.0)。
     *
     * 声明值经 Float 最短十进制表示转 Double ("1.4" 路径), 与白名单字面量精确相等;
     * 直接浮点扩展会得到 1.399999976... 一类值而误判不受支持。
     */
    fun parseLibVersion(declaredLib: Float?, versionName: String?): Double? {
        return declaredLib
            ?.takeUnless { it == 0.0f }
            ?.toString()
            ?.toDouble()
            ?: versionName?.substringBeforeLast('.')?.toDoubleOrNull()
    }

    /**
     * 扩展库版本白名单筛选: 通过返回该版本, 未知或不受支持返回 null。
     *
     * 返回非空即代表该版本可用, 调用方分支后可直接取用非空值, 不需要默认值兜底。
     */
    fun supportedLibVersion(kind: ExtensionKind, libVersion: Double?): Double? =
        libVersion?.takeIf { it in supportedLibVersions(kind) }

    /** 该家族受支持的扩展库版本白名单 (筛选与不受支持的提示共用同一来源)。 */
    fun supportedLibVersions(kind: ExtensionKind): List<Double> =
        if (kind == ExtensionKind.ANIME) ANIME_SUPPORTED_LIB_VERSIONS else SUPPORTED_LIB_VERSIONS

    /** 声明的内容分级映射: 1→MIXED, 2→NSFW, 其余→SAFE。 */
    fun contentWarningOfDeclared(declared: Int): ContentWarning = when (declared) {
        1 -> ContentWarning.MIXED
        2 -> ContentWarning.NSFW
        else -> ContentWarning.SAFE
    }

    /** 内容分级未声明时的 nsfw 标志回退: ==1 即 NSFW。 */
    fun isNsfwFlag(nsfwFlag: Int?): Boolean = nsfwFlag == 1

    /** 源类名展开: 分号分隔, "." 开头为扩展包内相对类名。 */
    fun expandSourceClassNames(sourceClasses: String, pkgName: String): List<String> =
        sourceClasses.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith(".")) pkgName + it else it }
}
