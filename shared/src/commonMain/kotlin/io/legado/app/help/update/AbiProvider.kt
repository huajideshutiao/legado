package io.legado.app.help.update

/**
 * 跨平台 ABI 提供接口（供 [AppUpdateShared] 注入 SUPPORTED_ABIS）。
 *
 * app 端返回 `android.os.Build.SUPPORTED_ABIS`; 其它端走 [AppUpdateEnvironment.supportedAbis]
 * (desktop 取 `os.arch`, iOS/鸿蒙真机恒 arm64)。原始值由 [AbiTokens] 归一化后参与资产匹配。
 */
interface AbiProvider {
    val supportedAbis: Array<String>
}
