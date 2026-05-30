package io.legado.app.lib.epublib.epub

import io.legado.app.lib.epublib.Constants
import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.Identifier
import io.legado.app.lib.epublib.util.StringUtil
import org.xmlpull.v1.XmlSerializer

object PackageDocumentMetadataWriter : PackageDocumentBase() {
    /**
     * Writes the book's metadata.
     * 
     * @param book       book
     * @param serializer serializer
     * @throws IOException              IOException
     * @throws IllegalStateException    IllegalStateException
     * @throws IllegalArgumentException IllegalArgumentException
     */
    @Throws(
        java.lang.IllegalArgumentException::class,
        java.lang.IllegalStateException::class,
        java.io.IOException::class
    )
    fun writeMetaData(book: EpubBook, serializer: XmlSerializer) {
        serializer.startTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.metadata)
        serializer.setPrefix(
            PackageDocumentBase.Companion.PREFIX_DUBLIN_CORE,
            PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE
        )
        serializer.setPrefix(
            PackageDocumentBase.Companion.PREFIX_OPF,
            PackageDocumentBase.Companion.NAMESPACE_OPF
        )

        PackageDocumentMetadataWriter.writeIdentifiers(
            book.metadata.getIdentifiers(),
            serializer
        )
        PackageDocumentMetadataWriter.writeSimpleMetdataElements(
            DCTags.Companion.title, book.metadata.titles ?: mutableListOf(),
            serializer
        )
        PackageDocumentMetadataWriter.writeSimpleMetdataElements(
            DCTags.Companion.subject, book.metadata.subjects ?: mutableListOf(),
            serializer
        )
        PackageDocumentMetadataWriter.writeSimpleMetdataElements(
            DCTags.Companion.description,
            book.metadata.descriptions, serializer
        )
        PackageDocumentMetadataWriter.writeSimpleMetdataElements(
            DCTags.Companion.publisher,
            book.metadata.publishers, serializer
        )
        PackageDocumentMetadataWriter.writeSimpleMetdataElements(
            DCTags.Companion.type, book.metadata.types,
            serializer
        )
        PackageDocumentMetadataWriter.writeSimpleMetdataElements(
            DCTags.Companion.rights, book.metadata.rights ?: mutableListOf(),
            serializer
        )

        // write authors
        for (author in book.metadata.authors.filterNotNull()) {
            serializer.startTag(
                PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.creator
            )
            serializer.attribute(
                PackageDocumentBase.Companion.NAMESPACE_OPF, OPFAttributes.Companion.role,
                author.relator?.code
            )
            serializer.attribute(
                PackageDocumentBase.Companion.NAMESPACE_OPF, OPFAttributes.Companion.file_as,
                author.lastname + ", " + author.firstname
            )
            serializer.text(author.firstname + " " + author.lastname)
            serializer.endTag(
                PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.creator
            )
        }

        // write contributors
        for (author in book.metadata.contributors.filterNotNull()) {
            serializer.startTag(
                PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.contributor
            )
            serializer.attribute(
                PackageDocumentBase.Companion.NAMESPACE_OPF, OPFAttributes.Companion.role,
                author.relator?.code
            )
            serializer.attribute(
                PackageDocumentBase.Companion.NAMESPACE_OPF, OPFAttributes.Companion.file_as,
                author.lastname + ", " + author.firstname
            )
            serializer.text(author.firstname + " " + author.lastname)
            serializer.endTag(
                PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.contributor
            )
        }

        // write dates
        for (date in book.metadata.dates.filterNotNull()) {
            serializer.startTag(
                PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.date
            )
            if (date.event != null) {
                serializer.attribute(
                    PackageDocumentBase.Companion.NAMESPACE_OPF, OPFAttributes.Companion.event,
                    date.event.toString()
                )
            }
            serializer.text(date.value)
            serializer.endTag(
                PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.date
            )
        }

        // write language
        if (StringUtil.isNotBlank(book.metadata.language)) {
            serializer.startTag(PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE, "language")
            serializer.text(book.metadata.language)
            serializer.endTag(PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE, "language")
        }

        // write other properties
        if (book.metadata.otherProperties != null) {
            for (mapEntry in book.metadata
                .otherProperties!!.entries) {
                serializer.startTag(mapEntry.key?.getNamespaceURI() ?: "", OPFTags.Companion.meta)
                serializer.attribute(
                    EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX,
                    OPFAttributes.Companion.property, mapEntry.key?.getLocalPart() ?: ""
                )
                serializer.text(mapEntry.value)
                serializer.endTag(mapEntry.key?.getNamespaceURI() ?: "", OPFTags.Companion.meta)
            }
        }

        // write coverimage
        if (book.coverImage != null) { // write the cover image
            serializer.startTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.meta)
            serializer
                .attribute(
                    EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.name,
                    OPFValues.Companion.meta_cover
                )
            serializer
                .attribute(
                    EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.content,
                    book.coverImage!!.id
                )
            serializer.endTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.meta)
        }

        // write generator
        serializer.startTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.meta)
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.name,
            OPFValues.Companion.generator
        )
        serializer
            .attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.content,
                Constants.EPUB_GENERATOR_NAME
            )
        serializer.endTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.meta)

        // write duokan
        serializer.startTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.meta)
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.name,
            OPFValues.Companion.duokan
        )
        serializer
            .attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, OPFAttributes.Companion.content,
                Constants.EPUB_DUOKAN_NAME
            )
        serializer.endTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.meta)

        serializer.endTag(PackageDocumentBase.Companion.NAMESPACE_OPF, OPFTags.Companion.metadata)
    }

    @Throws(
        java.lang.IllegalArgumentException::class,
        java.lang.IllegalStateException::class,
        java.io.IOException::class
    )
    private fun writeSimpleMetdataElements(
        tagName: kotlin.String?,
        values: kotlin.collections.MutableList<kotlin.String?>, serializer: XmlSerializer
    ) {
        for (value in values) {
            if (StringUtil.isBlank(value)) {
                continue
            }
            serializer.startTag(PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE, tagName)
            serializer.text(value)
            serializer.endTag(PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE, tagName)
        }
    }


    /**
     * Writes out the complete list of Identifiers to the package document.
     * The first identifier for which the bookId is true is made the bookId identifier.
     * If no identifier has bookId == true then the first bookId identifier is written as the primary.
     * 
     * @param identifiers identifiers
     * @param serializer  serializer
     * @throws IllegalStateException    e
     * @throws IllegalArgumentException e
     * @
     */
    @Throws(
        java.lang.IllegalArgumentException::class,
        java.lang.IllegalStateException::class,
        java.io.IOException::class
    )
    private fun writeIdentifiers(
        identifiers: kotlin.collections.MutableList<Identifier?>,
        serializer: XmlSerializer
    ) {
        val nonNullIdentifiers = identifiers.filterNotNull().toMutableList()
        val bookIdIdentifier: Identifier? = Identifier.getBookIdIdentifier(nonNullIdentifiers)
        if (bookIdIdentifier == null) {
            return
        }

        serializer.startTag(
            PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
            DCTags.Companion.identifier
        )
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, DCAttributes.Companion.id,
            PackageDocumentBase.Companion.BOOK_ID_ID
        )
        serializer.attribute(
            PackageDocumentBase.Companion.NAMESPACE_OPF, OPFAttributes.Companion.scheme,
            bookIdIdentifier.scheme
        )
        serializer.text(bookIdIdentifier.value)
        serializer.endTag(
            PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
            DCTags.Companion.identifier
        )

        for (identifier in nonNullIdentifiers.subList(1, nonNullIdentifiers.size)) {
            if (identifier === bookIdIdentifier) {
                continue
            }
            serializer.startTag(
                PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.identifier
            )
            serializer.attribute(
                PackageDocumentBase.Companion.NAMESPACE_OPF,
                "scheme",
                identifier.scheme
            )
            serializer.text(identifier.value)
            serializer.endTag(
                PackageDocumentBase.Companion.NAMESPACE_DUBLIN_CORE,
                DCTags.Companion.identifier
            )
        }
    }
}
