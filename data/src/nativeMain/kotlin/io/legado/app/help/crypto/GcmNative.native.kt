package io.legado.app.help.crypto

/**
 * 纯 Kotlin GCM (NIST SP 800-38D), iOS/鸿蒙 共用。
 *
 * 平台侧不可用: iOS 的 CommonCrypto GCM 接口 (CCCryptorGCM*) 属 SPI 非公开 API
 * (公开 CommonCryptor.h 无 GCM, 引用会被 App Store ITMS-90338 拒审), 鸿蒙 C API 的
 * OH_CryptoAsymCipher 同代宏面也没有对称 GCM 的可盲用封装; 故以平台 AES-ECB 单块
 * 原语 ([PlatformCipherOps.aesEcbEncryptBlock]) 为底, Kotlin 实现 CTR + GHASH。
 * 两侧共用同一实现, 字节级一致, 密文形态对齐 JCA: cipher || tag(16B)。
 *
 * AAD: JCA Cipher 层 GCM 无 AAD 入口, 恒为空 (GHASH 仍按规范计入长度块)。
 */
internal object GcmNative {

    private const val BLOCK = 16
    private const val TAG_LEN = 16

    /** 加密: 返回 cipher || tag(16B) (对齐 JCA AES/GCM/NoPadding 密文形态)。 */
    fun encrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val cipher = gctr(key, iv, plain)
        val tag = authTag(key, iv, cipher)
        return cipher + tag
    }

    /** 解密: blob = cipher || tag(16B); tag 不匹配抛 IllegalStateException (对齐 JCA AEADBadTagException)。 */
    fun decrypt(key: ByteArray, iv: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size < TAG_LEN) {
            throw IllegalStateException("GCM ciphertext too short: ${blob.size}")
        }
        val n = blob.size - TAG_LEN
        val cipher = blob.copyOf(n)
        val tag = blob.copyOfRange(n, blob.size)
        val expected = authTag(key, iv, cipher)
        if (!expected.contentEquals(tag)) {
            throw IllegalStateException("GCM tag verification failed")
        }
        return gctr(key, iv, cipher)
    }

    /** Tag = E_K(J0) ⊕ GHASH_H(A, C)。 */
    private fun authTag(key: ByteArray, iv: ByteArray, cipher: ByteArray): ByteArray {
        val h = PlatformCipherOps.aesEcbEncryptBlock(key, ByteArray(BLOCK))
        val j0 = if (iv.size == 12) iv + byteArrayOf(0, 0, 0, 1) else ghash(h, iv)
        val s = ghash(h, cipher)
        val ekj0 = PlatformCipherOps.aesEcbEncryptBlock(key, j0)
        return ByteArray(TAG_LEN) { (ekj0[it].toInt() xor s[it].toInt()).toByte() }
    }

    /** CTR: CB_1 = inc32(J0) 起步, 逐块 E_K(CB_i) ⊕ data; 96 位 IV 免算 H (省一次单块加密)。 */
    private fun gctr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val counter = computeJ0(key, iv)
        inc32(counter)
        val out = ByteArray(data.size)
        var off = 0
        while (off < data.size) {
            val ks = PlatformCipherOps.aesEcbEncryptBlock(key, counter)
            val n = minOf(BLOCK, data.size - off)
            for (i in 0 until n) {
                out[off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
            }
            off += n
            if (off < data.size) inc32(counter)
        }
        return out
    }

    /** J0: 96 位 IV 直拼 0^31||1 (不算 H); 其他长度 GHASH_H(IV||pad||[len(IV)]64)。 */
    private fun computeJ0(key: ByteArray, iv: ByteArray): ByteArray {
        if (iv.size == 12) {
            return iv + byteArrayOf(0, 0, 0, 1)
        }
        // ghash 复用: IV 视作唯一的"密文"输入, 长度块由 GHASH 内部补
        return ghash(PlatformCipherOps.aesEcbEncryptBlock(key, ByteArray(BLOCK)), iv)
    }

    /** GHASH_H(X): Y_i = (Y_{i-1} ⊕ X_i) • H, 末块零补齐, 末尾追加 [len(X)]64 位长块。 */
    private fun ghash(h: ByteArray, data: ByteArray): ByteArray {
        var y = ByteArray(BLOCK)
        var off = 0
        while (off < data.size) {
            val n = minOf(BLOCK, data.size - off)
            val block = ByteArray(BLOCK)
            data.copyInto(block, 0, off, off + n)
            for (i in 0 until BLOCK) y[i] = (y[i].toInt() xor block[i].toInt()).toByte()
            y = mult(h, y)
            off += n
        }
        val bits = data.size.toLong() * 8
        val lenBlock = ByteArray(BLOCK)
        for (i in 0 until 8) {
            lenBlock[i] = 0 // 64 位上界内长度恒 0
            lenBlock[8 + i] = (bits ushr ((7 - i) * 8)).toByte()
        }
        for (i in 0 until BLOCK) y[i] = (y[i].toInt() xor lenBlock[i].toInt()).toByte()
        return mult(h, y)
    }

    /**
     * GF(2^128) 乘法, 多项式 x^128 + x^7 + x^2 + x + 1 (NIST SP 800-38D Alg. 1):
     * Z=0, V=H; 逐位扫描 x (MSB 在前), 该位为 1 则 Z ⊕= V; V 右移一位, 移出位为 1 时 ⊕ R(0xE1<<120)。
     */
    private fun mult(h: ByteArray, x: ByteArray): ByteArray {
        val z = ByteArray(BLOCK)
        val v = h.copyOf()
        for (i in 0 until BLOCK * 8) {
            if ((x[i / 8].toInt() shr (7 - (i % 8))) and 1 == 1) {
                for (j in 0 until BLOCK) z[j] = (z[j].toInt() xor v[j].toInt()).toByte()
            }
            val lsb = v[BLOCK - 1].toInt() and 1
            // 128 位大端右移一位
            for (j in BLOCK - 1 downTo 1) {
                v[j] = (((v[j].toInt() and 0xFF) ushr 1) or ((v[j - 1].toInt() and 1) shl 7)).toByte()
            }
            v[0] = ((v[0].toInt() and 0xFF) ushr 1).toByte()
            if (lsb == 1) {
                v[0] = (v[0].toInt() xor 0xE1).toByte()
            }
        }
        return z
    }

    /** 最后 4 字节按 mod 2^32 大端递增 (NIST inc32)。 */
    private fun inc32(block: ByteArray) {
        for (i in 15 downTo 12) {
            val v = (block[i].toInt() and 0xFF) + 1
            block[i] = v.toByte()
            if (v <= 0xFF) return
        }
    }
}
