package io.legado.app.help.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扩展清单契约测试: 空安全判定与特征映射。
 */
class ExtensionManifestContractTest {

    @Test
    fun `isExtensionFeature 处理 null 与非扩展特征返回 false`() {
        assertFalse(ExtensionManifestContract.isExtensionFeature(null))
        assertFalse(ExtensionManifestContract.isExtensionFeature(""))
        assertFalse(ExtensionManifestContract.isExtensionFeature("android.hardware.camera"))
        assertFalse(ExtensionManifestContract.isExtensionFeature("reqGlEsVersion"))
    }

    @Test
    fun `isExtensionFeature 正确识别漫画与视频扩展特征`() {
        assertTrue(ExtensionManifestContract.isExtensionFeature("tachiyomi.extension"))
        assertTrue(ExtensionManifestContract.isExtensionFeature("tachiyomi.animeextension"))
    }

    @Test
    fun `extensionKindOf 在含有 null 或无关特征时安全识别家族`() {
        assertNull(ExtensionManifestContract.extensionKindOf(emptyList()))
        assertNull(ExtensionManifestContract.extensionKindOf(listOf(null, "android.hardware.camera")))
        assertEquals(
            ExtensionManifestContract.ExtensionKind.MANGA,
            ExtensionManifestContract.extensionKindOf(listOf(null, "tachiyomi.extension")),
        )
        assertEquals(
            ExtensionManifestContract.ExtensionKind.ANIME,
            ExtensionManifestContract.extensionKindOf(listOf("tachiyomi.animeextension", null)),
        )
    }
}
