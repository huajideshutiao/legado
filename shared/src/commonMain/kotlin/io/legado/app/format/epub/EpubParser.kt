package io.legado.app.format.epub

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser

/**
 * 纯 Kotlin EPUB 解析器 (commonMain, 无 native 依赖)。
 *
 * # 背景
 * jvmAndAndroidMain 端 [io.legado.app.model.fileBook.EpubFile] 依赖
 * [io.legado.app.lib.epublib.*] (JVM-only, 内部用 java.xml.parsers / java.util.zip),
 * iOS/鸿蒙 (Kotlin/Native) 不可见。本解析器用纯 Kotlin + [Ksoup] (KMP XML/HTML 解析)
 * + [unzipEpubEntries] (expect/actual, native 委托 [io.legado.app.help.storage.NativeZipCodec],
 * jvm 用 java.util.zip) 实现 EPUB 2.0 / 3.0 解析, 解除 iOS/鸿蒙端 epub 支持 stub 限制。
 *
 * # 解析流程
 * 1. [unzipEpubEntries] 解压 epub 字节 → Map<path, ByteArray>
 * 2. 解析 `META-INF/container.xml` → 找到 OPF 路径 (rootfile@full-path)
 * 3. [Ksoup] XML 解析 OPF:
 *    a) [readMetadata]: <metadata> 下 dc:title/dc:creator/dc:description/dc:language
 *    b) [readManifest]: <manifest><item id, href, media-type> → [EpubResource] 集合
 *    c) [readSpine]: <spine><itemref idref> → 阅读顺序
 *    d) [findCoverImage]: <meta name="cover"> / <item properties="cover-image"> / <guide reference type="cover">
 * 4. [findTocResource]: epub3 <item properties="nav">; epub2 <spine toc="ncx-id">
 * 5. [readToc]:
 *    a) epub3 nav: <nav epub:type="toc"><ol><li><a href>
 *    b) epub2 NCX: <navMap><navPoint><navLabel><text>, <content src>
 * 6. 组装 [EpubBook]
 *
 * # 路径解析
 * OPF/NCX 内的 href 是相对路径 (相对 OPF/NCX 自身位置), 需 [resolvePath] 规范化为
 * zip 内绝对路径 (POSIX 风格, "/" 分隔)。与 epublib PackageDocumentReader.resolvePath
 * 行为对齐 (不做 URL 编解码, 因 zip entry 名恒为字面路径)。
 *
 * # 局限
 * - 不支持加密 epub (DRM)
 * - 不支持 SVG cover / 多 rendition
 * - 远程 epub 需调用方先下载为 ByteArray 再传入 (本解析器只处理本地字节)
 */
/**
 * 解压 epub 字节流为 zip 内 entry 名 → 字节内容 Map。
 *
 * expect/actual: nativeMain 委托 [io.legado.app.help.storage.NativeZipCodec.unzipToMap]
 * (纯 Kotlin inflate + ZIP 格式解析); jvmAndAndroidMain 用 `java.util.zip.ZipInputStream`。
 */
internal expect fun unzipEpubEntries(zipData: ByteArray): Map<String, ByteArray>

object EpubParser {

    /**
     * 解析 epub 字节流为 [EpubBook]。
     *
     * @param epubData epub 文件完整字节 (zip 格式)
     * @return 解析后的 [EpubBook]; 解析失败抛 [IllegalStateException]
     */
    fun parse(epubData: ByteArray): EpubBook {
        val entries = unzipEpubEntries(epubData)
        val opfPath = findOpfPath(entries)
            ?: throw IllegalStateException("EpubParser: META-INF/container.xml missing or no rootfile")
        val opfBytes = entries[opfPath]
            ?: throw IllegalStateException("EpubParser: OPF not found at $opfPath")
        val opfDoc = Ksoup.parse(opfBytes.decodeToString(), parser = Parser.xmlParser())

        val version = opfDoc.getElementsByTag("package").firstOrNull()
            ?.attr("version") ?: "2.0"
        val metadata = readMetadata(opfDoc)
        val resources = readManifest(opfDoc, opfPath, entries)
        val spine = readSpine(opfDoc, resources)
        val coverImage = findCoverImage(opfDoc, opfPath, resources)
        val tocResource = findTocResource(opfDoc, resources, version)
        val toc = if (tocResource != null) {
            readToc(tocResource, resources, version)
        } else {
            emptyList()
        }

        return EpubBook(
            version = version,
            opfPath = opfPath,
            metadata = metadata,
            resources = resources,
            spine = spine,
            toc = toc,
            coverImage = coverImage,
        )
    }

    /** 解析 `META-INF/container.xml` 找到 OPF 路径 (rootfile@full-path)。 */
    private fun findOpfPath(entries: Map<String, ByteArray>): String? {
        val containerBytes = entries["META-INF/container.xml"] ?: return null
        val doc = Ksoup.parse(containerBytes.decodeToString(), parser = Parser.xmlParser())
        // <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
        val rootFile = doc.getElementsByTag("rootfile").firstOrNull() ?: return null
        val fullPath = rootFile.attr("full-path").ifBlank { return null }
        return fullPath
    }

    /** 读取 <metadata> 下的 Dublin Core 元素。 */
    private fun readMetadata(opfDoc: Document): EpubMetadata {
        val metadataEl = opfDoc.getElementsByTag("metadata").firstOrNull()
            ?: return EpubMetadata()
        return EpubMetadata(
            titles = metadataEl.getElementsByTag("dc:title").map { it.text().trim() }.filter { it.isNotEmpty() },
            authors = metadataEl.getElementsByTag("dc:creator").map { it.text().trim() }.filter { it.isNotEmpty() },
            descriptions = metadataEl.getElementsByTag("dc:description").map { it.text().trim() }.filter { it.isNotEmpty() },
            publishers = metadataEl.getElementsByTag("dc:publisher").map { it.text().trim() }.filter { it.isNotEmpty() },
            language = metadataEl.getElementsByTag("dc:language").firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * 读取 <manifest><item> 列表, 构造 href → [EpubResource] Map。
     *
     * item href 是相对 OPF 位置的路径, [resolvePath] 规范化为 zip 内绝对路径,
     * 与 entries Map key 对齐。
     */
    private fun readManifest(
        opfDoc: Document, opfPath: String, entries: Map<String, ByteArray>
    ): Map<String, EpubResource> {
        val manifestEl = opfDoc.getElementsByTag("manifest").firstOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, EpubResource>()
        for (item in manifestEl.getElementsByTag("item")) {
            val id = item.attr("id").ifBlank { continue }
            val href = item.attr("href").ifBlank { continue }
            val mediaType = item.attr("media-type").ifBlank { null }
            val properties = item.attr("properties").ifBlank { null }
            val resolvedHref = resolvePath(opfPath, href)
            val data = entries[resolvedHref] ?: continue
            result[resolvedHref] = EpubResource(
                href = resolvedHref,
                id = id,
                mediaType = mediaType,
                properties = properties,
                data = data,
            )
        }
        return result
    }

    /** 读取 <spine><itemref idref> 列表, 按 idref 顺序从 [resources] 取出 [EpubResource]。 */
    private fun readSpine(opfDoc: Document, resources: Map<String, EpubResource>): List<EpubResource> {
        val spineEl = opfDoc.getElementsByTag("spine").firstOrNull() ?: return emptyList()
        val result = ArrayList<EpubResource>()
        for (itemref in spineEl.getElementsByTag("itemref")) {
            val idref = itemref.attr("idref").ifBlank { continue }
            // manifest item id 即 resource id; 按 id 查找
            val resource = resources.values.firstOrNull { it.id == idref } ?: continue
            result.add(resource)
        }
        return result
    }

    /**
     * 查找封面图片资源。
     *
     * 优先级 (对齐 epublib PackageDocumentReader.findCoverHrefs):
     * 1. epub3 <item properties="cover-image">
     * 2. epub2 <meta name="cover" content="cover-id"/> → manifest item[id=cover-id]
     * 3. <guide><reference type="cover" href="..."/> (仅 epub2, href 指向图片)
     */
    private fun findCoverImage(
        opfDoc: Document, opfPath: String, resources: Map<String, EpubResource>
    ): EpubResource? {
        // 1. epub3 cover-image properties
        for (res in resources.values) {
            if (res.properties != null && res.properties.contains("cover-image")) {
                return res
            }
        }
        // 2. epub2 meta name="cover" content="id"
        val metadataEl = opfDoc.getElementsByTag("metadata").firstOrNull()
        if (metadataEl != null) {
            for (meta in metadataEl.getElementsByTag("meta")) {
                if (meta.attr("name") == "cover") {
                    val coverId = meta.attr("content").ifBlank { continue }
                    return resources.values.firstOrNull { it.id == coverId }
                }
            }
        }
        // 3. guide reference type="cover"
        val guideEl = opfDoc.getElementsByTag("guide").firstOrNull()
        if (guideEl != null) {
            for (ref in guideEl.getElementsByTag("reference")) {
                if (ref.attr("type").equals("cover", ignoreCase = true) == true) {
                    val href = ref.attr("href").ifBlank { continue }
                    val resolved = resolvePath(opfPath, href)
                    return resources[resolved]
                }
            }
        }
        return null
    }

    /**
     * 查找目录资源 (TOC)。
     *
     * - epub3: <item properties="nav">
     * - epub2: <spine toc="ncx-id"> → manifest item[id=ncx-id]
     * - 兜底: media-type="application/x-dtbncx+xml" 的第一个 item
     */
    private fun findTocResource(
        opfDoc: Document, resources: Map<String, EpubResource>, version: String
    ): EpubResource? {
        // 1. epub3 nav properties
        if (version.startsWith("3.")) {
            for (res in resources.values) {
                if (res.properties != null && res.properties.contains("nav")) {
                    return res
                }
            }
        }
        // 2. epub2 spine toc 属性 → manifest item id
        val spineEl = opfDoc.getElementsByTag("spine").firstOrNull()
        val tocId = spineEl?.attr("toc")?.ifBlank { null }
        if (tocId != null) {
            resources.values.firstOrNull { it.id == tocId }?.let { return it }
        }
        // 3. 兜底: NCX media-type
        return resources.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
    }

    /**
     * 解析目录 (TOC) 为层级 [EpubChapter] 列表。
     *
     * - epub3 nav: <nav epub:type="toc"><ol><li><a href="...">title</a><ol>...</ol></li></ol></nav>
     * - epub2 NCX: <navMap><navPoint><navLabel><text>title</text></navLabel><content src="..."/></navPoint></navMap>
     */
    private fun readToc(
        tocResource: EpubResource, resources: Map<String, EpubResource>, version: String
    ): List<EpubChapter> {
        val xml = tocResource.data.decodeToString()
        val doc = Ksoup.parse(xml, parser = Parser.xmlParser())
        return if (version.startsWith("3.")) {
            readNavToc(doc, tocResource.href, resources)
        } else {
            readNcxToc(doc, tocResource.href, resources)
        }
    }

    /** 解析 epub3 nav 元素为层级目录。 */
    private fun readNavToc(
        doc: Document, tocHref: String, resources: Map<String, EpubResource>
    ): List<EpubChapter> {
        // 找 <nav epub:type="toc"> 或 <nav> (兜底)
        val navEl = doc.getElementsByTag("nav").firstOrNull() ?: return emptyList()
        val olEl = navEl.getElementsByTag("ol").firstOrNull() ?: return emptyList()
        return readNavListItems(olEl, tocHref, resources)
    }

    /** 递归解析 <ol><li> 列表。 */
    private fun readNavListItems(
        olEl: Element, tocHref: String, resources: Map<String, EpubResource>
    ): List<EpubChapter> {
        val result = ArrayList<EpubChapter>()
        for (li in olEl.children()) {
            if (li.tagName() != "li") continue
            val a = li.getElementsByTag("a").firstOrNull() ?: continue
            val title = a.text().trim().ifBlank { "" }
            val href = a.attr("href").ifBlank { continue }
            val resolved = resolvePath(tocHref, href)
            val (pathHref, fragmentId) = splitFragment(resolved)
            val resource = resources[pathHref]
            val children = li.getElementsByTag("ol").firstOrNull()?.let {
                readNavListItems(it, tocHref, resources)
            } ?: emptyList()
            result.add(EpubChapter(
                title = title,
                completeHref = resolved,
                fragmentId = fragmentId,
                resource = resource,
                children = children,
            ))
        }
        return result
    }

    /** 解析 epub2 NCX <navMap><navPoint> 为层级目录。 */
    private fun readNcxToc(
        doc: Document, tocHref: String, resources: Map<String, EpubResource>
    ): List<EpubChapter> {
        val navMap = doc.getElementsByTag("navMap").firstOrNull() ?: return emptyList()
        return readNcxNavPoints(navMap, tocHref, resources)
    }

    /** 递归解析 <navPoint> 列表。 */
    private fun readNcxNavPoints(
        parent: Element, tocHref: String, resources: Map<String, EpubResource>
    ): List<EpubChapter> {
        val result = ArrayList<EpubChapter>()
        // 直接子 navPoint (避免递归到孙子)
        for (navPoint in parent.children()) {
            if (navPoint.tagName() != "navPoint") continue
            val labelEl = navPoint.getElementsByTag("navLabel").firstOrNull()
            val textEl = labelEl?.getElementsByTag("text")?.firstOrNull()
            val title = textEl?.text()?.trim()?.ifBlank { null } ?: ""
            val contentEl = navPoint.getElementsByTag("content").firstOrNull() ?: continue
            val src = contentEl.attr("src").ifBlank { continue }
            val resolved = resolvePath(tocHref, src)
            val (pathHref, fragmentId) = splitFragment(resolved)
            val resource = resources[pathHref]
            val children = readNcxNavPoints(navPoint, tocHref, resources)
            result.add(EpubChapter(
                title = title,
                completeHref = resolved,
                fragmentId = fragmentId,
                resource = resource,
                children = children,
            ))
        }
        return result
    }

    /**
     * 规范化相对路径为 zip 内绝对路径 (POSIX 风格)。
     *
     * @param base 基准路径 (OPF 或 NCX 在 zip 内的绝对路径, 如 "OEBPS/content.opf")
     * @param relative 相对路径 (如 "chapter1.xhtml" 或 "../images/cover.png")
     * @return 规范化后的绝对路径 (如 "OEBPS/chapter1.xhtml" 或 "images/cover.png")
     */
    internal fun resolvePath(base: String, relative: String): String {
        if (relative.startsWith("/")) return relative.removePrefix("/")
        val baseDir = base.substringBeforeLast("/", "")
        val parts = if (baseDir.isEmpty()) {
            relative.split("/").toMutableList()
        } else {
            (baseDir.split("/") + relative.split("/")).toMutableList()
        }
        val result = mutableListOf<String>()
        for (part in parts) {
            when {
                part.isEmpty() || part == "." -> { /* skip */ }
                part == ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex)
                else -> result.add(part)
            }
        }
        return result.joinToString("/")
    }

    /** 把 href 拆分为 (path, fragmentId), fragmentId 为 null 表示无 #fragment。 */
    internal fun splitFragment(href: String): Pair<String, String?> {
        val idx = href.indexOf('#')
        return if (idx < 0) href to null else href.substring(0, idx) to href.substring(idx + 1)
    }
}

/**
 * EPUB 书籍数据模型 (commonMain 纯 Kotlin, 无平台依赖)。
 *
 * 与 jvmAndAndroidMain [io.legado.app.lib.epublib.domain.EpubBook] 字段对齐 (子集),
 * 供 [EpubParser] 输出 / ohosMain [io.legado.app.model.fileBook.EpubFile] 消费。
 */
data class EpubBook(
    /** EPUB 版本 ("2.0" / "3.0")。 */
    val version: String = "2.0",
    /** OPF 文件在 zip 内的绝对路径。 */
    val opfPath: String = "",
    /** 书籍元数据。 */
    val metadata: EpubMetadata = EpubMetadata(),
    /** manifest 所有资源, key 为 zip 内绝对路径。 */
    val resources: Map<String, EpubResource> = emptyMap(),
    /** spine 阅读顺序资源列表。 */
    val spine: List<EpubResource> = emptyList(),
    /** 目录 (层级)。 */
    val toc: List<EpubChapter> = emptyList(),
    /** 封面图片资源 (可能为 null)。 */
    val coverImage: EpubResource? = null,
)

/** EPUB 元数据 (Dublin Core 子集)。 */
data class EpubMetadata(
    val titles: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val descriptions: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val language: String? = null,
) {
    /** 第一个非空 title, 无则空串 (对齐 epublib Metadata.firstTitle)。 */
    val firstTitle: String get() = titles.firstOrNull { it.isNotBlank() } ?: ""
}

/** EPUB 资源 (manifest item)。 */
data class EpubResource(
    /** zip 内绝对路径 (entry 名)。 */
    val href: String,
    /** manifest item id。 */
    val id: String? = null,
    /** media-type (如 "application/xhtml+xml", "image/jpeg")。 */
    val mediaType: String? = null,
    /** epub3 properties (如 "nav", "cover-image", 可能空格分隔多个)。 */
    val properties: String? = null,
    /** 资源字节内容。 */
    val data: ByteArray,
) {
    // ByteArray 默认按引用比较, data class equals/hashCode 会失真; 重写为按 href 标识
    override fun equals(other: Any?): Boolean =
        this === other || (other is EpubResource && href == other.href && id == other.id)
    override fun hashCode(): Int = href.hashCode()
}

/** EPUB 目录项 (层级)。 */
data class EpubChapter(
    /** 章节标题。 */
    val title: String,
    /** 解析后的完整 href (zip 内绝对路径 + 可选 #fragment)。 */
    val completeHref: String,
    /** fragmentId (# 后部分), 无则 null。 */
    val fragmentId: String?,
    /** 引用的 [EpubResource] (按 completeHref 去掉 fragment 后查找; 找不到为 null)。 */
    val resource: EpubResource?,
    /** 子章节 (层级目录)。 */
    val children: List<EpubChapter> = emptyList(),
)
