package io.legado.app.help.update

/**
 * 跨平台 ABI 提供接口（供 [AppUpdateShared] 注入 SUPPORTED_ABIS）。
 *
 * app 端实现返回 `android.os.Build.SUPPORTED_ABIS`,
 * desktop 端返回空数组或当前 JVM 架构, iOS/鸿蒙视端需求实现。
 */
interface AbiProvider {
    val supportedAbis: Array<String>
}
