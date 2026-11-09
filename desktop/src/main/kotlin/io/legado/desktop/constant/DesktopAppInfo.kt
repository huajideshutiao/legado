package io.legado.desktop.constant

import io.legado.app.help.versionNameToVersionCode
import io.legado.desktop.constant.DesktopAppInfo.FALLBACK_VERSION_NAME
import io.legado.desktop.constant.DesktopAppInfo.versionCode
import io.legado.desktop.constant.DesktopAppInfo.versionName
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
 * - [versionCode]: 桌面端无 `packageManager.versionCode`, 从 [versionName] 用
 *   [versionNameToVersionCode] 解析成单调版本号 (与 iOS/鸿蒙 同一编码, 版本越新值越大,
 *   跨年不受影响; 取值数值空间与 app 端的 10000+提交数 不同), 解析不出时回落 1;
 *   供崩溃日志版本字段与默认数据按版本推进使用
 *
 * # 与 app 端的差异
 *
 * - app 端 versionCode 为 Long (`PackageManager.longVersionCode`); 桌面端同为 Long
 *   (位槽编码的取值在 1e10 量级, Int 装不下)
 * - app 端 AppInfo 还含 appVariant (签名校验, 桌面端无签名); 桌面端不暴露
 *
 * @see io.legado.app.constant.AppConstAndroid.kt (app 端 appInfo 来源)
 */
object DesktopAppInfo {

    /**
     * 应用版本名 (如 "1.0.0"), 供 About 页显示 + shared 检查更新链路
     * ([io.legado.app.help.update.AppUpdateEnvironment] / [io.legado.desktop.help.registerDesktopAppUpdate]) 版本比对。
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
     * 应用版本号 (Long), 桌面端无 PackageManager, 从 [versionName] 用 [versionNameToVersionCode]
     * 解析成单调版本号 —— 与 iOS/鸿蒙 同一编码: 三端写入的键 `appVersionCode` 随备份在三端之间走,
     * 取值口径必须相同, 否则恢复别端备份后版本门会把"更新"判成"已记过"。
     *
     * 例: "1.0.0" → 10000000000, "3.26.081614" → 32608161400, "3.27.010112" → 32701011200
     * (跨年仍是递增)。
     * 注: 与 app 端 10000+提交数 的数值空间不同, 但同为版本越新值越大 (各端只与自己那份记录比)。
     * 解析不出 (版本名无任何数字段) 时回落 1, 保证首启仍会补一次默认数据。使用处: 崩溃日志的
     * 版本字段 ([io.legado.desktop.help.DesktopCrashHandler]) 与默认数据按版本推进的本地记录
     * ([io.legado.desktop.help.DesktopDefaultDataInit] 的 appVersionCode 比对)。
     */
    val versionCode: Long by lazy {
        versionNameToVersionCode(versionName).coerceAtLeast(1L)
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
