package io.legado.app.lib.epublib.domain

import io.legado.app.lib.epublib.util.StringUtil
import java.io.Serializable

class GuideReference : TitledResourceReference, Serializable {
    var type: String? = null

    @JvmOverloads
    constructor(resource: Resource?, title: String? = null) : super(resource, title)

    @JvmOverloads
    constructor(
        resource: Resource?, type: String, title: String?,
        fragmentId: String? = null
    ) : super(resource, title, fragmentId) {
        this.type = if (StringUtil.isNotBlank(type)) type.lowercase() else null
    }

    companion object {
        private const val serialVersionUID = -316179702440631834L

        const val COVER: String = "cover"
        const val TITLE_PAGE: String = "title-page"
        const val TOC: String = "toc"
        const val INDEX: String = "index"
        const val GLOSSARY: String = "glossary"
        const val ACKNOWLEDGEMENTS: String = "acknowledgements"
        const val BIBLIOGRAPHY: String = "bibliography"
        const val COLOPHON: String = "colophon"
        const val COPYRIGHT_PAGE: String = "copyright-page"
        const val DEDICATION: String = "dedication"
        const val EPIGRAPH: String = "epigraph"
        const val FOREWORD: String = "foreword"
        const val LOI: String = "loi"
        const val LOT: String = "lot"
        const val NOTES: String = "notes"
        const val PREFACE: String = "preface"
        const val TEXT: String = "text"
    }
}
