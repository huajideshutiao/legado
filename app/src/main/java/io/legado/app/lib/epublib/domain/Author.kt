package io.legado.app.lib.epublib.domain


import io.legado.app.lib.epublib.util.StringUtil
import java.io.Serializable

/**
 * Represents one of the authors of the book
 * 
 * @author paul
 */
class Author(var firstname: String?, var lastname: String?) : Serializable {
    var relator: Relator? = Relator.AUTHOR

    constructor(singleName: String?) : this("", singleName)


    override fun toString(): String {
        return this.lastname + ", " + this.firstname
    }

    override fun hashCode(): Int {
        return StringUtil.hashCode(firstname, lastname)
    }

    override fun equals(authorObject: Any?): Boolean {
        if (authorObject !is Author) {
            return false
        }
        val other = authorObject
        return StringUtil.equals(firstname, other.firstname)
            && StringUtil.equals(lastname, other.lastname)
    }

    /**
     * 设置贡献者的角色
     * 
     * @param code 角色编号
     */
    fun setRole(code: String?) {
        var result: Relator? = Relator.Companion.byCode(code)
        if (result == null) {
            result = Relator.AUTHOR
        }
        this.relator = result
    }


    companion object {
        private const val serialVersionUID = 6663408501416574200L
    }
}
