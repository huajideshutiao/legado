package io.legado.app.help.crypto

import cn.hutool.crypto.KeyUtil


// KMP 化: 原 `class Sign` 改名为 `SignAndroid` (仿 SymmetricCryptoAndroid 命名约定),
// 避免与 commonMain 同包同名 interface Sign 在合并编译时冲突
// (Kotlin 规则: 同包 class-like declarations 不能同名)。
// 方法体零变化: 仅追加 `: Sign` (commonMain interface) 实现标记 + 方法 `override`。
//
// @Keep 移除：shared 无 androidx.annotation 依赖，JS 桥反射保活改由 consumer-rules.pro -keep 登记（照 JsURL/StrResponse 先例）。
@Suppress("unused")
class SignAndroid(algorithm: String): cn.hutool.crypto.asymmetric.Sign(algorithm), Sign {

    override fun setPrivateKey(key: ByteArray): SignAndroid {
        setPrivateKey(KeyUtil.generatePrivateKey(algorithm, key))
        return this
    }

    override fun setPrivateKey(key: String): SignAndroid = setPrivateKey(key.encodeToByteArray())

    override fun setPublicKey(key: ByteArray): SignAndroid {
        setPublicKey(KeyUtil.generatePublicKey(algorithm, key))
        return this
    }

    override fun setPublicKey(key: String): SignAndroid = setPublicKey(key.encodeToByteArray())

}
