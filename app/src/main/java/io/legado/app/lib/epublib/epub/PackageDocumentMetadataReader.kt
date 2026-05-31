package io.legado.app.lib.epublib.epub

import android.util.Log
import io.legado.app.lib.epublib.domain.Author
import io.legado.app.lib.epublib.domain.Date
import io.legado.app.lib.epublib.domain.Identifier
import io.legado.app.lib.epublib.domain.Metadata
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.namespace.QName

/**
 * Reads the package document metadata.
 * 
 * 
 * In its own separate class because the PackageDocumentReader became a bit large and unwieldy.
 * 
 * @author paul
 */
// package
internal object PackageDocumentMetadataReader : PackageDocumentBase() {
    private val TAG: String = PackageDocumentMetadataReader::class.java.getName()

    fun readMetadata(packageDocument: Document): Metadata {
        val result = Metadata()
        val metadataElement = DOMUtil.getFirstElementByTagNameNS(
            packageDocument.getDocumentElement(),
            PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.metadata
        )
        if (metadataElement == null) {
            Log.e(TAG, "Package does not contain element " + OPFTags.Companion.metadata)
            return result
        }
        result.titles = DOMUtil.getElementsTextChild(
                metadataElement, PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.title
            )
        result.publishers = DOMUtil.getElementsTextChild(
                metadataElement, PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.publisher
            )
        result.descriptions = DOMUtil.getElementsTextChild(
                metadataElement, PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.description
            )
        result.rights = DOMUtil.getElementsTextChild(
                metadataElement, PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.rights
            )
        result.types = DOMUtil.getElementsTextChild(
                metadataElement, PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.type
            )
        result.subjects = DOMUtil.getElementsTextChild(
                metadataElement, PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.subject
            )
        result.setIdentifiers(readIdentifiers(metadataElement))
        result.authors = readCreators(metadataElement)
        result.contributors = readContributors(metadataElement)
        result.dates = readDates(metadataElement)
        result.otherProperties = readOtherProperties(metadataElement)
        result.setMetaAttributes(readMetaProperties(metadataElement))
        val languageTag = DOMUtil.getFirstElementByTagNameNS(
            metadataElement, PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
            DCTags.Companion.language
        )
        if (languageTag != null) {
            result.language = DOMUtil.getTextChildrenContent(languageTag)
        }

        return result
    }

    /**
     * consumes meta tags that have a property attribute as defined in the standard. For example:
     * &lt;meta property="rendition:layout"&gt;pre-paginated&lt;/meta&gt;
     * 
     * @param metadataElement metadataElement
     * @return Map<QName></QName>, String>
     */
    private fun readOtherProperties(
        metadataElement: Element
    ): MutableMap<QName?, String?> {
        val result: MutableMap<QName?, String?> = HashMap<QName?, String?>()

        val metaTags = metadataElement.getElementsByTagName(OPFTags.Companion.meta)
        for (i in 0..<metaTags.getLength()) {
            val metaNode = metaTags.item(i)
            val property = metaNode.getAttributes()
                .getNamedItem(OPFAttributes.Companion.property)
            if (property != null) {
                val name = property.getNodeValue()
                val value = metaNode.getTextContent()
                result.put(QName(name), value)
            }
        }

        return result
    }

    /**
     * consumes meta tags that have a property attribute as defined in the standard. For example:
     * &lt;meta property="rendition:layout"&gt;pre-paginated&lt;/meta&gt;
     * 
     * @param metadataElement metadataElement
     * @return Map<String></String>, String>
     */
    private fun readMetaProperties(
        metadataElement: Element
    ): MutableMap<String?, String?> {
        val result: MutableMap<String?, String?> = HashMap<String?, String?>()

        val metaTags = metadataElement.getElementsByTagName(OPFTags.Companion.meta)
        for (i in 0..<metaTags.getLength()) {
            val metaElement = metaTags.item(i) as Element
            val name = metaElement.getAttribute(OPFAttributes.Companion.name)
            val value = metaElement.getAttribute(OPFAttributes.Companion.content)
            result.put(name, value)
        }

        return result
    }

    private fun getBookIdId(document: Document): String? {
        val packageElement = DOMUtil.getFirstElementByTagNameNS(
            document.getDocumentElement(),
            PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.packageTag
        )
        if (packageElement == null) {
            return null
        }
        return DOMUtil.getAttribute(
            packageElement,
            PackageDocumentBase.Companion.NAMESPACE_OPF,
            OPFAttributes.Companion.uniqueIdentifier
        )
    }

    private fun readCreators(metadataElement: Element): MutableList<Author?> {
        return readAuthors(DCTags.Companion.creator, metadataElement)
    }

    private fun readContributors(metadataElement: Element): MutableList<Author?> {
        return readAuthors(DCTags.Companion.contributor, metadataElement)
    }

    private fun readAuthors(
        authorTag: String?,
        metadataElement: Element
    ): MutableList<Author?> {
        val elements = metadataElement
            .getElementsByTagNameNS(PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE, authorTag)
        val result: MutableList<Author?> = ArrayList<Author?>(elements.getLength())
        for (i in 0..<elements.getLength()) {
            val authorElement = elements.item(i) as Element
            val author: Author? = createAuthor(authorElement)
            if (author != null) {
                result.add(author)
            }
        }
        return result
    }

    private fun readDates(metadataElement: Element): MutableList<Date?> {
        val elements = metadataElement
            .getElementsByTagNameNS(
                PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.date
            )
        val result: MutableList<Date?> = ArrayList<Date?>(elements.getLength())
        for (i in 0..<elements.getLength()) {
            val dateElement = elements.item(i) as Element
            val date: Date?
            try {
                date = Date(
                    DOMUtil.getTextChildrenContent(dateElement) ?: "",
                    DOMUtil.getAttribute(
                        dateElement,
                        PackageDocumentBase.Companion.NAMESPACE_OPF,
                        OPFAttributes.Companion.event
                    )
                )
                result.add(date)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, e.message!!)
            }
        }
        return result
    }

    private fun createAuthor(authorElement: Element): Author? {
        val authorString: String = DOMUtil.getTextChildrenContent(authorElement) ?: return null
        if (authorString.isNullOrBlank()) {
            return null
        }
        val spacePos = authorString.lastIndexOf(' ')
        val result: Author?
        if (spacePos < 0) {
            result = Author(authorString)
        } else {
            result = Author(
                authorString.substring(0, spacePos),
                authorString.substring(spacePos + 1)
            )
        }
        result.setRole(
            DOMUtil.getAttribute(
                authorElement,
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFAttributes.Companion.role
            )
        )
        return result
    }


    private fun readIdentifiers(metadataElement: Element): MutableList<Identifier?> {
        val identifierElements = metadataElement
            .getElementsByTagNameNS(
                PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.identifier
            )
        if (identifierElements.getLength() == 0) {
            Log.e(TAG, "Package does not contain element " + DCTags.Companion.identifier)
            return ArrayList<Identifier?>()
        }
        val bookIdId = getBookIdId(metadataElement.getOwnerDocument())
        val result: MutableList<Identifier?> = ArrayList<Identifier?>(
            identifierElements.getLength()
        )
        for (i in 0..<identifierElements.getLength()) {
            val identifierElement = identifierElements.item(i) as Element
            val schemeName = DOMUtil.getAttribute(
                identifierElement,
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                DCAttributes.Companion.scheme
            )
            val identifierValue = DOMUtil.getTextChildrenContent(identifierElement)
            if (identifierValue.isNullOrBlank()) {
                continue
            }
            val identifier: Identifier = Identifier(schemeName ?: "", identifierValue ?: "")
            if (identifierElement.getAttribute("id") == bookIdId) {
                identifier.isBookId = true
            }
            result.add(identifier)
        }
        return result
    }
}
