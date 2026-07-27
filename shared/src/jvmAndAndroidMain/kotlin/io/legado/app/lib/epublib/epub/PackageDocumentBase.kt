package io.legado.app.lib.epublib.epub


/**
 * Functionality shared by the PackageDocumentReader and the PackageDocumentWriter
 * 
 * @author paul
 */
open class PackageDocumentBase {
    protected interface DCTags {
        companion object {
            const val title: String = "title"
            const val creator: String = "creator"
            const val subject: String = "subject"
            const val description: String = "description"
            const val publisher: String = "publisher"
            const val contributor: String = "contributor"
            const val date: String = "date"
            const val type: String = "type"
            const val format: String = "format"
            const val identifier: String = "identifier"
            const val source: String = "source"
            const val language: String = "language"
            const val relation: String = "relation"
            const val coverage: String = "coverage"
            const val rights: String = "rights"
        }
    }

    protected interface DCAttributes {
        companion object {
            const val scheme: String = "scheme"
            const val id: String = "id"
        }
    }

    protected interface OPFTags {
        companion object {
            const val metadata: String = "metadata"
            const val meta: String = "meta"
            const val manifest: String = "manifest"
            const val packageTag: String = "package"
            const val itemref: String = "itemref"
            const val spine: String = "spine"
            const val reference: String = "reference"
            const val guide: String = "guide"
            const val item: String = "item"
        }
    }

    protected interface OPFAttributes {
        companion object {
            const val uniqueIdentifier: String = "unique-identifier"
            const val idref: String = "idref"
            const val name: String = "name"
            const val content: String = "content"
            const val type: String = "type"
            const val href: String = "href"
            const val linear: String = "linear"
            const val event: String = "event"
            const val role: String = "role"
            const val file_as: String = "file-as"
            const val id: String = "id"
            const val media_type: String = "media-type"
            const val title: String = "title"
            const val toc: String = "toc"
            const val version: String = "version"
            const val scheme: String = "scheme"
            const val property: String = "property"
            //add for epub3
            /**
             * add for epub3
             */
            const val properties: String = "properties"
        }
    }

    protected interface OPFValues {
        companion object {
            const val meta_cover: String = "cover"
            const val reference_cover: String = "cover"
            const val no: String = "no"
            const val generator: String = "generator"
            const val duokan: String = "duokan-body-font"
        }
    }

    companion object {
        const val BOOK_ID_ID: String = "duokan-book-id"
        const val NAMESPACE_OPF: String = "http://www.idpf.org/2007/opf"
        const val NAMESPACE_DUBLIN_CORE: String = "http://purl.org/dc/elements/1.1/"
        const val PREFIX_DUBLIN_CORE: String = "dc"

        //public static final String PREFIX_OPF = "opf";
        //在EPUB3标准中，packge前面没有opf头，一些epub阅读器也不支持opf头。
        //Some Epub Reader not reconize op:packge,So just let it empty;
        const val PREFIX_OPF: String = ""

        //添加 version 变量来区分Epub文件的版本
        //Add the version field to distinguish the version of EPUB file
        const val version: String = "version"
        const val dateFormat: String = "yyyy-MM-dd"
    }
}