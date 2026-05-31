package io.legado.app.lib.epublib.epub

import android.util.Log
import io.legado.app.lib.epublib.Constants
import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.Guide
import io.legado.app.lib.epublib.domain.GuideReference
import io.legado.app.lib.epublib.domain.MediaType
import io.legado.app.lib.epublib.domain.MediaTypes
import io.legado.app.lib.epublib.domain.Resource
import io.legado.app.lib.epublib.domain.Resources
import io.legado.app.lib.epublib.domain.Spine
import io.legado.app.lib.epublib.domain.SpineReference
import io.legado.app.lib.epublib.util.ResourceUtil
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

/**
 * Reads the opf package document as defined by namespace http://www.idpf.org/2007/opf
 * 
 * @author paul
 */
object PackageDocumentReader : PackageDocumentBase() {
    private val TAG: String = PackageDocumentReader::class.java.getName()
    private val POSSIBLE_NCX_ITEM_IDS: Array<String> = arrayOf<String>(
        "toc",
        "ncx", "ncxtoc", "htmltoc"
    )
    private val namespaceRegex: Pattern = Pattern.compile(" s?mlns=\"")


    @Throws(SAXException::class, IOException::class)
    fun read(
        packageResource: Resource, epubReader: EpubReader?, book: EpubBook,
        resources: Resources
    ) {
        /*掌上书苑有很多自制书OPF的nameSpace格式不标准，强制修复成正确的格式*/
        var resources: Resources = resources
        val string = namespaceRegex.matcher(kotlin.text.String(packageResource.data!!))
            .replaceAll(" xmlns=\"")
        packageResource.data = string.toByteArray()

        val packageDocument: Document = ResourceUtil.getAsDocument(packageResource) ?: return
        val packageHref: String? = packageResource.getHref()

        val packagePath: URI?
        try {
            packagePath = URI(packageHref ?: "")
        } catch (e: URISyntaxException) {
            throw RuntimeException(e)
        }

        //resources = fixHrefs(packageHref, resources);
        readGuide(packageDocument, epubReader, book, resources)

        // Books sometimes use non-identifier ids. We map these here to legal ones
        val idMapping: MutableMap<String?, String?> = HashMap<String?, String?>()
        val version = DOMUtil.getAttribute(
            packageDocument.getDocumentElement(),
            PackageDocumentBase.Companion.PREFIX_OPF,
            PackageDocumentBase.Companion.version
        )

        resources = readManifest(
            packageDocument, packageHref, packagePath!!, epubReader,
            resources, idMapping
        )
        book.resources = resources
        book.version = version ?: "2.0"
        readCover(packageDocument, packagePath, book)
        book.metadata = PackageDocumentMetadataReader.readMetadata(packageDocument)
        book.spine = readSpine(packageDocument, book.resources, idMapping)

        // if we did not find a cover page then we make the first page of the book the cover page
        if (book.coverPage == null && book.spine.size() > 0) {
            book.coverPage = book.spine.getResource(0)
        }
    }

    /**
     * 修复一些非标准epub格式由于 opf 文件内容不全而读取不到图片的问题
     * 
     * @return 修复图片路径后的一个Element列表
     * @author qianfanguojin
     */
    private fun ensureImageInfo(
        resources: Resources,
        manifestElement: Element,
        packagePath: URI,
        packageDocument: Document
    ): ArrayList<Element> {
        val fixedElements = ArrayList<Element>()
        val originItemHrefSet = HashSet<String?>()
        //加入当前所有的 item 标签 并将 href 保存到集合中
        val originItemElements = manifestElement
            .getElementsByTagNameNS(
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFTags.Companion.item
            )
        for (i in 0..<originItemElements.getLength()) {
            val itemElement = originItemElements.item(i).cloneNode(false) as Element
            var href = DOMUtil.getAttribute(
                itemElement,
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFAttributes.Companion.href
            )
            val resolvedHref = resolvePath(packagePath, href)
            itemElement.setAttribute("href", resolvedHref ?: "")
            fixedElements.add(itemElement)
            try {
                href = URLDecoder.decode(resolvedHref ?: "", Constants.CHARACTER_ENCODING)
            } catch (e: UnsupportedEncodingException) {
                Log.e(TAG, e.message!!)
            }
            originItemHrefSet.add(href)
        }

        //如果有图片资源未定义在 originItemElements ，则加入该图片信息得到 fixedElements 中
        for (resource in resources.all) {
            val currentMediaType: MediaType = resource.mediaType ?: continue
            if (!MediaTypes.isImage(currentMediaType)) {
                continue
            }
            var imageHref: String = resource.getHref()
            //确保该图片信息 resource 在原 originItemHrefSet 集合中没有出现过
            if (originItemHrefSet.contains(imageHref)) {
                continue
            }
            val itemEl = packageDocument.createElement("item")
            itemEl.setAttribute("id", resource.id)
            try {
                imageHref = URLEncoder.encode(imageHref, Constants.CHARACTER_ENCODING)
            } catch (e: UnsupportedEncodingException) {
                Log.e(TAG, e.message!!)
                continue
            }
            itemEl.setAttribute("href", imageHref.replace("+", "%20"))
            itemEl.setAttribute("media-type", currentMediaType.name)
            fixedElements.add(itemEl)
        }
        return fixedElements
    }

    /**
     * Reads the manifest containing the resource ids, hrefs and mediatypes.
     * 
     * @param packageDocument e
     * @param packageHref     e
     * @param epubReader      e
     * @param resources       e
     * @param idMapping       e
     * @return a Map with resources, with their id's as key.
     */
    @Suppress("unused")
    private fun readManifest(
        packageDocument: Document,
        packageHref: String?,
        packagePath: URI,
        epubReader: EpubReader?, resources: Resources,
        idMapping: MutableMap<String?, String?>
    ): Resources {
        val manifestElement = DOMUtil.getFirstElementByTagNameNS(
            packageDocument.getDocumentElement(),
            PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.manifest
        )
        val result: Resources = Resources()
        if (manifestElement == null) {
            Log.e(
                TAG,
                "Package document does not contain element " + OPFTags.Companion.manifest
            )
            return result
        }
        val ensuredElements: MutableList<Element> =
            ensureImageInfo(resources, manifestElement, packagePath, packageDocument)
        for (itemElement in ensuredElements) {
//            Element itemElement = ;
            val id = DOMUtil.getAttribute(
                itemElement,
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFAttributes.Companion.id
            )
            var href = DOMUtil.getAttribute(
                itemElement,
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFAttributes.Companion.href
            )

            try {
                href = URLDecoder.decode(href ?: "", Constants.CHARACTER_ENCODING)
            } catch (e: UnsupportedEncodingException) {
                Log.e(TAG, e.message!!)
            }
            val mediaTypeName = DOMUtil.getAttribute(
                itemElement,
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFAttributes.Companion.media_type
            )
            val resource: Resource? = resources.remove(href)
            if (resource == null) {
                Log.e(TAG, "resource with href '" + href + "' not found")
                continue
            }
            resource.id = id
            //for epub3
            val properties = DOMUtil.getAttribute(
                itemElement,
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFAttributes.Companion.properties
            )
            resource.properties = properties

            val mediaType: MediaType? = MediaTypes.getMediaTypeByName(mediaTypeName)
            if (mediaType != null) {
                resource.mediaType = mediaType
            }
            result.add(resource)
            idMapping.put(id, resource.id)
        }
        return result
    }


    /**
     * Reads the book's guide.
     * Here some more attempts are made at finding the cover page.
     * 
     * @param packageDocument r
     * @param epubReader      r
     * @param book            r
     * @param resources       g
     */
    @Suppress("unused")
    private fun readGuide(
        packageDocument: Document,
        epubReader: EpubReader?, book: EpubBook, resources: Resources
    ) {
        val guideElement = DOMUtil.getFirstElementByTagNameNS(
            packageDocument.getDocumentElement(),
            PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.guide
        )
        if (guideElement == null) {
            return
        }
        val guide: Guide = book.guide
        val guideReferences = guideElement
            .getElementsByTagNameNS(
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFTags.Companion.reference
            )
        for (i in 0..<guideReferences.getLength()) {
            val referenceElement = guideReferences.item(i) as Element
            val resourceHref = DOMUtil.getAttribute(
                referenceElement,
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFAttributes.Companion.href
            )
            if (resourceHref.isNullOrBlank()) {
                continue
            }
            val resource: Resource? = resources.getByHref(
                resourceHref.substringBefore(Constants.FRAGMENT_SEPARATOR_CHAR)
            )
            if (resource == null) {
                Log.e(
                    TAG, ("Guide is referencing resource with href " + resourceHref
                        + " which could not be found")
                )
                continue
            }
            val type = DOMUtil.getAttribute(
                referenceElement,
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFAttributes.Companion.type
            )
            if (type.isNullOrBlank()) {
                Log.e(
                    TAG, ("Guide is referencing resource with href " + resourceHref
                        + " which is missing the 'type' attribute")
                )
                continue
            }
            val title = DOMUtil.getAttribute(
                referenceElement,
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFAttributes.Companion.title
            )
            if (GuideReference.COVER.equals(type, ignoreCase = true)) {
                continue  // cover is handled elsewhere
            }
            val reference: GuideReference = GuideReference(
                resource, type ?: "", title,
                resourceHref.substringAfter(Constants.FRAGMENT_SEPARATOR_CHAR, "")
            )
            guide.addReference(reference)
        }
    }


    /**
     * Strips off the package prefixes up to the href of the packageHref.
     * 
     * 
     * Example:
     * If the packageHref is "OEBPS/content.opf" then a resource href like "OEBPS/foo/bar.html" will be turned into "foo/bar.html"
     * 
     * @param packageHref     f
     * @param resourcesByHref g
     * @return The stripped package href
     */
    fun fixHrefs(
        packageHref: String,
        resourcesByHref: Resources
    ): Resources {
        val lastSlashPos = packageHref.lastIndexOf('/')
        if (lastSlashPos < 0) {
            return resourcesByHref
        }
        val result: Resources = Resources()
        for (resource in resourcesByHref.all) {
            if (!resource.getHref().isNullOrBlank()
                && resource.getHref().length > lastSlashPos
            ) {
                resource.setHref(resource.getHref().substring(lastSlashPos + 1))
            }
            result.add(resource)
        }
        return result
    }

    /**
     * Reads the document's spine, containing all sections in reading order.
     * 
     * @param packageDocument b
     * @param resources       b
     * @param idMapping       b
     * @return the document's spine, containing all sections in reading order.
     */
    private fun readSpine(
        packageDocument: Document, resources: Resources,
        idMapping: MutableMap<String?, String?>
    ): Spine {
        val spineElement = DOMUtil.getFirstElementByTagNameNS(
            packageDocument.getDocumentElement(),
            PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.spine
        )
        if (spineElement == null) {
            Log.e(
                TAG, ("Element " + OPFTags.Companion.spine
                    + " not found in package document, generating one automatically")
            )
            return generateSpineFromResources(resources)
        }
        val result: Spine = Spine()
        val tocResourceId = DOMUtil.getAttribute(
            spineElement,
            PackageDocumentBase.Companion.NAMESPACE_OPF,
            OPFAttributes.Companion.toc
        )
        Log.v(TAG, tocResourceId ?: "")
        result.tocResource = findTableOfContentsResource(tocResourceId, resources)
        val spineNodes = DOMUtil.getElementsByTagNameNS(
            packageDocument,
            PackageDocumentBase.Companion.NAMESPACE_OPF,
            OPFTags.Companion.itemref
        )
        if (spineNodes == null) {
            Log.e(TAG, "spineNodes is null")
            return result
        }
        val spineReferences: MutableList<SpineReference?> =
            ArrayList<SpineReference?>(spineNodes.getLength())
        for (i in 0..<spineNodes.getLength()) {
            val spineItem = spineNodes.item(i) as Element
            val itemref = DOMUtil.getAttribute(
                spineItem,
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFAttributes.Companion.idref
            )
            if (itemref.isNullOrBlank()) {
                Log.e(TAG, "itemref with missing or empty idref") // XXX
                continue
            }
            var id = idMapping.get(itemref)
            if (id == null) {
                id = itemref
            }

            val resource: Resource? = resources.getByIdOrHref(id ?: "")
            if (resource == null) {
                Log.e(TAG, "resource with id '" + id + "' not found")
                continue
            }

            val spineReference: SpineReference = SpineReference(resource)
            if (OPFValues.Companion.no.equals(
                    DOMUtil.getAttribute(
                        spineItem,
                        PackageDocumentBase.Companion.NAMESPACE_OPF,
                        OPFAttributes.Companion.linear
                    ), ignoreCase = true
                )
            ) {
                spineReference.isLinear = false
            }
            spineReferences.add(spineReference)
        }
        result.spineReferences = spineReferences.filterNotNull().toMutableList()
        return result
    }

    /**
     * Creates a spine out of all resources in the resources.
     * The generated spine consists of all XHTML pages in order of their href.
     * 
     * @param resources f
     * @return a spine created out of all resources in the resources.
     */
    private fun generateSpineFromResources(resources: Resources): Spine {
        val result: Spine = Spine()
        val resourceHrefs: MutableList<String?> = ArrayList(resources.allHrefs)
        resourceHrefs.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it ?: "" })
        for (resourceHref in resourceHrefs) {
            val resource: Resource = resources.getByHref(resourceHref ?: continue) ?: continue
            if (resource.mediaType === MediaTypes.NCX) {
                result.tocResource = resource
            } else if (resource.mediaType === MediaTypes.XHTML) {
                result.addSpineReference(SpineReference(resource))
            }
        }
        return result
    }


    /**
     * The spine tag should contain a 'toc' attribute with as value the resource id of the table of contents resource.
     * 
     * 
     * Here we try several ways of finding this table of contents resource.
     * We try the given attribute value, some often-used ones and finally look through all resources for the first resource with the table of contents mimetype.
     * 
     * @param tocResourceId g
     * @param resources     g
     * @return the Resource containing the table of contents
     */
    fun findTableOfContentsResource(
        tocResourceId: kotlin.String?,
        resources: Resources
    ): Resource? {
        var tocResource: Resource?
        //一些epub3的文件为了兼容epub2,保留的epub2的目录文件，这里优先选择epub3的xml目录
        tocResource = resources.getByProperties("nav")
        if (tocResource != null) {
            return tocResource
        }

        if (!tocResourceId.isNullOrBlank()) {
            tocResource = resources.getByIdOrHref(tocResourceId ?: "")
        }

        if (tocResource != null) {
            return tocResource
        }

        // get the first resource with the NCX mediatype
        tocResource = resources.findFirstResourceByMediaType(MediaTypes.NCX)

        if (tocResource == null) {
            for (possibleNcxItemId in POSSIBLE_NCX_ITEM_IDS) {
                tocResource = resources.getByIdOrHref(possibleNcxItemId)
                if (tocResource != null) {
                    break
                }
                tocResource = resources
                    .getByIdOrHref(possibleNcxItemId!!.uppercase())
                if (tocResource != null) {
                    break
                }
            }
        }


        if (tocResource == null) {
            Log.e(
                TAG,
                ("Could not find table of contents resource. Tried resource with id '"
                    + tocResourceId + "', " + Constants.DEFAULT_TOC_ID + ", "
                    + Constants.DEFAULT_TOC_ID.uppercase(Locale.ROOT)
                    + " and any NCX resource.")
            )
        }
        return tocResource
    }


    /**
     * Find all resources that have something to do with the coverpage and the cover image.
     * Search the meta tags and the guide references
     * 
     * @param packageDocument s
     * @return all resources that have something to do with the coverpage and the cover image.
     */
    // package
    fun findCoverHrefs(packageDocument: Document, packagePath: URI): MutableSet<kotlin.String?> {
        val result: MutableSet<kotlin.String?> = HashSet<kotlin.String?>()

        // try and find a meta tag with name = 'cover' and a non-blank id
        val coverResourceId = DOMUtil.getFindAttributeValue(
            packageDocument, PackageDocumentBase.Companion.NAMESPACE_OPF,
            OPFTags.Companion.meta, OPFAttributes.Companion.name, OPFValues.Companion.meta_cover,
            OPFAttributes.Companion.content
        )

        if (!coverResourceId.isNullOrBlank()) {
            val coverHref = DOMUtil.getFindAttributeValue(
                packageDocument, PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFTags.Companion.item, OPFAttributes.Companion.id, coverResourceId ?: "",
                OPFAttributes.Companion.href
            )
            if (!coverHref.isNullOrBlank()) {
                result.add(resolvePath(packagePath, coverHref))
            } else {
                val resolved = resolvePath(packagePath, coverResourceId)
                result.add(
                    resolved
                ) // maybe there was a cover href put in the cover id attribute
            }
        }
        // try and find a reference tag with type is 'cover' and reference is not blank
        val coverHref = DOMUtil.getFindAttributeValue(
            packageDocument,
            PackageDocumentBase.Companion.NAMESPACE_OPF,
            OPFTags.Companion.reference,
            OPFAttributes.Companion.type,
            OPFValues.Companion.reference_cover,
            OPFAttributes.Companion.href
        )
        if (!coverHref.isNullOrBlank()) {
            result.add(resolvePath(packagePath, coverHref))
        }
        return result
    }

    private fun resolvePath(parentPath: URI, href: kotlin.String?): kotlin.String? {
        val encoded = java.net.URLEncoder.encode(href ?: return null, Constants.CHARACTER_ENCODING)
        val resolved = parentPath.resolve(encoded).toString()
        try {
            return URLDecoder.decode(resolved, Constants.CHARACTER_ENCODING)
        } catch (e: UnsupportedEncodingException) {
            throw RuntimeException(e)
        }
    }

    /**
     * Finds the cover resource in the packageDocument and adds it to the book if found.
     * Keeps the cover resource in the resources map
     * 
     * @param packageDocument s
     * @param book            x
     */
    private fun readCover(packageDocument: Document, packagePath: URI, book: EpubBook) {
        val coverHrefs: MutableCollection<kotlin.String?> =
            findCoverHrefs(packageDocument, packagePath)
        for (coverHref in coverHrefs) {
            val resource: Resource? = book.resources.getByHref(coverHref ?: continue)
            if (resource == null) {
                Log.e(TAG, "Cover resource " + coverHref + " not found")
                continue
            }
            if (resource.mediaType === MediaTypes.XHTML) {
                book.coverPage = resource
            } else if (MediaTypes.isBitmapImage(resource.mediaType)) {
                book.coverImage = resource
            }
        }
    }
}
