package io.legado.app.help.file

import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

/**
 * [AppFilesDir] 的 iOS 真实实现。
 *
 * KP3: 用 NSFileManager + NSSearchPathForDirectoriesInDomains 取真实沙盒路径:
 * - [filesDir]: `Documents/` (iTunes 文件共享可见, 持久化, 用户可读写)
 * - [cacheDir]: `Library/Caches/` (系统可清理, 不备份到 iCloud)
 * - [externalFilesDir]: iOS 无外部存储概念, 返回 null (与桌面端 jvm 一致)
 * - [externalCacheDir]: iOS 无外部存储概念, 返回 null (调用方回退到 [cacheDir])
 *
 * 路径解析参考 Apple 官方文档:
 * - https://developer.apple.com/documentation/foundation/filemanager
 * - https://developer.apple.com/library/archive/documentation/FileManagement/Conceptual/FileSystemProgrammingGuide/AccessingFilesandDirectories/AccessingFilesandDirectories.html
 *
 * 模式参考 [io.legado.app.help.config.AppConfigProviders] / `registerAndroidMediaNotificationProvider`。
 */
class IosAppFilesDir : AppFilesDir {

    /** Documents 目录 (沙盒内, 持久化, iTunes 文件共享可见)。 */
    override val filesDir: String = resolveSearchPath(NSDocumentDirectory)

    /** Library/Caches 目录 (沙盒内, 系统可清理, 不备份到 iCloud)。 */
    override val cacheDir: String = resolveSearchPath(NSCachesDirectory)

    /** iOS 无外部存储概念, 返回 null (与桌面端 jvm 一致)。 */
    override val externalFilesDir: String? = null

    /** iOS 无外部缓存概念, 返回 null (调用方回退到 [cacheDir])。 */
    override val externalCacheDir: String? = null

    private fun resolveSearchPath(directory: ULong): String {
        // NSSearchPathForDirectoriesInDomains 返回 NSArray<String>, 取首个匹配路径
        // iOS 沙盒保证 Documents/Caches 目录一定存在, 首个元素即绝对路径
        val paths = NSSearchPathForDirectoriesInDomains(
            directory = directory,
            domainMask = NSUserDomainMask,
            expandTilde = true
        )
        return paths.firstOrNull() as? String ?: run {
            // 兜底: Documents/Caches 路径解析失败时退化为 NSTemporaryDirectory (沙盒内临时目录)
            // 理论上不会走到此分支 (iOS 沙盒保证路径存在), 兜底防止 NSSearchPath 返回空数组导致 NPE
            NSTemporaryDirectory()
        }
    }
}

/**
 * iOS 宿主启动早期注册 [AppFilesDir] 的真实实现。
 *
 * 调用时机: iOS app 启动早期, 在任何 commonMain 代码调用 `AppFilesDirs.get()` 之前。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerIosAppFilesDir() {
    AppFilesDirs.register(IosAppFilesDir())
}
