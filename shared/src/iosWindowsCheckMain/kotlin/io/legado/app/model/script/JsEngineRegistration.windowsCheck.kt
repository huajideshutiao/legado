package io.legado.app.model.script

import io.legado.app.help.image.ImageOps

/**
 * Windows klib 校验专用 stub (源根 src/iosWindowsCheckMain, 仅非 mac 主机的
 * iosArm64Main/iosSimulatorArm64Main 编译挂载): 真实 actual 在
 * RegisterNativeJsEngines.native.kt (随 nativeInterop stage 进 leaf, 引用
 * [NativeJsEngine] 等依赖 quickjs cinterop 的类), Windows 上 cinterop 无法生成,
 * 由本 stub 顶替, 使 nativeMain 的 expect/actual 配对通过 klib 语法/签名校验。
 * 本代码不会在真实设备执行 (iOS 实际注册走 leaf 内的真实实现)。
 */
internal actual fun registerNativeJsEngines(imageOps: ImageOps) {
    // 仅满足 expect/actual 配对; 不注册任何引擎
}
