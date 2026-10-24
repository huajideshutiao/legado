package io.legado.app.help.crypto

/**
 * native 端对称加解密的 padding 层 (纯 Kotlin, iOS/鸿蒙共用)。
 *
 * 平台原语 ([PlatformCipherOps]) 一律 NoPadding, padding 在本层补/剥:
 * - 加密向 padding (JCA 语义: 对齐时 PKCS7/X923/ISO10126 仍补整块, ZERO 仅在需要时补);
 * - 解密向去 padding; PKCS7 逐字节校验 (对齐 JCA BadPaddingException), X923/ISO10126 仅校验长度字节。
 */
internal object SymPadding {

    /** 加密向补 padding; ECB/CBC/PCBC 的 NoPadding 要求块对齐 (对齐 JCA IllegalBlockSizeException)。 */
    fun pad(padding: String, data: ByteArray, block: Int, mode: String): ByteArray {
        val n = block - data.size % block
        return when (padding) {
            "NOPADDING" -> {
                if (mode == "ECB" || mode == "CBC" || mode == "PCBC") {
                    require(data.size % block == 0) {
                        "NoPadding requires block-aligned input (${data.size} % $block != 0)"
                    }
                }
                data
            }
            "PKCS7PADDING" -> data + ByteArray(n) { n.toByte() }
            "ANSIX923PADDING" -> data + ByteArray(n).also { it[n - 1] = n.toByte() }
            "ISO10126PADDING" -> data + PlatformCipherOps.randomBytes(n).also { it[n - 1] = n.toByte() }
            "ZEROPADDING" -> if (n == block) data else data + ByteArray(n)
            else -> throw UnsupportedOperationException("SymPadding: unsupported padding '$padding'")
        }
    }

    /** 解密向去 padding。 */
    fun unpad(padding: String, data: ByteArray, block: Int): ByteArray = when (padding) {
        "NOPADDING" -> data
        "PKCS7PADDING" -> {
            val n = padCount(data, block)
            for (i in data.size - n until data.size) {
                if ((data[i].toInt() and 0xFF) != n) throw IllegalStateException("bad PKCS7 padding")
            }
            data.copyOf(data.size - n)
        }
        "ANSIX923PADDING", "ISO10126PADDING" -> data.copyOf(data.size - padCount(data, block))
        "ZEROPADDING" -> {
            var end = data.size
            while (end > 0 && data[end - 1] == 0.toByte()) end--
            data.copyOf(end)
        }
        else -> throw UnsupportedOperationException("SymPadding: unsupported padding '$padding'")
    }

    private fun padCount(data: ByteArray, block: Int): Int {
        if (data.isEmpty()) throw IllegalStateException("empty data, nothing to unpad")
        val n = data.last().toInt() and 0xFF
        if (n < 1 || n > block || n > data.size) throw IllegalStateException("bad padding length $n")
        return n
    }
}
