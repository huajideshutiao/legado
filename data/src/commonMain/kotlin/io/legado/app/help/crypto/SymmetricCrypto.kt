package io.legado.app.help.crypto

/**
 * KMP 抽象的对称加解密门面。
 *
 * JVM/Android 半区 actual 由 `jvmAndAndroidMain/io/legado/app/help/crypto/SymmetricCryptoAndroid.kt`
 * 中的 `SymmetricCryptoAndroid` 类实现，继承 hutool `cn.hutool.crypto.symmetric.SymmetricCrypto`
 * 提供完整能力；iOS / 鸿蒙 半区可提供 actual 实现或抛 UnsupportedOperationException 降级。
 *
 * 接口签名对齐 jvmAndAndroidMain 既有 public 方法中跨平台可用的子集：
 * - `encrypt(ByteArray)` / `encrypt(String)`（String 按 UTF-8，对齐 hutool encrypt(String)）
 * - `encryptHex(ByteArray)` / `encryptHex(String)`（小写 hex，对齐 hutool HexUtil.encodeHexStr）
 * - `encryptBase64(ByteArray)` / `encryptBase64(String)` / `encryptBase64(String, charsetName?)`
 * - `decrypt(String)`（兼容 hex 与 base64 密文形态）
 *
 * JVM 半区独有的 `encryptBase64(InputStream)` / `encryptBase64(String, java.nio.charset.Charset?)`
 * 保留为 jvmAndAndroidMain 实现类的附加成员（hutool 父类 override），不暴露到 commonMain，
 * 避免 commonMain 引用 java.nio.charset.Charset。
 *
 * 注：`charset: String?` 接收 charset 名字（如 "UTF-8"），由 actual 实现自行解析；
 * jvmAndAndroidMain 实现内 `encrypt(data, charset)` 仍走 hutool `SymmetricCrypto.encrypt(String, String)`
 * 重载，行为零变化。
 *
 * interface 方法不定义默认值（Kotlin 规则：override 不能重新定义默认值），
 * 由 jvmAndAndroidMain 实现类保留原 `charset: String? = null` 默认值。
 */
interface SymmetricCrypto {

    fun encrypt(data: ByteArray): ByteArray

    fun encrypt(data: String): ByteArray

    fun encryptHex(data: ByteArray): String

    fun encryptHex(data: String): String

    fun encryptBase64(data: ByteArray): String

    fun encryptBase64(data: String, charset: String?): String

    fun encryptBase64(data: String): String

    fun decrypt(data: String): ByteArray
}
