package io.legado.app.help.file

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.File

/**
 * [AppFilesDir] 的鸿蒙 (OHOS) 真实实现。
 *
 * KP7+: 真实鸿蒙沙盒路径经 [io.legado.app.napi.OhosNativeBridge] napi 桥接注入
 * (ArkTS EntryAbility 取 `context.filesDir`/`context.cacheDir` 后经 @CName 推送);
 * 文件 I/O 仍用 [kotlin.io.File] (linuxArm64 POSIX fs, 无需桥接 `@ohos.file.fs`)。
 *
 * # 路径解析
 * - [filesDir]: 优先 [OhosNativeBridge.getFilesDir] (真实沙盒, 如
 *   `/data/storage/el2/base/haps/entry/files`); 未注入回退 `{user.dir}/legado_data/files`
 *   (与 [OhosDatabaseDriver] defaultDbPath 退化路径同源, 兼容 napi 未接入阶段)
 * - [cacheDir]: 优先 [OhosNativeBridge.getCacheDir]; 未注入回退 `{user.dir}/legado_data/cache`
 * - [externalFilesDir]: OHOS 无外部存储概念, 返回 null (与 iOS 端 / 桌面 jvm 一致)
 * - [externalCacheDir]: OHOS 无外部存储概念, 返回 null (调用方回退到 [cacheDir])
 *
 * # 与 iOS 端差异
 * - iOS 用 NSSearchPathForDirectoriesInDomains 取沙盒 Documents/Caches 真实路径;
 *   鸿蒙经 napi 桥接取 `context.filesDir`/`cacheDir` 真实沙盒路径 (未注入才回退 POSIX user.dir)
 * - iOS 沙盒保证 Documents/Caches 目录存在; 鸿蒙真实沙盒目录由系统保证存在,
 *   回退路径需显式 [File.mkdirs] 确保目录存在
 *
 * # 共享实例
 * 启动早期 [registerOhosAppFilesDir] 注入一次, 之后 [AppFilesDirs.get] 全局复用。
 *
 * 模式参考 [io.legado.app.help.config.AppConfigProviders] /
 * `registerAndroidMediaNotificationProvider` / [OhosDatabaseDriver] defaultDbPath 退化路径。
 */
class OhosAppFilesDir : AppFilesDir {

    /**
     * POSIX user.dir 派生回退路径 (napi 路径未注入时用, 构造期 mkdirs 一次, 与历史行为一致)。
     * 真实鸿蒙沙盒路径优先读 [OhosNativeBridge.getFilesDir] / [OhosNativeBridge.getCacheDir]。
     */
    private val fallbackFilesDir: String = resolveDir("legado_data/files")

    /** POSIX 回退 cacheDir (同 [fallbackFilesDir])。 */
    private val fallbackCacheDir: String = resolveDir("legado_data/cache")

    /**
     * 应用沙盒 filesDir: 优先 [OhosNativeBridge.getFilesDir] (ArkTS 注入真实沙盒路径),
     * 未注入回退 [fallbackFilesDir] (POSIX user.dir 派生, 兼容 napi 未接入阶段)。
     *
     * 用计算 getter (非一次性 `val`): 路径注入可能晚于本对象构造
     * (LegadoNativeExports init 触发 registerOhosProviders 时 ArkTS 尚未注入),
     * 访问时实时读取保证消费方拿到注入后的真实路径。
     */
    override val filesDir: String
        get() = OhosNativeBridge.getFilesDir() ?: fallbackFilesDir

    /** 应用沙盒 cacheDir (同 [filesDir], 优先 [OhosNativeBridge.getCacheDir], 未注入回退)。 */
    override val cacheDir: String
        get() = OhosNativeBridge.getCacheDir() ?: fallbackCacheDir

    /** OHOS 无外部存储概念, 返回 null (与 iOS 端 / 桌面 jvm 一致)。 */
    override val externalFilesDir: String? = null

    /** OHOS 无外部缓存概念, 返回 null (调用方回退到 [cacheDir])。 */
    override val externalCacheDir: String? = null

    /**
     * 解析相对路径为绝对路径并确保目录存在。
     *
     * - base 取 `System.getProperty("user.dir")` (POSIX getcwd, Kotlin/Native linuxArm64 标准库支持)
     * - base 缺失时退化为 "." (当前工作目录), 与 [OhosDatabaseDriver] defaultDbPath 退化策略一致
     * - [File.mkdirs] 失败静默 (退化路径可能无写权限, 不阻断构造流程,
     *   后续真实 I/O 时再报错更易定位, 与 [OhosDatabaseDriver] dbFile.apply 同模式)
     */
    private fun resolveDir(relative: String): String {
        val base = runCatching { System.getProperty("user.dir") }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: "."
        val path = if (base.endsWith("/")) "$base$relative" else "$base/$relative"
        // 启动时确保目录存在 (与 iOS 沙盒保证 Documents/Caches 存在行为对齐)
        runCatching { File(path).mkdirs() }
        return path
    }
}

/**
 * 鸿蒙宿主启动早期注册 [AppFilesDir] 的真实实现。
 *
 * 调用时机: 鸿蒙 app 启动早期, 在任何 commonMain 代码调用 `AppFilesDirs.get()` 之前。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerOhosAppFilesDir() {
    AppFilesDirs.register(OhosAppFilesDir())
}
