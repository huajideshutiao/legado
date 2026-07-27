package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Base64 实现替换（android.util.Base64.NO_WRAP → java.util.Base64.getEncoder）语义锁。
 * 期望值硬编码自 android.util.Base64.encodeToString(bytes, NO_WRAP) 行为：标准字母表+padding+不换行。
 */
class JsEncodeUtilsTest {

    // KMP 限制: actual interface 成员必须 abstract (与 commonMain expect modality 对齐),
    // 默认实现下沉到 jvmAndAndroidMain 的 JsEncodeUtilsDefaults interface, 测试改用 Defaults。
    private val js = object : JsEncodeUtilsDefaults {}

    @Test
    fun `digestBase64Str 对照 android NO_WRAP oracle`() {
        // SHA-512 摘要 64 字节 → base64 88 字符 > 76，若误用 MIME 编码器会插入换行
        assertEquals(
            "oHaCoBWU3AWxo1y1OgV93eZSrjIr/xf52gu+kErprm6I6jUS5xbBp2d73yAywfRdr4aqh/MOUF0bgm8mXLLb9w==",
            js.digestBase64Str("legado", "SHA-512")
        )
        assertEquals(
            "yEjIOhSFOCFZL57FccPuI8qphaLr6TuNMYW+Pp1lAFE=",
            js.digestBase64Str("legado", "SHA-256")
        )
        assertFalse(js.digestBase64Str("legado", "SHA-512").contains("\n"))
    }

    @Test
    fun `HMacBase64 对照 android NO_WRAP oracle`() {
        assertEquals(
            "xT4q+Psyth+QCRm4zODJN3OTI+tSjY1VW0TzO4Ge8Gtu/uPVxBcBmyK2O60VhvOyNjwSEefwvpaPJZa3l+csfw==",
            js.HMacBase64("legado", "HmacSHA512", "0123456789abcdef")
        )
        assertEquals(
            "6bSmDJEyIpo76xDL/UlvStxcF0com5vvMBCPNlOrS1g=",
            js.HMacBase64("legado", "HmacSHA256", "0123456789abcdef")
        )
        assertFalse(js.HMacBase64("legado", "HmacSHA512", "0123456789abcdef").contains("\n"))
    }
}
