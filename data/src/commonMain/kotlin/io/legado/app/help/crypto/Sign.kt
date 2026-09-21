package io.legado.app.help.crypto

/**
 * KMP 抽象的签名门面。
 *
 * JVM/Android 半区 actual 由 `jvmAndAndroidMain/io/legado/app/help/crypto/Sign.kt`
 * 中的 `Sign` 类实现，继承 hutool `cn.hutool.crypto.asymmetric.Sign` 提供完整能力；
 * iOS / 鸿蒙 半区可提供 actual 实现或抛 UnsupportedOperationException 降级。
 *
 * 接口签名对齐 jvmAndAndroidMain 既有 public 方法（仅 setPrivateKey/setPublicKey 链式调用）。
 * 签名/验签本体走 hutool 父类 open 方法（verify/sign 等），跨端调用方需走 JVM 半区；
 * commonMain interface 仅暴露跨端必须的 key 装载接口。
 */
interface Sign {

    fun setPrivateKey(key: ByteArray): Sign

    fun setPrivateKey(key: String): Sign

    fun setPublicKey(key: ByteArray): Sign

    fun setPublicKey(key: String): Sign
}
