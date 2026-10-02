package io.legado.app.format.epub

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser
import io.documentnode.epub4kmp.domain.Resource
import io.documentnode.epub4kmp.domain.Resources
import io.documentnode.epub4kmp.domain.TOCReference
import io.documentnode.epub4kmp.epub.EpubReader

/**
 * iOS/鸿蒙的 EPUB4KMP 读取适配层。
 *
 * 解压后由 EpubReader 解析 OPF 元数据、spine 和 NCX，再映射到 Legado 模型。
 * 保留 EPUB 3 nav、cover-image、URI 路径和缺失命名空间的兼容处理；
 * 正文章节切分交给 EpubContentReader。鸿蒙使用同版本源码重编译模块。
 * 远程 EPUB 由调用方下载到本地后读取；不支持 DRM 或多 rendition。
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

        val version = opfDoc.elementsByLocalName("package").firstOrNull()
            ?.attr("version") ?: "2.0"
        val resources = readManifest(opfDoc, opfPath, entries)
        val normalizedEntries = entries.mapValues { (href, bytes) ->
            // The previous reader tolerated OPF/NCX documents missing their
            // default namespace; EPUB4KMP's DOM lookup requires it.
            if (href == opfPath || resources[href]?.mediaType == "application/x-dtbncx+xml") {
                val doc = Ksoup.parse(bytes.decodeToString(), parser = Parser.xmlParser())
                val root = doc.children().firstOrNull()
                if (root != null && !root.hasAttr("xmlns") && !root.tagName().contains(':')) {
                    root.attr("xmlns", if (href == opfPath) "http://www.idpf.org/2007/opf"
                        else "http://www.daisy.org/z3986/2005/ncx/")
                    doc.outerHtml().encodeToByteArray()
                } else bytes
            } else bytes
        }
        val archiveResources = Resources().apply {
            normalizedEntries.forEach { (href, bytes) -> add(Resource(bytes, href)) }
            // EPUB4KMP looks up decoded manifest hrefs without collapsing dot
            // segments. Supply aliases before it makes paths relative to OPF.
            val opfDir = opfPath.substringBeforeLast('/', "")
            for (item in opfDoc.elementsByLocalName("manifest").firstOrNull()
                ?.elementsByLocalName("item").orEmpty()) {
                val href = item.attr("href")
                val resolved = resolvePath(opfPath, href)
                val bytes = normalizedEntries[resolved] ?: continue
                val decoded = decodeHref(href)
                val alias = if (opfDir.isEmpty()) decoded else "$opfDir/$decoded"
                if (alias != resolved) add(Resource(bytes, alias))
            }
        }
        val parsed = EpubReader().readEpub(archiveResources)
        check(parsed.opfResource != null) { "EpubParser: package document could not be read" }
        // EPUB4KMP exposes paths relative to OPF; Legado uses ZIP entry paths.
        fun resource(href: String?): EpubResource? = href?.let {
            resources[resolvePath(opfPath, it)]
        }
        val metadata = parsed.metadata.let {
            EpubMetadata(
                titles = it.getTitles(),
                authors = it.getAuthors().map { author ->
                    listOf(author.firstname, author.lastname).filter(String::isNotBlank).joinToString(" ")
                },
                descriptions = it.getDescriptions(),
                publishers = it.getPublishers(),
                language = it.language,
            )
        }
        val spine = parsed.spine.getSpineReferences().mapNotNull { resource(it.resource?.href) }
        check(spine.isNotEmpty()) { "EpubParser: no readable spine resources" }
        val coverImage = coverImageResource(findCoverImage(opfDoc, opfPath, resources), resources)
            ?: coverImageResource(resource(parsed.coverImage?.href), resources)
            ?: coverImageResource(resource(parsed.coverPage?.href), resources)
        val tocResource = findTocResource(opfDoc, resources, version)
        fun chapter(ref: TOCReference): EpubChapter {
            val res = resource(ref.resource?.href)
            val fragment = ref.fragmentId?.let(::decodeHref)
            return EpubChapter(
                title = ref.title.orEmpty(),
                completeHref = res?.href.orEmpty() + (fragment?.let { "#$it" } ?: ""),
                fragmentId = fragment,
                resource = res,
                children = ref.children.map(::chapter),
            )
        }
        // 0.3.0 reads NCX but not EPUB 3 nav documents. Keep the nav compatibility
        // layer (including unlinked volume headings) above the library's parser.
        val toc = if (tocResource?.mediaType == "application/xhtml+xml") {
            readToc(tocResource, resources)
        } else {
            parsed.tableOfContents.getTocReferences().map(::chapter)
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
        val rootFile = doc.elementsByLocalName("rootfile").firstOrNull() ?: return null
        val fullPath = rootFile.attr("full-path").ifBlank { return null }
        return decodeHref(fullPath)
    }

    /** 读取 <metadata> 下的 Dublin Core 元素。 */
    private fun readMetadata(opfDoc: Document): EpubMetadata {
        val metadataEl = opfDoc.elementsByLocalName("metadata").firstOrNull()
            ?: return EpubMetadata()
        return EpubMetadata(
            titles = metadataEl.elementsByLocalName("title").map { it.text().trim() }.filter { it.isNotEmpty() },
            authors = metadataEl.elementsByLocalName("creator").map { it.text().trim() }.filter { it.isNotEmpty() },
            descriptions = metadataEl.elementsByLocalName("description").map { it.text().trim() }.filter { it.isNotEmpty() },
            publishers = metadataEl.elementsByLocalName("publisher").map { it.text().trim() }.filter { it.isNotEmpty() },
            language = metadataEl.elementsByLocalName("language").firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() },
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
        val manifestEl = opfDoc.elementsByLocalName("manifest").firstOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, EpubResource>()
        for (item in manifestEl.elementsByLocalName("item")) {
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
        val spineEl = opfDoc.elementsByLocalName("spine").firstOrNull()
            ?: return resources.values.filter { it.mediaType == "application/xhtml+xml" }
                .sortedBy { it.href.lowercase() }
        val result = ArrayList<EpubResource>()
        for (itemref in spineEl.elementsByLocalName("itemref")) {
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
            if (res.hasProperty("cover-image")) {
                return res
            }
        }
        // 2. epub2 meta name="cover" content="id"
        val metadataEl = opfDoc.elementsByLocalName("metadata").firstOrNull()
        if (metadataEl != null) {
            for (meta in metadataEl.elementsByLocalName("meta")) {
                if (meta.attr("name") == "cover") {
                    val coverId = meta.attr("content").ifBlank { continue }
                    return resources.values.firstOrNull { it.id == coverId }
                }
            }
        }
        // 3. guide reference type="cover"
        val guideEl = opfDoc.elementsByLocalName("guide").firstOrNull()
        if (guideEl != null) {
            for (ref in guideEl.elementsByLocalName("reference")) {
                if (ref.attr("type").equals("cover", ignoreCase = true) == true) {
                    val href = ref.attr("href").ifBlank { continue }
                    val resolved = resolvePath(opfPath, href)
                    return resources[resolved]
                }
            }
        }
        return null
    }

    /** A guide/meta cover may reference an XHTML wrapper rather than an image. */
    private fun coverImageResource(
        candidate: EpubResource?, resources: Map<String, EpubResource>
    ): EpubResource? {
        candidate ?: return null
        if (candidate.mediaType?.startsWith("image/") == true) return candidate
        val doc = Ksoup.parse(candidate.data.decodeToString(), parser = Parser.xmlParser())
        for (element in doc.getAllElements()) {
            val href = when (element.localName()) {
                "img" -> element.attr("src")
                "image" -> element.attr("href").ifBlank { element.attr("xlink:href") }
                else -> continue
            }
            val image = resources[resolvePath(candidate.href, href).substringBefore('#')]
            if (image?.mediaType?.startsWith("image/") == true) return image
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
                if (res.hasProperty("nav")) {
                    return res
                }
            }
        }
        // 2. epub2 spine toc 属性 → manifest item id
        val spineEl = opfDoc.elementsByLocalName("spine").firstOrNull()
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
        tocResource: EpubResource, resources: Map<String, EpubResource>
    ): List<EpubChapter> {
        val xml = tocResource.data.decodeToString()
        val doc = Ksoup.parse(xml, parser = Parser.xmlParser())
        // EPUB 3 may also use NCX, so inspect the navigation document.
        return if (doc.elementsByLocalName("navMap").isNotEmpty()) {
            readNcxToc(doc, tocResource.href, resources)
        } else {
            readNavToc(doc, tocResource.href, resources)
        }
    }

    /** 解析 epub3 nav 元素为层级目录。 */
    private fun readNavToc(
        doc: Document, tocHref: String, resources: Map<String, EpubResource>
    ): List<EpubChapter> {
        // 找 <nav epub:type="toc"> 或 <nav> (兜底)
        val navs = doc.elementsByLocalName("nav")
        val navEl = navs.firstOrNull { nav ->
            nav.attr("epub:type").split(Regex("\\s+")).contains("toc")
        } ?: navs.firstOrNull { !it.hasAttr("epub:type") } ?: return emptyList()
        val olEl = navEl.children().firstOrNull { it.localName() == "ol" } ?: return emptyList()
        return readNavListItems(olEl, tocHref, resources)
    }

    /** 递归解析 <ol><li> 列表。 */
    private fun readNavListItems(
        olEl: Element, tocHref: String, resources: Map<String, EpubResource>
    ): List<EpubChapter> {
        val result = ArrayList<EpubChapter>()
        for (li in olEl.children()) {
            if (li.localName() != "li") continue
            // Only direct children: a volume must not steal a descendant's chapter link.
            val label = li.children().firstOrNull { it.localName() in listOf("a", "span") }
                ?: continue
            val title = label.text().trim()
            val href = label.attr("href")
            val resolved = if (href.isNotBlank()) resolvePath(tocHref, href) else ""
            val (pathHref, fragmentId) = splitFragment(resolved)
            val resource = resources[pathHref]
            val children = li.children().firstOrNull { it.localName() == "ol" }?.let {
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
        val navMap = doc.elementsByLocalName("navMap").firstOrNull() ?: return emptyList()
        return readNcxNavPoints(navMap, tocHref, resources)
    }

    /** 递归解析 <navPoint> 列表。 */
    private fun readNcxNavPoints(
        parent: Element, tocHref: String, resources: Map<String, EpubResource>
    ): List<EpubChapter> {
        val result = ArrayList<EpubChapter>()
        // 直接子 navPoint (避免递归到孙子)
        for (navPoint in parent.children()) {
            if (navPoint.localName() != "navPoint") continue
            val labelEl = navPoint.elementsByLocalName("navLabel").firstOrNull()
            val textEl = labelEl?.elementsByLocalName("text")?.firstOrNull()
            val title = textEl?.text()?.trim()?.ifBlank { null } ?: ""
            val contentEl = navPoint.elementsByLocalName("content").firstOrNull() ?: continue
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
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(relative) || relative.startsWith("//")) {
            return relative
        }
        val path = relative.substringBefore('#').substringBefore('?')
        val fragment = relative.substringAfter('#', "").takeIf { '#' in relative }
        val resolved = if (path.isEmpty()) {
            base.substringBefore('#')
        } else {
            val decoded = decodeHref(path)
            val baseDir = base.substringBefore('#').substringBeforeLast("/", "")
            val parts = if (decoded.startsWith("/") || baseDir.isEmpty()) {
                decoded.split("/")
            } else {
                baseDir.split("/") + decoded.split("/")
            }
            val result = mutableListOf<String>()
            for (part in parts) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex)
                    else -> result.add(part)
                }
            }
            result.joinToString("/")
        }
        return resolved + (fragment?.let { "#$it" } ?: "")
    }

    /** Decode URI escapes without treating a literal '+' as a space. */
    internal fun decodeHref(href: String): String = buildString {
        var i = 0
        while (i < href.length) {
            if (href[i] != '%' || i + 2 >= href.length ||
                href[i + 1].digitToIntOrNull(16) == null || href[i + 2].digitToIntOrNull(16) == null
            ) {
                append(href[i++])
                continue
            }
            val bytes = mutableListOf<Byte>()
            while (i + 2 < href.length && href[i] == '%') {
                val high = href[i + 1].digitToIntOrNull(16) ?: break
                val low = href[i + 2].digitToIntOrNull(16) ?: break
                bytes.add(((high shl 4) or low).toByte())
                i += 3
            }
            append(bytes.toByteArray().decodeToString())
        }
    }

    private fun Element.localName(): String = tagName().substringAfter(':')

    private fun Element.elementsByLocalName(name: String): List<Element> =
        getAllElements().filter { it.localName() == name }

    private fun EpubResource.hasProperty(property: String): Boolean =
        properties?.split(Regex("\\s+"))?.contains(property) == true

    /** 把 href 拆分为 (path, fragmentId), fragmentId 为 null 表示无 #fragment。 */
    internal fun splitFragment(href: String): Pair<String, String?> {
        val idx = href.indexOf('#')
        return if (idx < 0) href to null else href.substring(0, idx) to decodeHref(href.substring(idx + 1))
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
