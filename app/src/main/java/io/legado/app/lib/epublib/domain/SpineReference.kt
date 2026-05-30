package io.legado.app.lib.epublib.domain

import java.io.Serializable

/**
 * A Section of a book.
 * Represents both an item in the package document and a item in the index.
 * 
 * @author paul
 */
class SpineReference @JvmOverloads constructor(
    resource: Resource?,
    /**
     * Linear denotes whether the section is Primary or Auxiliary.
     * Usually the cover page has linear set to false and all the other sections
     * have it set to true.
     * 
     * 
     * It's an optional property that readers may also ignore.
     * 
     * <blockquote>primary or auxiliary is useful for Reading Systems which
     * opt to present auxiliary content differently than primary content.
     * For example, a Reading System might opt to render auxiliary content in
     * a popup window apart from the main window which presents the primary
     * content. (For an example of the types of content that may be considered
     * auxiliary, refer to the example below and the subsequent discussion.)</blockquote>
     * 
     * @return whether the section is Primary or Auxiliary.
     * @see [OPF Spine specification](http://www.idpf.org/epub/20/spec/OPF_2.0.1_draft.htm.Section2.4)
     */
    var isLinear: Boolean //default = true;
    = true
) : ResourceReference(resource), Serializable {
    companion object {
        private val serialVersionUID = -7921609197351510248L
    }
}
