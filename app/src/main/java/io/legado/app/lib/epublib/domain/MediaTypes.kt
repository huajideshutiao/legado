package io.legado.app.lib.epublib.domain

import io.legado.app.lib.epublib.util.StringUtil

/**
 * Manages mediatypes that are used by epubs
 * 
 * @author paul
 */
object MediaTypes {
    val XHTML: MediaType = MediaType(
        "application/xhtml+xml",
        ".xhtml", arrayOf<String?>(".htm", ".html", ".xhtml")
    )
    val EPUB: MediaType = MediaType(
        "application/epub+zip",
        ".epub"
    )
    val NCX: MediaType = MediaType(
        "application/x-dtbncx+xml",
        ".ncx"
    )

    val JAVASCRIPT: MediaType = MediaType(
        "text/javascript",
        ".js"
    )
    val CSS: MediaType = MediaType("text/css", ".css")

    // images
    val JPG: MediaType = MediaType(
        "image/jpeg", ".jpg",
        arrayOf<String?>(".jpg", ".jpeg")
    )
    val PNG: MediaType = MediaType("image/png", ".png")
    val GIF: MediaType = MediaType("image/gif", ".gif")

    val SVG: MediaType = MediaType("image/svg+xml", ".svg")

    val WEBP: MediaType = MediaType("image/webp", ".webp")

    // fonts
    val TTF: MediaType = MediaType(
        "application/x-truetype-font", ".ttf"
    )
    val OPENTYPE: MediaType = MediaType(
        "application/vnd.ms-opentype", ".otf"
    )
    val WOFF: MediaType = MediaType(
        "application/font-woff",
        ".woff"
    )

    // audio
    val MP3: MediaType = MediaType("audio/mpeg", ".mp3")
    val OGG: MediaType = MediaType("audio/ogg", ".ogg")

    // video
    val MP4: MediaType = MediaType("video/mp4", ".mp4")

    val SMIL: MediaType = MediaType(
        "application/smil+xml",
        ".smil"
    )
    val XPGT: MediaType = MediaType(
        "application/adobe-page-template+xml", ".xpgt"
    )
    val PLS: MediaType = MediaType(
        "application/pls+xml",
        ".pls"
    )
    val UNKNOWN: MediaType = MediaType("application/octet-stream", "")

    val mediaTypes: Array<MediaType> = arrayOf<MediaType>(
        XHTML, EPUB, JPG, PNG, GIF, WEBP, CSS, SVG, TTF, NCX, XPGT, OPENTYPE, WOFF,
        SMIL, PLS, JAVASCRIPT, MP3, MP4, OGG, UNKNOWN
    )

    val mediaTypesByName: MutableMap<String?, MediaType> = LinkedHashMap<String?, MediaType>()

    init {
        for (mediaType in mediaTypes) {
            mediaTypesByName[mediaType.name] = mediaType
        }
    }

    fun isBitmapImage(mediaType: MediaType?): Boolean {
        return mediaType === JPG || mediaType === PNG || mediaType === GIF || mediaType === WEBP
    }

    fun isImage(mediaType: MediaType?): Boolean {
        return mediaType === JPG || mediaType === PNG || mediaType === GIF || mediaType === SVG || mediaType === WEBP
    }

    /**
     * Gets the MediaType based on the file extension.
     * Null of no matching extension found.
     * 
     * @param filename filename
     * @return the MediaType based on the file extension.
     */
    fun determineMediaType(filename: String?): MediaType? {
        for (mediaType in mediaTypesByName.values) {
            for (extension in mediaType.extensions ?: emptyList()) {
                if (StringUtil.endsWithIgnoreCase(filename, extension)) {
                    return mediaType
                }
            }
        }
        return null
    }

    fun getMediaTypeByName(mediaTypeName: String?): MediaType? = mediaTypesByName[mediaTypeName]
}
