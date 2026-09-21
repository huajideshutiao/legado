package io.legado.app.lib.epublib.epub

import io.legado.app.lib.epublib.domain.Resource

@kotlin.Suppress("unused")
interface HtmlProcessor {
    fun processHtmlResource(resource: Resource?, out: java.io.OutputStream?)
}
