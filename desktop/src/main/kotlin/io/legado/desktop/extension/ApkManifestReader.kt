// 扩展 APK 的 AndroidManifest 解析 (二进制 AXML 经 apk-parser 还原为文本 XML, 再 DOM 取字段)。
// 字段面与 Android 端 PackageManager.GET_META_DATA | GET_CONFIGURATIONS 查询等价:
// package/versionName/versionCode/uses-feature/application meta-data。
package io.legado.desktop.extension

import net.dongliu.apk.parser.ApkFile
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

data class ApkManifest(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val label: String?,
    val reqFeatures: List<String>,
    val metaData: Map<String, String>,
) {
    fun containsFeature(name: String): Boolean = name in reqFeatures
}

object ApkManifestReader {

    private const val ANDROID_NS_PREFIX = "android:"

    fun read(apkFile: File): ApkManifest {
        ApkFile(apkFile).use { apk ->
            val meta = apk.apkMeta
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(apk.manifestXml.byteInputStream())

            val manifest = document.documentElement
            val packageName = manifest.getAttribute("${ANDROID_NS_PREFIX}package")
                .ifBlank { manifest.getAttribute("package") }
            val versionName = manifest.getAttribute("${ANDROID_NS_PREFIX}versionName")
                .ifBlank { null }
            val versionCode = manifest.getAttribute("${ANDROID_NS_PREFIX}versionCode")
                .ifBlank { "0" }.toLongOrNull() ?: 0L

            val reqFeatures = elementSequence(manifest)
                .filter { it.tagName == "uses-feature" }
                .mapNotNull { it.getAttribute("${ANDROID_NS_PREFIX}name").ifBlank { null } }
                .toList()

            val metaData = elementSequence(manifest)
                .filter { it.tagName == "application" }
                .flatMap { elementSequence(it) }
                .filter { it.tagName == "meta-data" }
                .mapNotNull { element ->
                    val name = element.getAttribute("${ANDROID_NS_PREFIX}name")
                    val value = element.getAttribute("${ANDROID_NS_PREFIX}value")
                    if (name.isBlank()) null else name to value
                }
                .toMap()

            return ApkManifest(
                packageName = packageName,
                versionName = versionName ?: meta.versionName,
                versionCode = versionCode,
                label = meta.label,
                reqFeatures = reqFeatures,
                metaData = metaData,
            )
        }
    }

    private fun elementSequence(node: Node): Sequence<Element> =
        generateSequence(node.firstChild) { it.nextSibling }
            .filter { it.nodeType == Node.ELEMENT_NODE }
            .map { it as Element }
}
