package io.legado.desktop.extension

import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.extension.ExtensionApkInfo
import io.legado.app.help.extension.ExtensionInstallScaffold
import io.legado.app.help.extension.EXTENSION_ICON_MAX_BYTES
import io.legado.app.help.extension.EXTENSION_ICON_MAX_PIXEL_SIZE
import io.legado.app.help.extension.MangaExtensionHost
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.help.extension.model.ContentWarning as ModelContentWarning
import io.legado.app.help.extension.model.InstallStep
import io.legado.app.help.extension.model.MangaExtension
import io.legado.app.help.extension.isValidExtensionPackageName
import io.legado.app.help.extension.requireValidExtensionPackageName
import io.legado.app.help.extension.trust.TrustHelper
import io.legado.app.help.file.desktopAppRootDir
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import net.dongliu.apk.parser.ApkFile
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

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

    /**
     * 插件包内启动图标 (PNG 字节): 扩展目录内 apk 的清单图标条目, 取密度最高的一条。
     *
     * 扩展 apk 的图标资源名被混淆 (如 `res/o-.png`), 只能经资源表反解, 故用已有的 apk-parser;
     * 文件缺失、清单无图标、图标解不出图像一律返回 null (调用方回落书源名首字占位)。
     *
     * 取图口径与安卓端一致: 清单图标条目是什么位图格式都收 (png/webp/jpg...), 统一编码成 PNG 落盘,
     * 不要求条目本身就是 png。自适应图标的 xml 是矢量/自适应描述, 桌面无对应渲染器, 跳过。
     *
     * 单条目读取与像素尺寸都有上限 ([EXTENSION_ICON_MAX_BYTES] / [EXTENSION_ICON_MAX_PIXEL_SIZE]):
     * 清单可以声明任意大的图标, 不限量地读条目、按声明尺寸解像素都会被拉到巨量分配。
     *
     * 解析/读条目任何一环出错都收敛为 null: 调用方在书源列表流里逐行取图, 异常漏出去会终结
     * 整条列表流 (此后不再随插件与书源库更新)。
     */
    override fun readExtensionIcon(pkgName: String): ByteArray? =
        runCatching { readIconPngBytes(pkgName) }
            .getOrElse { e ->
                // 落日志自身也要写文件, 再套一层: 日志失败同样不能让异常漏出本方法
                runCatching { AppLog.put("扩展图标读取失败: $pkgName", e) }
                null
            }

    /** [readExtensionIcon] 的实现体 (允许抛出, 由外层收容)。 */
    private fun readIconPngBytes(pkgName: String): ByteArray? {
        if (!isValidExtensionPackageName(pkgName)) return null
        val apk = File(extDir, "$pkgName.$APK_EXTENSION")
        if (!apk.isFile) return null
        return ApkFile(apk).use { file ->
            file.iconPathsByDensity().firstNotNullOfOrNull { path ->
                readApkEntryBounded(apk, path)?.let(::rasterToPng)
            }
        }
    }

    /**
     * apk 内图标条目的 zip 路径, 按密度从高到低 (密度越高越清晰)。
     *
     * apk-parser 的 getIconPaths() 只解出资源表里的路径与密度, 不读条目内容; 其替代品
     * getAllIcons() 会把每个候选条目整块读进内存, 用不了有上限的读取。
     */
    @Suppress("DEPRECATION")
    private fun ApkFile.iconPathsByDensity(): List<String> =
        getIconPaths().sortedByDescending { it.density }.map { it.path }

    /**
     * 读 apk 内单个条目, 超过 [EXTENSION_ICON_MAX_BYTES] 的一律不读。
     *
     * 不用 apk-parser 的 getFileData: 它把整条一次性读进内存; 这里先看中央目录声明的长度,
     * 再按实际读到的字节数兜一次 (声明值不可信时同样有上限)。
     */
    private fun readApkEntryBounded(apk: File, path: String): ByteArray? {
        return try {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(path) ?: return null
                if (entry.size > EXTENSION_ICON_MAX_BYTES) return null
                val buffer = ByteArray(16 * 1024)
                val out = ByteArrayOutputStream()
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (out.size() + read > EXTENSION_ICON_MAX_BYTES) return null
                        out.write(buffer, 0, read)
                    }
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 任意位图条目 → PNG 字节; 解不出图像或尺寸超过 [EXTENSION_ICON_MAX_PIXEL_SIZE] 的返回 null。
     *
     * 统一转 PNG 是为了两端只交一种格式: 取用侧据此按 PNG 判缓存完整性, 图标原格式不参与判定。
     * 尺寸先看图像头再解像素: 反过来会先按声明尺寸分配像素。
     */
    private fun rasterToPng(bytes: ByteArray): ByteArray? = runCatching {
        Data.makeFromBytes(bytes).use { data ->
            Codec.makeFromData(data).use { codec ->
                val withinLimit = codec.width in 1..EXTENSION_ICON_MAX_PIXEL_SIZE &&
                    codec.height in 1..EXTENSION_ICON_MAX_PIXEL_SIZE
                if (!withinLimit) {
                    null
                } else {
                    Image.makeFromEncoded(bytes).use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)?.bytes
                    }
                }
            }
        }
    }.getOrNull()

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