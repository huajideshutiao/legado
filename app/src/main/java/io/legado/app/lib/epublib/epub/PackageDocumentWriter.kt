package io.legado.app.lib.epublib.epub

import android.util.Log
import io.legado.app.lib.epublib.Constants
import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.Guide
import io.legado.app.lib.epublib.domain.GuideReference
import io.legado.app.lib.epublib.domain.MediaTypes
import io.legado.app.lib.epublib.domain.Resource
import io.legado.app.lib.epublib.domain.Spine
import org.xmlpull.v1.XmlSerializer
import java.io.IOException
import java.util.Collections

/**
 * Writes the opf package document as defined by namespace http://www.idpf.org/2007/opf
 * 
 * @author paul
 */
object PackageDocumentWriter : PackageDocumentBase() {
    private val TAG: String = PackageDocumentWriter::class.java.getName()

    fun write(
        epubWriter: EpubWriter, serializer: XmlSerializer,
        book: EpubBook
    ) {
        try {
            serializer.startDocument(Constants.CHARACTER_ENCODING, false)
            serializer.setPrefix(
                PackageDocumentBase.Companion.PREFIX_OPF,
                PackageDocumentBase.Companion.NAMESPACE_OPF
            )
            serializer.setPrefix(
                PackageDocumentBase.Companion.PREFIX_DUBLIN_CORE,
                PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE
            )
            serializer.startTag(
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFTags.Companion.packageTag
            )
            serializer
                .attribute(
                    EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.version,
                    book.version
                )
            serializer.attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX,
                OPFAttributes.Companion.uniqueIdentifier, PackageDocumentBase.Companion.BOOK_ID_ID
            )

            PackageDocumentMetadataWriter.writeMetaData(book, serializer)

            writeManifest(book, epubWriter, serializer)
            writeSpine(book, epubWriter, serializer)
            writeGuide(book, epubWriter, serializer)

            serializer.endTag(
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFTags.Companion.packageTag
            )
            serializer.endDocument()
            serializer.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * Writes the package's spine.
     * 
     * @param book       e
     * @param epubWriter g
     * @param serializer g
     * @throws IOException              g
     * @throws IllegalStateException    g
     * @throws IllegalArgumentException 1@throws XMLStreamException
     */
    @Suppress("unused")
    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeSpine(
        book: EpubBook, epubWriter: EpubWriter?,
        serializer: XmlSerializer
    ) {
        serializer.startTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.spine)
        val tocResource: Resource = book.spine.tocResource!!
        val tocResourceId: String? = tocResource.id
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.toc,
            tocResourceId
        )

        if (book.coverPage != null // there is a cover page
            && (book.spine.findFirstResourceById(book.coverPage!!.id!!)
                < 0)
        ) { // cover page is not already in the spine
            // write the cover html file
            serializer.startTag(
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFTags.Companion.itemref
            )
            serializer
                .attribute(
                    EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.idref,
                    book.coverPage!!.id
                )
            serializer
                .attribute(
                    EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.linear,
                    "no"
                )
            serializer.endTag(
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFTags.Companion.itemref
            )
        }
        writeSpineItems(book.spine, serializer)
        serializer.endTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.spine)
    }


    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeManifest(
        book: EpubBook, epubWriter: EpubWriter,
        serializer: XmlSerializer
    ) {
        serializer.startTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.manifest)

        serializer.startTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.item)

        //For EPUB3
        if (book.isEpub3) {
            serializer.attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX,
                OPFAttributes.Companion.properties,
                NCXDocumentV3.V3_NCX_PROPERTIES
            )
            serializer.attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX,
                OPFAttributes.Companion.id,
                NCXDocumentV3.NCX_ITEM_ID
            )
            serializer.attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX,
                OPFAttributes.Companion.href,
                NCXDocumentV3.DEFAULT_NCX_HREF
            )
            serializer.attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX,
                OPFAttributes.Companion.media_type,
                NCXDocumentV3.V3_NCX_MEDIATYPE!!.name
            )
        } else {
            serializer.attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.id,
                epubWriter.ncxId
            )
            serializer.attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX,
                OPFAttributes.Companion.href,
                epubWriter.ncxHref
            )
            serializer.attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX,
                OPFAttributes.Companion.media_type,
                epubWriter.ncxMediaType
            )
        }

        serializer.endTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.item)

        //		writeCoverResources(book, serializer);
        for (resource in getAllResourcesSortById(book)) {
            writeItem(book, resource, serializer)
        }

        serializer.endTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.manifest)
    }

    private fun getAllResourcesSortById(book: EpubBook): MutableList<Resource?> {
        val allResources: MutableList<Resource?> = ArrayList<Resource?>(
            book.resources.all
        )
        Collections.sort<Resource?>(
            allResources,
            Comparator { resource1: Resource?, resource2: Resource? ->
                resource1!!.id!!.compareTo(resource2!!.id!!, ignoreCase = true)
            })
        return allResources
    }

    /**
     * Writes a resources as an item element
     * 
     * @param resource   g
     * @param serializer g
     * @throws IOException              g
     * @throws IllegalStateException    g
     * @throws IllegalArgumentException 1@throws XMLStreamException
     */
    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeItem(
        book: EpubBook, resource: Resource?,
        serializer: XmlSerializer
    ) {
        if (resource == null ||
            (resource.mediaType === MediaTypes.NCX
                && book.spine.tocResource != null)
        ) {
            return
        }
        if (resource.id.isNullOrBlank()) {
            Log.e(
                TAG, ("resource id must not be empty (href: " + resource.getHref()
                    + ", mediatype:" + resource.mediaType + ")")
            )
            return
        }
        if (resource.getHref().isNullOrBlank()) {
            Log.e(
                TAG, ("resource href must not be empty (id: " + resource.id
                    + ", mediatype:" + resource.mediaType + ")")
            )
            return
        }
        if (resource.mediaType == null) {
            Log.e(
                TAG, ("resource mediatype must not be empty (id: " + resource.id
                    + ", href:" + resource.getHref() + ")")
            )
            return
        }
        serializer.startTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.item)
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.id,
            resource.id
        )
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.href,
            resource.getHref()
        )
        serializer
            .attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.media_type,
                resource.mediaType!!.name
            )
        serializer.endTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.item)
    }

    /**
     * List all spine references
     * 
     * @throws IOException              f
     * @throws IllegalStateException    f
     * @throws IllegalArgumentException f
     */
    @Suppress("unused")
    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeSpineItems(spine: Spine, serializer: XmlSerializer) {
        for (spineReference in spine.spineReferences!!) {
            serializer.startTag(
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFTags.Companion.itemref
            )
            serializer
                .attribute(
                    EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.idref,
                    spineReference.resourceId
                )
            if (!spineReference.isLinear) {
                serializer
                    .attribute(
                        EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.linear,
                        OPFValues.Companion.no
                    )
            }
            serializer.endTag(
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                OPFTags.Companion.itemref
            )
        }
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeGuide(
        book: EpubBook, epubWriter: EpubWriter?,
        serializer: XmlSerializer
    ) {
        serializer.startTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.guide)
        ensureCoverPageGuideReferenceWritten(
            book.guide, epubWriter,
            serializer
        )
        for (reference in book.guide.getReferences()) {
            writeGuideReference(reference, serializer)
        }
        serializer.endTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.guide)
    }

    @Suppress("unused")
    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun ensureCoverPageGuideReferenceWritten(
        guide: Guide,
        epubWriter: EpubWriter?, serializer: XmlSerializer
    ) {
        if (!(guide.getGuideReferencesByType(GuideReference.COVER).isEmpty())) {
            return
        }
        val coverPage: Resource? = guide.coverPage
        if (coverPage != null) {
            writeGuideReference(
                GuideReference(
                    guide.coverPage, GuideReference.COVER,
                    GuideReference.COVER
                ), serializer
            )
        }
    }


    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeGuideReference(
        reference: GuideReference?,
        serializer: XmlSerializer
    ) {
        if (reference == null) {
            return
        }
        serializer.startTag(
            PackageDocumentBase.Companion.NAMESPACE_OPF,
            OPFTags.Companion.reference
        )
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.type,
            reference.type
        )
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.href,
            reference.completeHref
        )
        if (!reference.title.isNullOrBlank()) {
            serializer
                .attribute(
                    EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.title,
                    reference.title
                )
        }
        serializer.endTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.reference)
    }
}