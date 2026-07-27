package io.legado.desktop.constant

import java.util.jar.Manifest

/**
 * 桌面端应用版本信息 (替代 app 端 [io.legado.app.constant.AppConst.appInfo])。
 *
 * # 背景
 *
 * app 端 [io.legado.app.constant.AppConst.appInfo] 是 Android 扩展
 * (见 `app/.../constant/AppConstAndroid.kt`), 通过
 * `appCtx.packageManager.getPackageInfo(...)` 读 versionName/versionCode,
 * 依赖 appCtx (Android Context) 绑定, 进不了 commonMain/desktop。
 *
 * 桌面端无 PackageManager, 改为:
 * - [versionName]: 优先读 classpath 下 `META-INF/MANIFEST.MF` 的 `Implementation-Version`
 *   (jpackage 打包后的 app jar manifest 由 Gradle jar task 写入); 读不到则回落
 *   [FALLBACK_VERSION_NAME] (= `desktop/build.gradle.kts`
 *   `nativeDistributions.packageVersion` 默认值)
 * - [versionCode]: 桌面端无 `packageManager.versionCode`, 从 [versionName] 解析
 *   `major*10000+minor*100+patch` (与 app 端 versionCode 语义不同, 仅作占位), 解析失败回落 1
 *
 * # 与 app 端的差异
 *
 * - app 端 versionCode 为 Long (`PackageManager.longVersionCode`); 桌面端用 Int (无大版本号需求)
 * - app 端 AppInfo 还含 appVariant (签名校验, 桌面端无签名); 桌面端不暴露
 *
 * @see io.legado.app.constant.AppConstAndroid.kt (app 端 appInfo 来源)
 */
object DesktopAppInfo {

    /**
     * 应用版本名 (如 "1.0.0"), 供 About 页显示 +
     * [io.legado.desktop.help.DesktopAppUpdate] 版本比对。
     *
     * 读取优先级:
     * 1. `META-INF/MANIFEST.MF` 的 `Implementation-Version` (jpackage 产物 / Gradle jar task 写入)
     * 2. [FALLBACK_VERSION_NAME] 硬编码回落 (与 `desktop/build.gradle.kts` packageVersion 默认值一致)
     *
     * 注: `getResourceAsStream("/META-INF/MANIFEST.MF")` 返回 classpath 上首个 manifest,
     * 开发期 (:desktop:run) 可能命中依赖 jar 的 manifest; 此时 Implementation-Version 多半缺失,
     * 自动回落到 [FALLBACK_VERSION_NAME], 不影响正确性。
     */
    val versionName: String by lazy {
        readManifestImplementationVersion() ?: FALLBACK_VERSION_NAME
    }

    /**
     * 应用版本号 (Int), 桌面端无 PackageManager, 从 [versionName] 解析 `major*10000+minor*100+patch`。
     *
     * 例: "1.0.0" → 10000, "3.25.7" → 325007。解析失败回落 1。
     * 注: 与 app 端 Long versionCode 语义不同, 仅作占位, 当前未在桌面端被使用。
     */
    val versionCode: Int by lazy {
        val parts = versionName.split(".").mapNotNull { it.toIntOrNull() }
        when (parts.size) {
            3 -> parts[0] * 10000 + parts[1] * 100 + parts[2]
            2 -> parts[0] * 10000 + parts[1] * 100
            1 -> parts[0] * 10000
            else -> 1
        }
    }

    /**
     * 读 classpath 下首个 `META-INF/MANIFEST.MF` 的 `Implementation-Version`。
     *
     * 返回 null 的情形:
     * - 无 manifest 资源 (开发期 :desktop:run classpath 上 manifest 不可达)
     * - manifest 未设 `Implementation-Version` (Gradle jar task 未配 / 依赖 jar 的 manifest)
     * - 值为 "unspecified" (Gradle `project.version` 未设时 jar task 写入的默认值)
     */
    private fun readManifestImplementationVersion(): String? {
        return runCatching {
            DesktopAppInfo::class.java.getResourceAsStream("/META-INF/MANIFEST.MF")?.use { stream ->
                Manifest(stream).mainAttributes.getValue("Implementation-Version")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "unspecified" }
    }

    private const val FALLBACK_VERSION_NAME = "1.0.0"
}
