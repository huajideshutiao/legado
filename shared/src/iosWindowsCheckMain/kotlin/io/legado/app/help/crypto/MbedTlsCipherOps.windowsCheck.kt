package io.legado.app.help.crypto

/**
 * Windows klib 校验专用 stub (源根 src/iosWindowsCheckMain, 仅非 mac 主机的
 * iosArm64Main/iosSimulatorArm64Main 编译挂载): 真实 actual 随 nativeInterop stage
 * 进 leaf (MbedTlsCipherOps.native.kt, 依赖 mbedtls cinterop), Windows 上 cinterop 无法生成,
 * 由本 stub 顶替, 使 nativeMain 的 expect/actual 配对通过 klib 语法/签名校验。
 * 本代码不会在真实设备执行, 函数体直接抛错防误用。
 */
internal actual object MbedTlsCipherOps {

    actual fun encrypt(
        algorithm: String,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray = error("MbedTlsCipherOps stub: 仅 Windows klib 校验用, 不应被执行")

    actual fun decrypt(
        algorithm: String,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray = error("MbedTlsCipherOps stub: 仅 Windows klib 校验用, 不应被执行")
}
